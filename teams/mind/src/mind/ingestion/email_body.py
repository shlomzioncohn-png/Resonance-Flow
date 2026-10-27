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
from dataclasses import dataclass
from email.utils import getaddresses

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


@dataclass(frozen=True)
class EmailBody:
    """An email's text, and who it is from and to, read from the record once.

    The sender and recipients are for the owner (#132): the one recipient
    asked may be it, the sender never is. They come from the record, never
    from the text, where the From and To lines are only for the model.
    """

    text: str
    sender: str | None = None
    recipients: tuple[str, ...] = ()


def _sender(value: object) -> str | None:
    """One sender, or None. An address record keeps its name beside the
    address ("Lisa Grant <lisa@…>"), and a name with no address is kept
    alone: a sender's name is often not spelled by the address, and it is
    what tells their signature ("Thanks, Lisa") apart."""

    if isinstance(value, list) and len(value) == 1 and isinstance(value[0], dict):
        value = value[0]
    if isinstance(value, dict):
        emails = _texts(value)
        email = emails[0] if emails else None
        name = value.get("name")
        name = " ".join(name.split()) if isinstance(name, str) else ""
        if email and name:
            return f"{name} <{email}>"
        return email or name or None
    senders = _texts(value)
    return senders[0] if len(senders) == 1 else None


# What a display name must be quoted for, so the address can be read back
# out of "Name <address>" (owner.what_to_look_up): "Cohen, Dana" has a comma.
_SPECIALS = set('()<>@,;:\\".[]')


def _person(name: str, address: str) -> str:
    name, address = " ".join(name.split()), address.strip()
    if not name:
        return address
    if _SPECIALS & set(name):
        name = '"' + name.replace("\\", "\\\\").replace('"', '\\"') + '"'
    return f"{name} <{address}>"


def _addresses(value: object) -> tuple[str, ...]:
    """Each recipient, one person each, written the way a header writes one:
    "Dana Cohen <dana@…>", or the address alone when the record has no name.
    A header puts several in a string; an address record keeps its name. The
    name tells whether a name the model gave is the recipient's; Inbox is
    asked by the address alone. What holds no address names nobody."""

    values = value if isinstance(value, list) else [value]
    people: list[tuple[str, str]] = []
    for v in values:
        if isinstance(v, dict):
            name, email = v.get("name"), v.get("email")
            if isinstance(email, str):
                people.append((name if isinstance(name, str) else "", email))
        elif isinstance(v, str):
            people.extend(getaddresses([" ".join(v.split())]))
    return tuple(_person(name, address) for name, address in people if "@" in address)


def email_body_text(text: str, object_key: str) -> str:
    return read_email_body(text, object_key).text


def read_email_body(text: str, object_key: str) -> EmailBody:
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
        return EmailBody(text)

    sender, recipients = _sender(record.get("from")), _addresses(record.get("to"))
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
        return EmailBody(record["body"], sender, recipients)
    return EmailBody("\n".join(lines) + "\n\n" + record["body"], sender, recipients)
