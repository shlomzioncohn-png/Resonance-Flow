"""Bridge intake (#349) and the AMI transcripts for the extraction eval (#784).

Consumes raw content landed by the Bridge team in object storage (+ pointer
events on bridge.raw.landed) and turns it into text for extraction, and loads
AMI's human transcripts from the local corpus. This module does not talk to
end users or other teams' databases directly — only to Bridge's handoff
surface and local corpora. See README.md.
"""
