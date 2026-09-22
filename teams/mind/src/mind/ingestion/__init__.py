"""Bridge intake and corpus loading.

Consumes raw content landed by the Bridge team in object storage (+ pointer
events) and loads evaluation/training corpora (AMI, RVL-CDIP/FUNSD, Enron,
SAMSum, etc.). This module does not talk to end users or other teams'
databases directly — only to Bridge's handoff surface and local corpora.
"""
