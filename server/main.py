import base64
import hashlib
import hmac
import os
import sqlite3
import time
from pathlib import Path
from typing import List, Optional

from fastapi import Depends, FastAPI, Header, HTTPException, Request
from pydantic import BaseModel, EmailStr

# =====================================================
# CONFIG
# =====================================================

DATABASE = Path(__file__).parent / "travelmate.db"
SECRET_KEY = os.environ.get("TRAVELMATE_SECRET", "change-me")
TOKEN_TTL_SECONDS = 60 * 60 * 24

app = FastAPI(title="TravelMate API", version="5.0.0")

# =====================================================
# MODELS
# =====================================================

class RegisterRequest(BaseModel):
    email: EmailStr
    password: str
    name: str


class LoginRequest(BaseModel):
    email: EmailStr
    password: str


class GoogleLoginRequest(BaseModel):
    email: EmailStr
    name: str


# TOTUL OPTIONAL → niciodată 422
class TripPayload(BaseModel):
    id: Optional[int] = 0
    title: Optional[str] = ""
    destination: Optional[str] = ""
    startDate: Optional[str] = ""
    endDate: Optional[str] = ""
    notes: Optional[str] = ""
    weatherTemp: Optional[str] = None
    weatherDescription: Optional[str] = None


class TripSyncRequest(BaseModel):
    trips: List[TripPayload] = []

# =====================================================
# DB UTILS
# =====================================================

def get_db():
    DATABASE.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(
        DATABASE,
        check_same_thread=False  # ← FIX IMPORTANT!!
    )
    conn.row_factory = sqlite3.Row
    try:
        yield conn
        conn.commit()
    finally:
        conn.close()


def init_db():
    with sqlite3.connect(DATABASE) as conn:
        cur = conn.cursor()

        cur.execute("""
            CREATE TABLE IF NOT EXISTS users (
                email TEXT PRIMARY KEY,
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
                ownerEmail TEXT NOT NULL,
                weatherTemp TEXT,
                weatherDescription TEXT,
                FOREIGN KEY(ownerEmail) REFERENCES users(email)
            );
        """)


@app.on_event("startup")
def startup():
    init_db()

# =====================================================
# AUTH HELPERS
# =====================================================

def hash_password(password: str, salt: Optional[str] = None):
    salt = salt or os.urandom(16).hex()
    hashed = hashlib.sha256(f"{salt}{password}".encode()).hexdigest()
    return hashed, salt


def create_token(email: str):
    issued = int(time.time())
    payload = f"{email}:{issued}"
    sig = hmac.new(SECRET_KEY.encode(), payload.encode(), hashlib.sha256).hexdigest()
    return base64.urlsafe_b64encode(f"{payload}:{sig}".encode()).decode()


def validate_token(token: str):
    try:
        raw = base64.urlsafe_b64decode(token).decode()
        email, issued, sig = raw.split(":")

        expected = hmac.new(
            SECRET_KEY.encode(),
            f"{email}:{issued}".encode(),
            hashlib.sha256
        ).hexdigest()

        if not hmac.compare_digest(sig, expected):
            raise ValueError("sig mismatch")

        if int(time.time()) - int(issued) > TOKEN_TTL_SECONDS:
            raise ValueError("expired")

        return email

    except Exception:
        raise HTTPException(401, "Invalid or expired token")


# AICI ERA PROBLEMA 422!
def require_auth(
    authorization: str = Header(..., alias="Authorization")
):
    if not authorization.startswith("Bearer "):
        raise HTTPException(401, "Missing Bearer token")

    token = authorization.split()[1]
    return validate_token(token)

# =====================================================
# ROUTES
# =====================================================

@app.get("/ping")
def ping():
    return {"status": "ok", "time": int(time.time())}

# ---------------------------
# REGISTER
# ---------------------------

@app.post("/auth/register")
def register(payload: RegisterRequest, conn=Depends(get_db)):
    cur = conn.cursor()

    cur.execute("SELECT email FROM users WHERE email = ?", (payload.email.lower(),))
    if cur.fetchone():
        raise HTTPException(409, "User already exists")

    hashed, salt = hash_password(payload.password)

    cur.execute("""
        INSERT INTO users (email, password, salt, name)
        VALUES (?, ?, ?, ?)
    """, (payload.email.lower(), hashed, salt, payload.name))

    token = create_token(payload.email.lower())

    return {
        "token": token,
        "role": "user",
        "email": payload.email.lower()
    }

# ---------------------------
# LOGIN
# ---------------------------

@app.post("/auth/login")
def login(payload: LoginRequest, conn=Depends(get_db)):
    cur = conn.cursor()

    cur.execute("SELECT * FROM users WHERE email = ?", (payload.email.lower(),))
    row = cur.fetchone()

    if row is None:
        raise HTTPException(404, "Account not found")

    hash_try, _ = hash_password(payload.password, row["salt"])
    if hash_try != row["password"]:
        raise HTTPException(401, "Invalid credentials")

    if row["isBlocked"] == 1:
        raise HTTPException(403, "User blocked")

    token = create_token(row["email"])

    return {
        "token": token,
        "role": row["role"],
        "email": row["email"]
    }

