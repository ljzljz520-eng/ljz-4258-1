from __future__ import annotations

import base64
import hashlib
import hmac
from datetime import datetime, timedelta, timezone

from fastapi import Depends, HTTPException, status
from fastapi.security import OAuth2PasswordBearer
from jose import JWTError, jwt
from sqlalchemy.orm import Session

from .config import get_settings
from .db import get_db
from .models import User, UserRole

oauth2_scheme = OAuth2PasswordBearer(tokenUrl="/api/auth/login")
settings = get_settings()


def verify_password(password: str, stored: str) -> bool:
    # Format: pbkdf2_sha256$iterations$base64_salt$base64_hash
    try:
        scheme, iterations, salt_b64, hash_b64 = stored.split("$", 3)
        if scheme != "pbkdf2_sha256":
            return False
        salt = base64.b64decode(salt_b64)
        expected = base64.b64decode(hash_b64)
        actual = hashlib.pbkdf2_hmac("sha256", password.encode(), salt, int(iterations))
        return hmac.compare_digest(actual, expected)
    except Exception:
        return False


def create_access_token(user: User) -> str:
    now = datetime.now(timezone.utc)
    payload = {
        "sub": str(user.id),
        "preferred_username": user.username,
        "role": user.role.value,
        "iat": int(now.timestamp()),
        "exp": now + timedelta(minutes=settings.access_token_expire_minutes),
    }
    return jwt.encode(payload, settings.jwt_secret, algorithm=settings.jwt_algorithm)


def current_user(token: str = Depends(oauth2_scheme), db: Session = Depends(get_db)) -> User:
    credentials_exc = HTTPException(
        status_code=status.HTTP_401_UNAUTHORIZED,
        detail="Could not validate credentials",
        headers={"WWW-Authenticate": "Bearer"},
    )
    try:
        payload = jwt.decode(token, settings.jwt_secret, algorithms=[settings.jwt_algorithm])
        user_id = payload.get("sub")
        if not user_id:
            raise credentials_exc
    except JWTError as exc:
        raise credentials_exc from exc
    user = db.get(User, user_id)
    if not user or not user.is_active:
        raise credentials_exc
    return user


def require_roles(*roles: UserRole):
    def checker(user: User = Depends(current_user)) -> User:
        if user.role not in roles and user.role != UserRole.ADMIN:
            raise HTTPException(status_code=403, detail="Insufficient role")
        return user
    return checker
