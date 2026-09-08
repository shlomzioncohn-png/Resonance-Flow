package com.rnd.bridge.enrichment;

/**
 * How a source item relates to the ref it mentions - "blocks #123" and
 * "Fixes #45" are not the same claim, and Chain treats them differently
 * when it builds the dependency graph from these suggestions.
 */
public enum DependencyRelation {
    BLOCKS,
    BLOCKED_BY,
    FIXES,
    RELATES_TO
}
