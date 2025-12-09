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

# =====================================================
# MODELE — actualizate cu ownerEmail (NU ownerId!)
# =====================================================

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
    ownerEmail: EmailStr                     # 🔥 IMPORTANT
    weatherTemp: Optional[str] = None
    weatherDescription: Optional[str] = None


class TripSyncRequest(BaseModel):
    trips: List[TripPayload]


app = FastAPI(title="TravelMate Companion API", version="3.0.0")


# =====================================================
# DB
# =====================================================

def get_db():
    DATABASE.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(DATABASE)
    conn.row_factory = sqlite3.Row
    yield conn
    conn.commit()
    conn.close()


def init_db():
    with sqlite3.connect(DATABASE) as conn:
        cur = conn.cursor()

        cur.execute("""
            CREATE TABLE IF NOT EXISTS users (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                email TEXT UNIQUE NOT NULL,
                password TEXT NOT NULL,
                salt TEXT NOT NULL,
                name TEXT NOT NULL,
                role TEXT NOT NULL DEFAULT 'user',
                isBlocked INTEGER NOT NULL DEFAULT 0
            );
        """)

        cur.execute("""
            CREATE TABLE IF NOT EXISTS trips (
                id INTEGER PRIMARY KEY,
                title TEXT NOT NULL,
                destination TEXT NOT NULL,
                startDate TEXT NOT NULL,
                endDate TEXT NOT NULL,
                notes TEXT NOT NULL,
                ownerId INTEGER NOT NULL,
                weatherTemp TEXT,
                weatherDescription TEXT,
                FOREIGN KEY(ownerId) REFERENCES users(id)
            );
        """)


# =====================================================
# TOKEN + SECURITY
# =====================================================

def hash_password(password: str, salt: Optional[str] = None):
    salt = salt or os.urandom(16).hex()
    digest = hashlib.sha256(f"{salt}{password}".encode()).hexdigest()
    return digest, salt


def create_token(email: str):
    issued = int(time.time())
    payload = f"{email}:{issued}"
    signature = hmac.new(SECRET_KEY.encode(), payload.encode(), hashlib.sha256).hexdigest()
    return base64.urlsafe_b64encode(f"{payload}:{signature}".encode()).decode()


def validate_token(token: str):
    try:
        raw = base64.urlsafe_b64decode(token).decode()
        email, issued, signature = raw.split(":")
        expected = hmac.new(SECRET_KEY.encode(), f"{email}:{issued}".encode(), hashlib.sha256).hexdigest()

        if not hmac.compare_digest(signature, expected):
            raise ValueError("Invalid token signature")

        if int(time.time()) - int(issued) > TOKEN_TTL_SECONDS:
            raise ValueError("Token expired")

        return email
    except Exception:
        raise HTTPException(401, "Invalid or expired token")


def require_auth(authorization: str = Header(...)):
    if not authorization.startswith("Bearer "):
        raise HTTPException(401, "Missing bearer token")
    return validate_token(authorization.split()[1])


@app.on_event("startup")
def on_startup():
    init_db()


# =====================================================
# ROUTES
# =====================================================

@app.get("/ping")
def ping():
    return {"status": "ok", "time": int(time.time())}


# REGISTER
@app.post("/auth/register")
def register(payload: RegisterRequest, conn=Depends(get_db)):
    cur = conn.cursor()

    cur.execute("SELECT id FROM users WHERE email = ?", (payload.email.lower(),))
    if cur.fetchone():
        raise HTTPException(409, "User already exists")

    hashed, salt = hash_password(payload.password)

    cur.execute("""
        INSERT INTO users (email, password, salt, name)
        VALUES (?, ?, ?, ?)
    """, (payload.email.lower(), hashed, salt, payload.name))

    token = create_token(payload.email.lower())

    return {"token": token, "role": "user"}


# LOGIN
@app.post("/auth/login")
def login(payload: LoginRequest, conn=Depends(get_db)):
    cur = conn.cursor()
    cur.execute("SELECT * FROM users WHERE email = ?", (payload.email.lower(),))
    row = cur.fetchone()

    if not row:
        raise HTTPException(404, "Account not found")

    hashed, _ = hash_password(payload.password, row["salt"])
    if hashed != row["password"]:
        raise HTTPException(401, "Invalid credentials")

    if row["isBlocked"] == 1:
        raise HTTPException(403, "User is blocked")

    token = create_token(row["email"])

    return {
        "token": token,
        "role": row["role"]
    }


# SYNC TRIPS
@app.post("/trips/sync")
def sync_trips(payload: TripSyncRequest, user_email: str = Depends(require_auth), conn=Depends(get_db)):
    cur = conn.cursor()

    # find the authenticated user's ID
    cur.execute("SELECT id FROM users WHERE email = ?", (user_email,))
    user_row = cur.fetchone()

    if not user_row:
        raise HTTPException(404, "User not found")

    authenticated_user_id = user_row["id"]

    for trip in payload.trips:

        # Convert incoming ownerEmail → correct userId
        cur.execute("SELECT id FROM users WHERE email = ?", (trip.ownerEmail.lower(),))
        owner_row = cur.fetchone()

        if not owner_row:
            raise HTTPException(404, f"Owner email not found: {trip.ownerEmail}")

        owner_id = owner_row["id"]

        cur.execute("""
            INSERT OR REPLACE INTO trips (
                id, title, destination, startDate, endDate, notes, ownerId, weatherTemp, weatherDescription
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, (
            trip.id,
            trip.title,
            trip.destination,
            trip.startDate,
            trip.endDate,
            trip.notes,
            owner_id,
            trip.weatherTemp,
            trip.weatherDescription
        ))

    return {"synced": len(payload.trips)}


# GET TRIPS FOR LOGGED USER
@app.get("/trips")
def list_trips(user_email: str = Depends(require_auth), conn=Depends(get_db)):
    cur = conn.cursor()

    cur.execute("SELECT id FROM users WHERE email = ?", (user_email,))
    row = cur.fetchone()

    if not row:
        raise HTTPException(404, "User not found")

    user_id = row["id"]

    cur.execute("SELECT * FROM trips WHERE ownerId = ?", (user_id,))
    trips = [dict(t) for t in cur.fetchall()]

    # Convert to Android-compatible format
    for t in trips:
        t["ownerEmail"] = user_email   # 🔥 important — Android expects ownerEmail

    return {"trips": trips}
