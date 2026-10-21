package com.rnd.bridge.ai;

/**
 * Entry point to Bridge's AI infrastructure. A usage is a name for one way Bridge uses a model
 * (e.g. "media-meeting"); bridge.ai.usages.&lt;name&gt; may pick its model, fallbacks and limits,
 * and a usage with no entry there gets bridge.ai.default-model and bridge.ai.defaults.
 * <p>
 * Called "usage", not "profile", on purpose: Spring's @Profile already means something else in
 * this codebase (see teams/bridge/CLAUDE.md §5).
 */
public interface LlmClients {

    LlmClient forUsage(String usage);

    /** Whether this usage's primary model has an API key - false means "don't call it at all". */
    boolean isConfigured(String usage);
}
