from __future__ import annotations

import base64
from datetime import datetime, timezone

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

from app.device_keys import canonical_message, is_valid_public_key, location_fingerprint, verify_signature

VECTOR = (
    "fieldpass-attempt-v1\natt_1\nn0nce\n"
    "559aead08264d5795d3909718cdd05abd49572e84fe55590eef31a88a08fdffd\n"
    "df7e70e5021544f4834bbee64a9e3789febc4be81470df629cad6ddb03320a5c\n"
    "12.971600\n77.594600\n10.0\n1791624665\n0\n1\n0"
)


def _key():
    private = ec.generate_private_key(ec.SECP256R1())
    der = private.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo
    )
    return private, base64.b64encode(der).decode()


def _vector_message() -> bytes:
    return canonical_message(
        attempt_id="att_1", nonce="n0nce", selfie=b"A", turn_photo=b"B", lat=12.9716, lng=77.5946,
        accuracy_m=10, captured_at=datetime(2026, 10, 10, 9, 31, 5, tzinfo=timezone.utc),
        is_mock=False, rooted=True, emulator=False,
    )


def test_canonical_message_matches_shared_vector():
    assert _vector_message() == VECTOR.encode()


def test_valid_signature_verifies():
    private, public_b64 = _key()
    signature = base64.b64encode(private.sign(_vector_message(), ec.ECDSA(hashes.SHA256()))).decode()
    assert verify_signature(public_b64, _vector_message(), signature) is True


def test_signature_over_other_message_fails():
    private, public_b64 = _key()
    signature = base64.b64encode(private.sign(b"something else", ec.ECDSA(hashes.SHA256()))).decode()
    assert verify_signature(public_b64, _vector_message(), signature) is False


def test_signature_from_other_key_fails():
    other, _ = _key()
    _, public_b64 = _key()
    signature = base64.b64encode(other.sign(_vector_message(), ec.ECDSA(hashes.SHA256()))).decode()
    assert verify_signature(public_b64, _vector_message(), signature) is False


def test_garbage_signature_and_key_never_raise():
    _, public_b64 = _key()
    assert verify_signature(public_b64, b"m", "not-base64!!") is False
    assert verify_signature("not-a-key", b"m", "AAAA") is False


def test_public_key_validation():
    _, public_b64 = _key()
    rsa_like = base64.b64encode(b"\x30\x03\x02\x01\x00").decode()
    assert is_valid_public_key(public_b64) is True
    assert is_valid_public_key(rsa_like) is False
    assert is_valid_public_key("") is False
    p384 = ec.generate_private_key(ec.SECP384R1()).public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo
    )
    assert is_valid_public_key(base64.b64encode(p384).decode()) is False


def test_location_fingerprint_is_stable_and_short():
    assert location_fingerprint(12.9716, 77.5946) == "0be44efee92b0b0b"
    assert location_fingerprint(12.9716001, 77.5946) == "0be44efee92b0b0b"  # same at 6 decimals
    assert location_fingerprint(12.971601, 77.5946) != "0be44efee92b0b0b"