# ---------------------------
# GOOGLE LOGIN
# ---------------------------

@app.post("/auth/google-login")
def google_login(payload: GoogleLoginRequest, conn=Depends(get_db)):
    cur = conn.cursor()

    cur.execute("SELECT * FROM users WHERE email = ?", (payload.email.lower(),))
    row = cur.fetchone()

    if row is None:
        hashed, salt = hash_password("google-oauth")

        cur.execute("""
            INSERT INTO users (email, password, salt, name)
            VALUES (?, ?, ?, ?)
        """, (payload.email.lower(), hashed, salt, payload.name))

        cur.execute("SELECT * FROM users WHERE email = ?", (payload.email.lower(),))
        row = cur.fetchone()

    token = create_token(row["email"])

    return {
        "token": token,
        "role": row["role"],
        "email": row["email"]
    }

# ---------------------------
# SYNC TRIPS
# ---------------------------

@app.post("/trips/sync")
async def sync_trips(
    request: Request,
    email: str = Depends(require_auth),
    conn=Depends(get_db)
):
    import json

    raw = await request.body()
    try:
        data = json.loads(raw.decode())
    except:
        raise HTTPException(400, "Invalid JSON")

    trips = data.get("trips", [])

    cur = conn.cursor()

    # 🔥 1) Ștergem toate tripurile existente ale acestui user
    cur.execute("DELETE FROM trips WHERE ownerEmail = ?", (email.lower(),))

    # Dacă nu există nimic de inserat → gata
    if not trips:
        return {"synced": 0}

    # 🔥 2) Pregătim inserarea în batch
    batch = []
    for trip in trips:
        batch.append((
            trip.get("id", 0),
            trip.get("title", ""),
            trip.get("destination", ""),
            trip.get("startDate", ""),
            trip.get("endDate", ""),
            trip.get("notes", ""),
            email.lower(),
            trip.get("weatherTemp"),
            trip.get("weatherDescription"),
        ))

    # 🔥 3) Inserăm tot într-o singură operațiune
    cur.executemany("""
        INSERT INTO trips (
            id, title, destination, startDate, endDate,
            notes, ownerEmail, weatherTemp, weatherDescription
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
    """, batch)

    return {"synced": len(trips)}


# ---------------------------
# GET TRIPS
# ---------------------------

@app.get("/trips")
def get_trips(email: str = Depends(require_auth), conn=Depends(get_db)):
    cur = conn.cursor()
    cur.execute("SELECT * FROM trips WHERE ownerEmail = ?", (email.lower(),))
    trips = [dict(t) for t in cur.fetchall()]
    return {"trips": trips}


def require_admin(authorization: str = Header(..., alias="Authorization")):
    email = require_auth(authorization)

    conn = sqlite3.connect(DATABASE, check_same_thread=False)
    conn.row_factory = sqlite3.Row
    cur = conn.cursor()

    cur.execute("SELECT role FROM users WHERE email = ?", (email,))
    row = cur.fetchone()
    conn.close()

    if row is None or row["role"] not in ("admin", "superadmin"):
        raise HTTPException(403, "Admin only")

    return email

@app.get("/admin/users")
def admin_get_users(admin=Depends(require_admin), conn=Depends(get_db)):
    cur = conn.cursor()
    cur.execute("""
        SELECT email, role, isBlocked 
        FROM users
        ORDER BY email
    """)
    users = [dict(u) for u in cur.fetchall()]
    return {"users": users}

from pydantic import BaseModel

class UpdateRoleRequest(BaseModel):
    role: str

@app.patch("/admin/users/{user_email}/role")
def admin_update_role(
    user_email: str,
    payload: UpdateRoleRequest,
    admin = Depends(require_admin),
    conn = Depends(get_db)
):
    if payload.role not in ("user", "admin", "superadmin"):
        raise HTTPException(400, "Invalid role")

    cur = conn.cursor()

    cur.execute("""
        UPDATE users 
        SET role = ?
        WHERE email = ?
    """, (payload.role, user_email.lower()))

    return {"status": "ok", "email": user_email.lower(), "role": payload.role}

class BlockRequest(BaseModel):
    block: bool

@app.patch("/admin/users/{user_email}/block")
def admin_block_user(
    user_email: str,
    payload: BlockRequest,
    admin = Depends(require_admin),
    conn = Depends(get_db)
):
    cur = conn.cursor()

    cur.execute("""
        UPDATE users 
        SET isBlocked = ?
        WHERE email = ?
    """, (1 if payload.block else 0, user_email.lower()))

    return {"status": "ok", "email": user_email.lower(), "blocked": payload.block}
