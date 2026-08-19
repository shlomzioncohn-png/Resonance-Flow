"""Segmented ranking + hierarchical value propagation.

Produces a per-task score using tier-native features (operational /
tactical / strategic). This is the exact API boundary with Chain: MIND
publishes the score, Chain consumes it for constraint-satisfaction
selection. MIND never decides what a person actually does next.
"""
