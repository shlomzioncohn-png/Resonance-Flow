package com.rnd.chain.events;

/**
 * Thrown when an event references a task that doesn't exist in Chain yet - e.g. a
 * task.dependencies.proposed event arriving before the task.created event for one of the
 * referenced ids. The team spec guarantees Mind only ever sends final, valid ids, so this is
 * always a timing/ordering gap, never a genuinely invalid reference - retryable, not sent
 * straight to the error topic.
 *
 * TODO(#377): once Postgres persistence adds upsert/placeholder-row support to TaskRepository,
 * replace this retry-based handling with an upsert-placeholder pattern (create a minimal
 * placeholder Task immediately instead of retrying) - see DependencyProposedEventHandler's class
 * Javadoc for the full design and why it isn't built yet.
 */
public class ReferencedTaskNotFoundException extends ChainTransientProcessingException {

    public ReferencedTaskNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
