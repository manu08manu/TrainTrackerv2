# TrainTracker Data API

A FastAPI backend that downloads, processes, and serves Network Rail CORPUS and National Rail Knowledgebase station data. Mobile clients query this API instead of downloading large blobs themselves.

## Quick start

```bash
cd backend
pip install -r requirements.txt

# Set credentials (get these from your Rail Data Marketplace subscriptions)
export CORPUS_DOWNLOAD_URL="https://..."
export KB_STATIONS_URL="https://..."
export KB_STATIONS_KEY="your-consumer-key"

uvicorn main:app --reload
```

The server will:
1. Create a SQLite database (`traintracker.db`)
2. Download and ingest CORPUS + KB Stations data on startup
3. Schedule a daily refresh at 04:00 UTC (configurable via `REFRESH_HOUR`)
4. Serve the API on `http://localhost:8000`

Swagger UI is available at `http://localhost:8000/docs`.

## Environment variables

| Variable | Required | Description |
|---|---|---|
| `CORPUS_DOWNLOAD_URL` | Yes | URL for the CORPUS CSV/ZIP from your RDM subscription |
| `KB_STATIONS_URL` | Yes | URL for the KB Stations JSON feed |
| `KB_STATIONS_KEY` | Yes | Consumer key (`x-apikey`) for KB Stations |
| `DATABASE_URL` | No | SQLAlchemy DB URL (default: `sqlite:///./traintracker.db`) |
| `REFRESH_HOUR` | No | Hour (0-23 UTC) for daily refresh (default: `4`) |

## API endpoints

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/v1/corpus/all` | Bulk download all CORPUS entries (for mobile startup) |
| `GET` | `/api/v1/corpus?tiplocs=X,Y` | Batch lookup by TIPLOC codes |
| `GET` | `/api/v1/corpus?stanoxes=X,Y` | Batch lookup by STANOX codes |
| `GET` | `/api/v1/corpus?crs=PAD` | Lookup all TIPLOCs for a CRS code |
| `GET` | `/api/v1/stations/{crs}` | Get station info by CRS code |
| `GET` | `/api/v1/stations?q=name` | Search stations by name |
| `POST` | `/api/v1/refresh` | Manually trigger data refresh |
| `GET` | `/api/v1/health` | Health check with entry counts |

## Docker

```bash
docker build -t traintracker-api .
docker run -p 8000:8000 \
  -e CORPUS_DOWNLOAD_URL="..." \
  -e KB_STATIONS_URL="..." \
  -e KB_STATIONS_KEY="..." \
  traintracker-api
```
