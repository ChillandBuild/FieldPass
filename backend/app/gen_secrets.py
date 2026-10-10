"""Print fresh secrets for backend/.env.

Usage: python -m app.gen_secrets
JWT_SECRET and PHOTO_ENCRYPTION_KEY are required. The two password lines are suggestions for
SEED_PASSWORD (student accounts) and FACULTY_SEED_PASSWORD (faculty account): different random values.
"""
from __future__ import annotations

import secrets

from cryptography.fernet import Fernet

PASSWORD_BYTES = 12


def main() -> None:
    print(f"JWT_SECRET={secrets.token_urlsafe(48)}")
    print(f"PHOTO_ENCRYPTION_KEY={Fernet.generate_key().decode()}")
    print(f"SEED_PASSWORD={secrets.token_urlsafe(PASSWORD_BYTES)}")
    print(f"FACULTY_SEED_PASSWORD={secrets.token_urlsafe(PASSWORD_BYTES)}")


if __name__ == "__main__":
    main()
