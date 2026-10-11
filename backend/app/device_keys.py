"""Phone-bound signatures for attendance submits, and the location fingerprint.

The phone holds an EC P-256 key in the Android Keystore and signs a canonical message per submit.
Coordinates are never stored; only a short fingerprint of them at 6 decimals.
"""
from __future__ import annotations

import base64
import binascii
import hashlib
from datetime import datetime

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

MESSAGE_VERSION = "fieldpass-attempt-v1"
FINGERPRINT_HEX_CHARS = 16


def _flag(value: bool) -> str:
    return "1" if value else "0"


def canonical_message(
    *, attempt_id: str, nonce: str, selfie: bytes, turn_photo: bytes, lat: float, lng: float,
    accuracy_m: float, captured_at: datetime, is_mock: bool, rooted: bool, emulator: bool,
) -> bytes:
    lines = [
        MESSAGE_VERSION, attempt_id, nonce,
        hashlib.sha256(selfie).hexdigest(), hashlib.sha256(turn_photo).hexdigest(),
        f"{lat:.6f}", f"{lng:.6f}", f"{accuracy_m:.1f}", str(int(captured_at.timestamp())),
        _flag(is_mock), _flag(rooted), _flag(emulator),
    ]
    return "\n".join(lines).encode("utf-8")


def _load_public_key(b64: str) -> ec.EllipticCurvePublicKey | None:
    try:
        key = serialization.load_der_public_key(base64.b64decode(b64, validate=True))
    except (ValueError, binascii.Error, TypeError):
        return None
    if not isinstance(key, ec.EllipticCurvePublicKey) or not isinstance(key.curve, ec.SECP256R1):
        return None
    return key


def is_valid_public_key(b64: str) -> bool:
    return bool(b64) and _load_public_key(b64) is not None


def verify_signature(public_key_b64: str, message: bytes, signature_b64: str) -> bool:
    key = _load_public_key(public_key_b64)
    if key is None:
        return False
    try:
        signature = base64.b64decode(signature_b64, validate=True)
        key.verify(signature, message, ec.ECDSA(hashes.SHA256()))
    except (InvalidSignature, ValueError, binascii.Error):
        return False
    return True


def location_fingerprint(lat: float, lng: float) -> str:
    digest = hashlib.sha256(f"{lat:.6f},{lng:.6f}".encode("utf-8")).hexdigest()
    return digest[:FINGERPRINT_HEX_CHARS]
