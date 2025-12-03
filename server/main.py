import base64
import hashlib
import hmac
import os
import sqlite3
import time
from pathlib import Path
from typing import List, Optional

from fastapi import Depends, FastAPI, Header, HTTPException, status
from pydantic import BaseModel, EmailStr

DATABASE = Path(__file__).parent / "travelmate.db"
SECRET_KEY = os.environ.get("TRAVELMATE_SECRET", "change-me")
TOKEN_TTL_SECONDS = 60 * 60 * 24


class RegisterRequest(BaseModel):
    email: EmailStr
    password: str
    name: str


class LoginRequest(BaseModel):
    email: EmailStr
    password: str


class TripPayload(BaseModel):
    id: int
    title: str
    destination: str
    startDate: str
    endDate: str
    notes: str
    weatherTemp: Optional[str] = None
    weatherDescription: Optional[str] = None


class TripSyncRequest(BaseModel):
    trips: List[TripPayload]


app = FastAPI(title="TravelMate Companion API", version="1.0.0")


def get_db():
    DATABASE.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(DATABASE)
    conn.row_factory = sqlite3.Row
    yield conn
    conn.commit()
    conn.close()


def init_db() -> None:
    with sqlite3.connect(DATABASE) as conn:
        cur = conn.cursor()
        cur.execute(
            """
            CREATE TABLE IF NOT EXISTS users (
                email TEXT PRIMARY KEY,
                password TEXT NOT NULL,
                salt TEXT NOT NULL,
                name TEXT NOT NULL,
                role TEXT NOT NULL DEFAULT 'user'
            );
            """
        )
        cur.execute(
            """
            CREATE TABLE IF NOT EXISTS trips (
                id INTEGER PRIMARY KEY,
                title TEXT NOT NULL,
                destination TEXT NOT NULL,
                startDate TEXT NOT NULL,
                endDate TEXT NOT NULL,
                notes TEXT NOT NULL,
                ownerEmail TEXT NOT NULL,
                weatherTemp TEXT,
                weatherDescription TEXT,
                FOREIGN KEY(ownerEmail) REFERENCES users(email)
            );
            """
        )
        conn.commit()


def hash_password(password: str, salt: Optional[str] = None) -> tuple[str, str]:
    salt = salt or os.urandom(16).hex()
    digest = hashlib.sha256(f"{salt}{password}".encode()).hexdigest()
    return digest, salt


def create_token(email: str) -> str:
    issued = int(time.time())
    payload = f"{email}:{issued}"
    signature = hmac.new(SECRET_KEY.encode(), payload.encode(), hashlib.sha256).hexdigest()
    return base64.urlsafe_b64encode(f"{payload}:{signature}".encode()).decode()


def validate_token(token: str) -> str:
    try:
        raw = base64.urlsafe_b64decode(token).decode()
        email, issued, signature = raw.split(":")
        expected = hmac.new(SECRET_KEY.encode(), f"{email}:{issued}".encode(), hashlib.sha256).hexdigest()
        if not hmac.compare_digest(signature, expected):
            raise ValueError("Invalid signature")
        if int(time.time()) - int(issued) > TOKEN_TTL_SECONDS:
            raise ValueError("Token expired")
        return email
    except Exception as exc:  # noqa: BLE001
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail=str(exc)) from exc


def require_auth(authorization: str = Header(..., alias="Authorization")) -> str:
    if not authorization.startswith("Bearer "):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Missing bearer token")
    token = authorization.split(" ", 1)[1]
    return validate_token(token)


@app.on_event("startup")
def on_startup():
    init_db()


@app.get("/")
def root():
    return {"status": "ok", "service": "TravelMate API"}


@app.post("/auth/register")
def register(payload: RegisterRequest, conn=Depends(get_db)):
    cur = conn.cursor()
    cur.execute("SELECT email FROM users WHERE email = ?", (payload.email.lower(),))
    if cur.fetchone():
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="User already exists")

    hashed, salt = hash_password(payload.password)
    cur.execute(
        "INSERT INTO users (email, password, salt, name) VALUES (?, ?, ?, ?)",
        (payload.email.lower(), hashed, salt, payload.name),
    )
    token = create_token(payload.email.lower())
    return {"token": token, "role": "user"}


@app.post("/auth/login")
def login(payload: LoginRequest, conn=Depends(get_db)):
    cur = conn.cursor()
    cur.execute("SELECT email, password, salt, role FROM users WHERE email = ?", (payload.email.lower(),))
    row = cur.fetchone()
    if not row:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Account not found")

    hashed, _ = hash_password(payload.password, row["salt"])
    if hashed != row["password"]:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid credentials")

    token = create_token(row["email"])
    return {"token": token, "role": row["role"]}


@app.get("/trips")
def list_trips(user_email: str = Depends(require_auth), conn=Depends(get_db)):
    cur = conn.cursor()
    cur.execute("SELECT * FROM trips WHERE ownerEmail = ?", (user_email,))
    rows = [dict(row) for row in cur.fetchall()]
    return {"trips": rows}


@app.post("/trips/sync")
def sync_trips(payload: TripSyncRequest, user_email: str = Depends(require_auth), conn=Depends(get_db)):
    cur = conn.cursor()
    for trip in payload.trips:
        cur.execute(
            """
            INSERT OR REPLACE INTO trips(
                id, title, destination, startDate, endDate, notes, ownerEmail, weatherTemp, weatherDescription
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                trip.id,
                trip.title,
                trip.destination,
                trip.startDate,
                trip.endDate,
                trip.notes,
                user_email,
                trip.weatherTemp,
                trip.weatherDescription,
            ),
        )
    return {"synced": len(payload.trips)}
