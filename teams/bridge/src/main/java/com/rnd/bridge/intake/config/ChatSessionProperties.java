package com.rnd.bridge.intake.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * correctionDelaySeconds is how long SlackSessionScheduler waits before publishing a correction
 * (edit/partial deletion/full deletion) to a group that may have already been published -
 * collapsing a burst of rapid corrections into a single publish via scheduleOrReschedule's
 * existing cancel-and-replace timer logic, instead of racing each edit straight to Kafka. See
 * SlackSessionScheduler.computeDeadline/maybePublished for how this is used; proposed default 60
 * seconds, not derived from existing config - see docs/plans/slack-message-deleted.md.
 * {@code @ConstructorBinding} on the all-args constructor is required once a second
 * (non-canonical) constructor exists - Spring Boot's constructor-binding auto-detection only
 * infers the binding constructor when there is exactly ONE declared; with two, it silently fell
 * back to plain JavaBean-style binding (which needs a no-arg constructor), failing startup with
 * "No default constructor found" everywhere this bean is wired in. Explicitly marking the
 * intended constructor fixes that regardless of how many convenience constructors exist.
 * {@code @DefaultValue} covers an environment that hasn't set this key yet (e.g.
 * application-local.yml) without failing startup over a missing primitive.
 */
@ConfigurationProperties(prefix = "bridge.intake.chat")
public record ChatSessionProperties(int sessionGapMinutes, int correctionDelaySeconds) {

    @ConstructorBinding
    public ChatSessionProperties(int sessionGapMinutes, @DefaultValue("60") int correctionDelaySeconds) {
        this.sessionGapMinutes = sessionGapMinutes;
        this.correctionDelaySeconds = correctionDelaySeconds;
    }

    /** Convenience constructor for every existing call site built before correctionDelaySeconds
     * existed - defaults to the proposed 60-second value. Never used for real property binding
     * (see {@code @ConstructorBinding} above, which pins that to the other constructor). */
    public ChatSessionProperties(int sessionGapMinutes) {
        this(sessionGapMinutes, 60);
    }
}
