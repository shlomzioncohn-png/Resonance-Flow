from typing import Any, cast

import pytest
from minio.error import S3Error

from mind.ingestion.event_objects import ObjectNotFound, ObjectTooLarge
from mind.ingestion.minio_reader import MinioObjectReader, minio_endpoint

BUCKET = "bridge-raw"
KEY = "ZOOM/550e8400-e29b-41d4-a716-446655440000/2026-10-18/meeting.vtt"
LIMIT = 10  # bytes, small enough to test both sides of it


def _s3_error(code: str) -> S3Error:
    return S3Error(cast(Any, None), code, f"{code} from the fake", KEY, "request", "host")


class _Response:
    def __init__(self, data: bytes | Exception) -> None:
        self.data = data
        self.released = False

    def read(self, amt: int | None = None) -> bytes:
        self.asked_amt = amt
        if isinstance(self.data, Exception):
            raise self.data
        return self.data if amt is None else self.data[:amt]

    def close(self) -> None:
        pass

    def release_conn(self) -> None:
        self.released = True


class _Client:
    """Stands in for minio.Minio: only get_object is used, with S3's range rules."""

    def __init__(
        self,
        objects: dict[str, bytes | Exception],
        error: S3Error | None = None,
        honours_range: bool = True,
    ) -> None:
        self.objects, self.error, self.honours_range = objects, error, honours_range
        self.asked: list[tuple[str, str, int]] = []
        self.responses: list[_Response] = []

    def get_object(self, bucket_name: str, object_name: str, length: int = 0) -> _Response:
        self.asked.append((bucket_name, object_name, length))
        if self.error is not None:
            raise self.error
        if object_name not in self.objects:
            raise _s3_error("NoSuchKey")
        data = self.objects[object_name]
        if isinstance(data, bytes) and length and self.honours_range:
            if not data:
                raise _s3_error("InvalidRange")  # S3 refuses any range of an empty object
            data = data[:length]
        self.responses.append(_Response(data))
        return self.responses[-1]


def _read(client: _Client) -> bytes:
    return MinioObjectReader(client, BUCKET, max_bytes=LIMIT)(KEY)


def test_an_object_is_read_from_the_bucket_and_its_connection_released() -> None:
    client = _Client({KEY: b"WEBVTT"})

    assert _read(client) == b"WEBVTT"
    assert client.asked == [(BUCKET, KEY, LIMIT + 1)]
    assert client.responses[0].released


def test_an_object_over_the_limit_is_never_read_whole() -> None:
    client = _Client({KEY: b"x" * (LIMIT + 5)})

    with pytest.raises(ObjectTooLarge):
        _read(client)
    assert client.asked == [(BUCKET, KEY, LIMIT + 1)]  # at most one byte past the limit
    assert client.responses[0].released


def test_a_server_that_ignores_the_range_still_cannot_fill_memory() -> None:
    # Some S3-compatible gateways answer 200 with the whole object.
    client = _Client({KEY: b"x" * (LIMIT * 100)}, honours_range=False)

    with pytest.raises(ObjectTooLarge):
        _read(client)
    assert client.responses[0].asked_amt == LIMIT + 1


def test_an_object_exactly_at_the_limit_is_read() -> None:
    assert _read(_Client({KEY: b"x" * LIMIT})) == b"x" * LIMIT


def test_an_empty_object_is_read_as_empty() -> None:
    assert _read(_Client({KEY: b""})) == b""


def test_a_missing_key_is_object_not_found() -> None:
    with pytest.raises(ObjectNotFound):
        _read(_Client({}))


def test_a_read_cut_off_midway_still_releases_the_connection() -> None:
    client = _Client({KEY: ConnectionResetError("connection dropped")})

    with pytest.raises(ConnectionResetError):
        _read(client)
    assert client.responses[0].released


# NoSuchBucket is a wrong setting, not a missing object: skipping it would
# skip every object.
@pytest.mark.parametrize("code", ["AccessDenied", "NoSuchBucket"])
def test_any_other_storage_error_propagates(code: str) -> None:
    client = _Client({KEY: b"WEBVTT"}, error=_s3_error(code))

    with pytest.raises(S3Error):
        _read(client)


@pytest.mark.parametrize(
    ("url", "expected"),
    [
        ("http://localhost:9000", ("localhost:9000", False)),
        ("https://minio.example.com", ("minio.example.com", True)),
    ],
)
def test_the_endpoint_url_is_split_the_way_the_sdk_wants_it(
    url: str, expected: tuple[str, bool]
) -> None:
    assert minio_endpoint(url) == expected


def test_an_endpoint_without_a_scheme_is_rejected() -> None:
    # Bridge's setting is a URL; "localhost:9000" would otherwise parse with
    # "localhost" as the scheme and fail later, far from the cause.
    with pytest.raises(ValueError):
        minio_endpoint("localhost:9000")
