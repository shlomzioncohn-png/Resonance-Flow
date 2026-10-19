"""Bridge intake (#349).

Consumes raw content landed by the Bridge team in object storage (+ pointer
events on bridge.raw.landed) and turns it into text for extraction. This
module does not talk to end users or other teams' databases directly — only
to Bridge's handoff surface. See README.md.
"""
