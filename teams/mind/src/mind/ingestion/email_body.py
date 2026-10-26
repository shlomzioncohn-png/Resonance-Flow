"""An email's BODY as Bridge lands it -> the text a person reads (#968).

Bridge's email lane lands the whole record as the BODY (FixtureEmailSourceReader:
the record's JSON as it is), each attachment inside it as base64. That base64
is bytes, not text: sent on, it fills the content cap and pushes the item's
other parts out of the model's sight. Each attachment lands again as its own
file and is converted there (OCR, ASR), so here only its name is kept.

A body that is not such a record goes as it is, with a WARNING naming the file:
losing the email would be worse than sending it unread, and the log is where
a change in Bridge's shape shows. That includes the Gmail API message
RealEmailSourceReader lands, whose text is base64url inside `payload.parts`;
Bridge already computes the plain text, and landing it is asked of Bridge.
"""

import json
import logging

logger = logging.getLogger(__name__)

# The headers a person reads, in a mail client's order: (label, record field).
_HEADERS = (("From", "from"), ("To", "to"), ("Subject", "subject"), ("Date", "date"))


def _texts(value: object) -> list[str]:
    """A field's text values: a string, or the strings in a list, an address
    record ({"name", "email"}) giving its email; nothing else. Each on one
    line, or a header could add lines to the text, a part heading among them."""

    values = value if isinstance(value, list) else [value]
    values = [v.get("email") if isinstance(v, dict) else v for v in values]
    return [" ".join(v.split()) for v in values if isinstance(v, str) and v.strip()]


def email_body_text(text: str, object_key: str) -> str:
    try:
        record = json.loads(text.lstrip("\ufeff"))
    except (ValueError, RecursionError):  # nested too deep is unreadable too
        record = None
    if not isinstance(record, dict) or not isinstance(record.get("body"), str):
        # The key only: Bridge may have classified the item HIGH sensitivity.
        if isinstance(record, dict) and "payload" in record:
            # Every real Gmail email, until Bridge lands the plain text: a
            # warning of its own, so an unknown shape still stands out.
            logger.warning(
                "email_body: %s is a Gmail API message (its text base64url in "
                "payload.parts), sent as it is",
                object_key,
            )
        else:
            logger.warning("email_body: %s is not an email record, sent as it is", object_key)
        return text

    lines = [
        f"{label}: {', '.join(values)}"
        for label, field in _HEADERS
        if (values := _texts(record.get(field)))
    ]
    attachments = record.get("attachments")
    if isinstance(attachments, list):
        names = _texts([a.get("filename") for a in attachments if isinstance(a, dict)])
        if names:
            lines.append(f"Attachments: {', '.join(names)}")
    if not lines:
        return record["body"]
    return "\n".join(lines) + "\n\n" + record["body"]
