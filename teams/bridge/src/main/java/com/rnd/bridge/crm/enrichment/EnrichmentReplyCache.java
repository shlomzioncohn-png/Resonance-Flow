package com.rnd.bridge.crm.enrichment;

import com.rnd.bridge.task.enrichment.EnrichmentOutput;
import com.rnd.bridge.task.model.TaskStatus;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.function.Supplier;

/**
 * In-memory, thread-safe cache of CRM-deal LLM enrichment outcomes, keyed by
 * {@code sourceRef} + a content fingerprint (title, description, status, deadline,
 * prompt version) - see {@code docs/crm-llm-enrichment-design.md} section 4.
 * Deliberately excludes {@code asOf} and {@code urgency}: neither reaches the model
 * (see {@link EnrichmentPromptBuilder}), so neither should ever force a re-call.
 * <p>
 * <b>Isolation.</b> {@code sourceRef} is {@code Deal.sourceRefPrefix() + dealId()}
 * ("crm:hubspot:...", "crm:salesforce:...", "crm:..."), so two sources can never
 * collide on the same raw deal id. It does not itself encode a tenant: v1 is a
 * single-tenant proof of concept (one {@code CRM_ORGANIZATION_ID} per running Bridge
 * process, enforced outside {@link com.rnd.bridge.task.enrichment.EnrichmentInput}
 * entirely - that record has no tenant field, and is not this class's to change, see
 * {@code CLAUDE.md}). Because this cache is a plain in-process map with no cross-JVM
 * sharing, and one process only ever serves one tenant, a cross-organization
 * collision is not reachable today. A real multi-tenant deployment must add tenant
 * identity to the key explicitly, as design doc section 4 already calls out.
 * <p>
 * <b>Eviction.</b> Bounded by {@link #MAX_ENTRIES}: entries and FIFO eviction order
 * live in the SAME {@link LinkedHashMap} (insertion order, not access order), guarded
 * by a single lock, so the two can never disagree - unlike an earlier version that kept
 * them in a separate {@code ConcurrentHashMap} + {@code ConcurrentLinkedQueue}, where
 * an expired-then-recomputed key left a stale queue entry behind that could later evict
 * the wrong (newer) mapping, and the queue could grow without bound even while the map
 * stayed capped. Re-inserting an existing key (e.g. an expired rejection, recomputed)
 * removes-then-puts it, so it moves to the most-recently-inserted position instead of
 * adding a duplicate. Not a true LRU, but simple and genuinely bounded, which is enough
 * for a POC cache that's lost on restart anyway.
 * <p>
 * <b>No single-flight.</b> The real model call happens with the lock held nowhere -
 * deliberately. Holding this cache's lock for the entire LLM round-trip would stall
 * every unrelated cache operation behind one slow call. Two threads that both miss on
 * the exact same key at the same time will both call the model and both attempt to
 * store their own result - the last writer under the lock wins the map entry, but each
 * caller still gets back what it itself computed. No in-flight deduplication is added
 * for this: {@code CrmSyncService} runs each source's sync sequentially, not
 * concurrently with itself, so this race is not the real workload.
 */
final class EnrichmentReplyCache {

    static final int MAX_ENTRIES = 10_000;
    static final Duration REJECTED_TTL = Duration.ofHours(6);

    private final Clock clock;
    private final Supplier<String> promptVersion;
    private final int maxEntries;
    private final Object lock = new Object();
    private final LinkedHashMap<CacheKey, CacheEntry> entries = new LinkedHashMap<>();

    EnrichmentReplyCache(Clock clock, Supplier<String> promptVersion) {
        this(clock, promptVersion, MAX_ENTRIES);
    }

    /** Test-only: a smaller bound makes the eviction policy cheap to exercise directly. */
    EnrichmentReplyCache(Clock clock, Supplier<String> promptVersion, int maxEntries) {
        this.clock = clock;
        this.promptVersion = promptVersion;
        this.maxEntries = maxEntries;
    }

