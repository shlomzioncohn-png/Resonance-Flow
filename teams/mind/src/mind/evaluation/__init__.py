"""Evaluation harness + metrics.

A runner that scores any ranking model against the fixed persona scenario
set and reports a number. Built *before* the model it measures — see
MIND-01-System-Overview.md §5 for why. Metric (top-1 accuracy vs.
MRR/nDCG) is an open decision to close with mentors in week 1.
"""
