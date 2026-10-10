"""Fernet-encrypted storage for enrollment photos and embeddings.

Only enrollment data touches disk. Attendance selfies never do.
"""
from __future__ import annotations

import os
import uuid
from pathlib import Path

from cryptography.fernet import Fernet, InvalidToken


class PhotoStore:
    def __init__(self, directory: Path, key: str) -> None:
        self._dir = directory
        self._fernet = Fernet(key.encode())
        directory.mkdir(parents=True, exist_ok=True)

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
        path = self._dir / name
        tmp = path.with_suffix(".tmp")
        tmp.write_bytes(self.encrypt(jpeg))
        os.replace(tmp, path)
        return name

    def load(self, name: str) -> bytes:
        if Path(name).name != name:
            raise ValueError("invalid photo name")
        return self.decrypt((self._dir / name).read_bytes())

    def delete(self, name: str) -> None:
        if Path(name).name != name:
            return
        (self._dir / name).unlink(missing_ok=True)