    /** What the caller's computation produced - whether and how it should be cached. */
    public sealed interface Outcome {
        record Success(EnrichmentOutput output) implements Outcome {
        }

        /** A rejected reply - cached with a bounded TTL (never retried on every sync). */
        record Rejected() implements Outcome {
        }

        /** Quota exhaustion, timeout, provider error or unexpected failure - never cached. */
        record Transient(EnrichmentOutput output) implements Outcome {
        }
    }

    EnrichmentOutput getOrCompute(String sourceRef, String title, String description,
                                 TaskStatus status, Instant deadline, Supplier<Outcome> computation) {
        CacheKey key = new CacheKey(sourceRef, fingerprint(title, description, status, deadline));

        CacheEntry cached = lookup(key);
        if (cached != null) {
            return outputOf(cached);
        }

        // Deliberately outside the lock (see class Javadoc "No single-flight") - and if this
        // throws, execution never reaches store() below, so a failing compute (quota,
        // timeout, interrupted wait, any RuntimeException) is never cached under any outcome,
        // successful or rejected. The exception propagates to the caller unchanged.
        Outcome outcome = computation.get();
        if (outcome instanceof Outcome.Transient transientOutcome) {
            return transientOutcome.output();
        }

        CacheEntry computed = outcome instanceof Outcome.Success success
                ? new CacheEntry.Success(success.output())
                : new CacheEntry.Rejected(clock.instant().plus(REJECTED_TTL));

        store(key, computed);
        return outputOf(computed);
    }

    private CacheEntry lookup(CacheKey key) {
        synchronized (lock) {
            CacheEntry existing = entries.get(key);
            if (existing instanceof CacheEntry.Rejected rejected && !clock.instant().isBefore(rejected.expiresAt())) {
                entries.remove(key);
                return null;
            }
            return existing;
        }
    }

    private void store(CacheKey key, CacheEntry computed) {
        synchronized (lock) {
            // Remove before put (not a plain put) so re-inserting an existing key moves it
            // to the most-recently-inserted position in iteration order, rather than keeping
            // its original position with a refreshed value - a key whose rejection just
            // expired and was recomputed must count as fresh, not stale.
            entries.remove(key);
            entries.put(key, computed);
            Iterator<CacheKey> oldestFirst = entries.keySet().iterator();
            while (entries.size() > maxEntries && oldestFirst.hasNext()) {
                oldestFirst.next();
                oldestFirst.remove();
            }
        }
    }

    private static EnrichmentOutput outputOf(CacheEntry entry) {
        return switch (entry) {
            case CacheEntry.Success success -> success.output();
            case CacheEntry.Rejected ignored -> EnrichmentOutput.empty();
        };
    }

    /** Test-only: current entry count, to verify the bounded-eviction policy directly. */
    int size() {
        synchronized (lock) {
            return entries.size();
        }
    }

    private String fingerprint(String title, String description, TaskStatus status, Instant deadline) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateWithLengthPrefix(digest, orEmpty(title));
            updateWithLengthPrefix(digest, orEmpty(description));
            updateWithLengthPrefix(digest, status == null ? "" : status.name());
            updateWithLengthPrefix(digest, deadline == null ? "" : deadline.toString());
            updateWithLengthPrefix(digest, promptVersion.get());
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a JDK-guaranteed algorithm (Java Security Standard Algorithm Names) - can't happen.
            throw new IllegalStateException(e);
        }
    }

    /**
     * Each field is preceded by its own byte length so the concatenation is injective -
     * no two distinct (title, description, status, deadline, promptVersion) tuples can
     * hash the same way just because one field's text happens to contain a delimiter.
     */
    private static void updateWithLengthPrefix(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    private record CacheKey(String sourceRef, String fingerprint) {
    }

    private sealed interface CacheEntry {
        record Success(EnrichmentOutput output) implements CacheEntry {
        }

        record Rejected(Instant expiresAt) implements CacheEntry {
        }
    }
}
