"""LLM extraction + classification.

Turns unstructured content (email, Slack, WhatsApp, meetings, OCR'd docs,
transcribed audio) into candidate tasks conforming to the canonical Task
schema owned by the Inbox team. Also handles urgency/importance
classification and duration estimation.
"""
