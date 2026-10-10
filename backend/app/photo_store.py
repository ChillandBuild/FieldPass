"""Fernet-encrypted storage for enrollment photos and embeddings.

Only enrollment data touches disk. Attendance selfies never do.
"""
from __future__ import annotations

import os
import urllib.error
import urllib.request
import uuid
from pathlib import Path

from cryptography.fernet import Fernet, InvalidToken


class SupabaseBucket:
    """Minimal Supabase Storage client (REST, service key). Objects are already Fernet-encrypted."""

    def __init__(self, url: str, service_key: str, bucket: str) -> None:
        self._base = f"{url}/storage/v1/object"
        self._bucket = bucket
        self._headers = {"Authorization": f"Bearer {service_key}", "apikey": service_key}

    def _request(self, method: str, path: str, data: bytes | None = None, content_type: str | None = None) -> bytes:
        headers = dict(self._headers)
        if content_type:
            headers["Content-Type"] = content_type
        req = urllib.request.Request(f"{self._base}/{path}", data=data, method=method, headers=headers)
        with urllib.request.urlopen(req, timeout=20) as resp:
            return resp.read()

    def put(self, name: str, data: bytes) -> None:
        self._request("POST", f"{self._bucket}/{name}", data, "application/octet-stream")

    def get(self, name: str) -> bytes:
        try:
            return self._request("GET", f"authenticated/{self._bucket}/{name}")
        except urllib.error.HTTPError as exc:
            if exc.code in (400, 404):
                raise FileNotFoundError(name) from exc
            raise

    def delete(self, name: str) -> None:
        try:
            self._request("DELETE", f"{self._bucket}/{name}")
        except urllib.error.HTTPError as exc:
            if exc.code not in (400, 404):
                raise


class PhotoStore:
    def __init__(self, directory: Path, key: str, remote: SupabaseBucket | None = None) -> None:
        self._dir = directory
        self._remote = remote
        self._fernet = Fernet(key.encode())
        if remote is None:
            directory.mkdir(parents=True, exist_ok=True)

    @classmethod
    def from_settings(cls, settings) -> "PhotoStore":  # noqa: ANN001  (Settings)
        remote = None
        if settings.supabase_url and settings.supabase_service_key:
            remote = SupabaseBucket(settings.supabase_url, settings.supabase_service_key, settings.photo_bucket)
        return cls(settings.photo_dir, settings.photo_encryption_key, remote)

    def encrypt(self, data: bytes) -> bytes:
        return self._fernet.encrypt(data)

    def decrypt(self, token: bytes) -> bytes:
        try:
            return self._fernet.decrypt(token)
        except InvalidToken as exc:
            raise ValueError("stored data cannot be decrypted with the configured key") from exc

    def save(self, jpeg: bytes) -> str:
        """Write an encrypted photo under a random name; return the file name."""
        name = f"{uuid.uuid4().hex}.enc"
        if self._remote is not None:
            self._remote.put(name, self.encrypt(jpeg))
            return name
        path = self._dir / name
        tmp = path.with_suffix(".tmp")
        tmp.write_bytes(self.encrypt(jpeg))
        os.replace(tmp, path)
        return name

    def load(self, name: str) -> bytes:
        if Path(name).name != name:
            raise ValueError("invalid photo name")
        if self._remote is not None:
            return self.decrypt(self._remote.get(name))
        return self.decrypt((self._dir / name).read_bytes())

    def delete(self, name: str) -> None:
        if Path(name).name != name:
            return
        if self._remote is not None:
            self._remote.delete(name)
            return
        (self._dir / name).unlink(missing_ok=True)
