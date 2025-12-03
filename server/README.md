# TravelMate Companion API (Python/FastAPI)

A lightweight REST API that mirrors the mobile app's data model and keeps trips synchronized across devices.

## Running locally

```bash
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
uvicorn main:app --reload --host 0.0.0.0 --port 8000
```

When testing from the Android emulator the API is reachable at `http://10.0.2.2:8000`.
