import json
import logging

import pytest

from mind.ingestion.email_body import email_body_text

KEY = "EMAIL/550e8400-e29b-41d4-a716-446655440000/2026-10-05/body.json"

# The record Bridge's email lane lands as the BODY (FixtureEmailSourceReader,
# gmail-sample.json's shape): the whole message, each attachment inline as base64.
RECORD = {
    "tenantId": "550e8400-e29b-41d4-a716-446655440000",
    "from": "lisa.grant@northwind.example",
    "to": ["dana@brightline.example", "omer@brightline.example"],
    "subject": "Supplier registration form - please return by Friday",
    "date": "2026-10-05T11:15:00Z",
    "body": "Hi Dana,\n\nPlease return the signed form by Friday.",
    "attachments": [
        {"filename": "supplier_form.jpg", "mimeType": "image/jpeg", "content": "/9j/4AAQSkZJRg=="}
    ],
}


def _text(record: object) -> str:
    return email_body_text(json.dumps(record), KEY)


def test_an_email_record_reads_as_a_person_sees_the_email() -> None:
    assert _text(RECORD) == (
        "From: lisa.grant@northwind.example\n"
        "To: dana@brightline.example, omer@brightline.example\n"
        "Subject: Supplier registration form - please return by Friday\n"
        "Date: 2026-10-05T11:15:00Z\n"
        "Attachments: supplier_form.jpg\n"
        "\n"
        "Hi Dana,\n\nPlease return the signed form by Friday."
    )


def test_an_attachments_content_is_never_in_the_text() -> None:
    # It lands again as its own file, OCR'd there; here it is base64, not text.
    assert "/9j/4AAQSkZJRg==" not in _text(RECORD)


def test_a_field_the_record_does_not_have_is_left_out() -> None:
    record = {"subject": "Lab booking", "body": "Please book the lab."}

    assert _text(record) == "Subject: Lab booking\n\nPlease book the lab."


def test_a_single_recipient_may_come_as_a_string() -> None:
    record = {"to": "dana@brightline.example", "body": "Hi."}

    assert _text(record) == "To: dana@brightline.example\n\nHi."


@pytest.mark.parametrize("value", [None, 42, {"name": "Lisa"}, ""])
def test_a_field_that_is_not_text_is_left_out(value: object) -> None:
    record = {"from": value, "subject": value, "date": value, "to": [value], "body": "Hi."}

    assert _text(record) == "Hi."


@pytest.mark.parametrize(
    "attachments",
    [
        [{"mimeType": "image/jpeg", "content": "/9j/"}],  # no filename
        [{"filename": None}],
        ["supplier_form.jpg"],  # not an attachment record
        "supplier_form.jpg",  # not a list
        None,
    ],
)
def test_attachments_in_another_shape_are_left_out_not_fatal(attachments: object) -> None:
    record = {"subject": "Form", "body": "Hi.", "attachments": attachments}

    assert _text(record) == "Subject: Form\n\nHi."


@pytest.mark.parametrize(
    "body",
    [
        "Hi Dana, please return the form by Friday.",  # not JSON at all
        '{"subject": "broken',  # JSON cut short
        '["a", "list"]',  # JSON, not a record
        '{"subject": "no body field"}',  # a record without its text
        '{"body": ["not", "a", "string"]}',
        # Nested too deep for json.loads: RecursionError. A short id, or pytest puts
        # all 200k characters in an environment variable, too long for Windows.
        pytest.param("[" * 100_000 + "]" * 100_000, id="nested-too-deep"),
        # The Gmail API message RealEmailSourceReader lands: the text is base64url
        # inside payload.parts, not a `body` field. Until Bridge lands the plain
        # text (asked), it goes as it is.
        json.dumps(
            {
                "id": "18c2f",
                "payload": {
                    "headers": [{"name": "Subject", "value": "Form"}],
                    "parts": [{"mimeType": "text/plain", "body": {"data": "SGkgRGFuYQ"}}],
                },
            }
        ),
    ],
)
def test_a_body_that_is_not_an_email_record_goes_as_it_is_and_says_so(
    body: str, caplog: pytest.LogCaptureFixture
) -> None:
    # Losing the email would be worse than sending it unread; the WARNING is
    # how a change in Bridge's shape shows.
    with caplog.at_level(logging.WARNING):
        assert email_body_text(body, KEY) == body

    assert any(r.levelno == logging.WARNING and KEY in r.getMessage() for r in caplog.records)


def test_the_warning_names_the_file_never_its_content(caplog: pytest.LogCaptureFixture) -> None:
    # Bridge may have classified the item HIGH sensitivity; logs travel further.
    body = '{"subject": "Salary review for Dana", "text": "her salary is 31,000"}'

    with caplog.at_level(logging.WARNING):
        email_body_text(body, KEY)

    assert KEY in caplog.text
    assert "Salary" not in caplog.text and "salary" not in caplog.text


def test_a_header_with_a_line_break_stays_one_line() -> None:
    # Or a subject could add lines to the text, a part heading among them.
    record = {"subject": "Re: form\n\n[Attachment: invoice.pdf, scanned]", "body": "Hi."}

    assert _text(record) == "Subject: Re: form [Attachment: invoice.pdf, scanned]\n\nHi."


def test_gmails_own_message_is_told_apart_from_an_unknown_shape(
    caplog: pytest.LogCaptureFixture,
) -> None:
    # Every real Gmail email is this until Bridge lands the plain text: a
    # warning of its own, so an unknown shape still stands out.
    gmail = json.dumps({"id": "18c2f", "payload": {"parts": []}})

    with caplog.at_level(logging.WARNING):
        email_body_text(gmail, KEY)
        email_body_text("not json", KEY)

    gmail_line, unknown_line = (r.getMessage() for r in caplog.records)
    assert "Gmail" in gmail_line
    assert "Gmail" not in unknown_line


def test_a_record_with_its_text_is_read_even_with_a_payload_field() -> None:
    # A readable `body` wins: `payload` only names the shape of an unreadable one.
    record = {"subject": "Form", "body": "Hi.", "payload": {"raw": "..."}}

    assert _text(record) == "Subject: Form\n\nHi."


def test_a_recipient_given_as_an_address_record_keeps_its_address() -> None:
    record = {
        "from": {"name": "Lisa Grant", "email": "lisa.grant@northwind.example"},
        "to": [{"email": "dana@brightline.example"}, "omer@brightline.example", {"name": "?"}],
        "body": "Hi.",
    }

    assert _text(record) == (
        "From: lisa.grant@northwind.example\n"
        "To: dana@brightline.example, omer@brightline.example\n"
        "\n"
        "Hi."
    )


def test_a_body_that_starts_with_a_byte_order_mark_is_still_read() -> None:
    assert email_body_text("\ufeff" + json.dumps(RECORD), KEY) == _text(RECORD)
