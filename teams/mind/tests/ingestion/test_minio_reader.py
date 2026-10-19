from typing import Any, cast

import pytest
from minio.error import S3Error

from mind.ingestion.minio_reader import MinioObjectReader, minio_endpoint
from mind.ingestion.raw_object_event_handler import ObjectNotFound

BUCKET = "bridge-raw"
KEY = "ZOOM/550e8400-e29b-41d4-a716-446655440000/2026-10-18/meeting.vtt"


def _s3_error(code: str) -> S3Error:
    return S3Error(cast(Any, None), code, f"{code} from the fake", KEY, "request", "host")


class _Response:
    def __init__(self, data: bytes | Exception) -> None:
        self.data = data
        self.released = False

    def read(self) -> bytes:
        if isinstance(self.data, Exception):
            raise self.data
        return self.data

    def close(self) -> None:
        pass

    def release_conn(self) -> None:
        self.released = True


class _Client:
    """Stands in for minio.Minio: only get_object is used."""

    def __init__(self, objects: dict[str, bytes | Exception], error: S3Error | None = None) -> None:
        self.objects, self.error = objects, error
        self.asked: list[tuple[str, str]] = []
        self.responses: list[_Response] = []

    def get_object(self, bucket_name: str, object_name: str) -> _Response:
        self.asked.append((bucket_name, object_name))
        if self.error is not None:
            raise self.error
        if object_name not in self.objects:
            raise _s3_error("NoSuchKey")
        self.responses.append(_Response(self.objects[object_name]))
        return self.responses[-1]


def test_an_object_is_read_from_the_bucket_and_its_connection_released() -> None:
    client = _Client({KEY: b"WEBVTT"})

    assert MinioObjectReader(client, BUCKET)(KEY) == b"WEBVTT"
    assert client.asked == [(BUCKET, KEY)]
    assert client.responses[0].released


def test_a_missing_key_is_object_not_found() -> None:
    with pytest.raises(ObjectNotFound):
        MinioObjectReader(_Client({}), BUCKET)(KEY)


def test_a_read_cut_off_midway_still_releases_the_connection() -> None:
    client = _Client({KEY: ConnectionResetError("connection dropped")})

    with pytest.raises(ConnectionResetError):
        MinioObjectReader(client, BUCKET)(KEY)
    assert client.responses[0].released


# NoSuchBucket is a wrong setting, not a missing object: skipping it would
# skip every object.
@pytest.mark.parametrize("code", ["AccessDenied", "NoSuchBucket"])
def test_any_other_storage_error_propagates(code: str) -> None:
    client = _Client({KEY: b"WEBVTT"}, error=_s3_error(code))

    with pytest.raises(S3Error):
        MinioObjectReader(client, BUCKET)(KEY)


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
