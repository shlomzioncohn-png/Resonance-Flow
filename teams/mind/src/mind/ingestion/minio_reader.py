"""Reads Bridge's landed objects from MinIO: the `ObjectReader` of #349.

Only a missing key becomes `ObjectNotFound`, the same line Bridge's own
MinioObjectStorageClient draws. Anything else, a wrong bucket included,
propagates: skipping it would skip every object.
"""

from typing import Any, Protocol
from urllib.parse import urlsplit

from minio import Minio
from minio.error import S3Error

from mind.config import ObjectStorageSettings
from mind.ingestion.raw_object_event_handler import ObjectNotFound


class _GetObject(Protocol):
    """The one call this reader makes; `minio.Minio` has it, so do the test fakes."""

    def get_object(self, bucket_name: str, object_name: str) -> Any: ...


class MinioObjectReader:
    def __init__(self, client: _GetObject, bucket: str) -> None:
        self._client = client
        self._bucket = bucket

    def __call__(self, object_key: str) -> bytes:
        # On an error status the SDK reads the body and releases the
        # connection itself before raising: no response, nothing to release.
        try:
            response = self._client.get_object(self._bucket, object_key)
        except S3Error as e:
            if e.code == "NoSuchKey":
                raise ObjectNotFound(object_key) from e
            raise
        # The SDK hands back an open HTTP response: released even when the
        # read fails midway, or the pool runs dry after enough bad reads.
        try:
            return bytes(response.read())
        finally:
            response.close()
            response.release_conn()


def minio_endpoint(url: str) -> tuple[str, bool]:
    """Split Bridge-style `http://localhost:9000` into ("localhost:9000", False):
    the SDK wants host and TLS apart, Bridge's setting is one URL."""

    parts = urlsplit(url)
    if parts.scheme not in ("http", "https") or not parts.netloc:
        raise ValueError(f"object storage endpoint must be an http(s) URL, got {url!r}")
    return parts.netloc, parts.scheme == "https"


def minio_reader(settings: ObjectStorageSettings | None = None) -> MinioObjectReader:
    settings = settings or ObjectStorageSettings()  # type: ignore[call-arg]  # from env
    host, secure = minio_endpoint(settings.endpoint)
    client = Minio(host, settings.access_key, settings.secret_key, secure=secure)
    return MinioObjectReader(client, settings.bucket)
