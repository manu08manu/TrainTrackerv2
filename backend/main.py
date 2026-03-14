"""FastAPI application — serves CORPUS and KB Stations data over HTTP."""

from __future__ import annotations

import logging
from contextlib import asynccontextmanager
from typing import Any

from apscheduler.schedulers.background import BackgroundScheduler
from fastapi import Depends, FastAPI, HTTPException, Query
from pydantic import BaseModel
from sqlalchemy.orm import Session

import config
from database import CorpusEntry, Station, create_tables, get_db
from fetchers import refresh_all

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s  %(name)-22s  %(levelname)-5s  %(message)s",
)
logger = logging.getLogger(__name__)

# ── Scheduler ──────────────────────────────────────────────────────────────────

scheduler = BackgroundScheduler()


def _scheduled_refresh() -> None:
    logger.info("Scheduled daily refresh starting …")
    results = refresh_all()
    logger.info("Scheduled daily refresh finished: %s", results)


# ── Lifespan ───────────────────────────────────────────────────────────────────


@asynccontextmanager
async def lifespan(app: FastAPI):
    create_tables()
    # Run an initial refresh on startup so the DB is populated
    logger.info("Running initial data refresh …")
    results = refresh_all()
    logger.info("Initial refresh results: %s", results)

    scheduler.add_job(
        _scheduled_refresh,
        "cron",
        hour=config.REFRESH_HOUR,
        minute=0,
        id="daily_refresh",
        replace_existing=True,
    )
    scheduler.start()
    logger.info("Scheduler started — daily refresh at %02d:00 UTC", config.REFRESH_HOUR)
    yield
    scheduler.shutdown(wait=False)


app = FastAPI(
    title="TrainTracker Data API",
    version="1.0.0",
    lifespan=lifespan,
)


def create_test_app() -> FastAPI:
    """Create a bare FastAPI app with all routes but no lifespan (for testing)."""
    test_app = FastAPI(title="TrainTracker Data API (test)")
    # Copy all routes from the production app
    test_app.router.routes = app.router.routes
    return test_app


# ── Response schemas ───────────────────────────────────────────────────────────


class CorpusResponse(BaseModel):
    tiploc: str
    crs: str
    stanox: str | None = None


class StationResponse(BaseModel):
    crs: str
    name: str
    address: str
    telephone: str
    staffing_note: str
    ticket_office_hours: str
    sstm_availability: str
    step_free_access: str
    assistance_avail: str
    wifi: str
    toilets: str
    waiting_room: str
    cctv: str
    taxi: str
    bus_interchange: str
    car_parking: str


# ── Endpoints ──────────────────────────────────────────────────────────────────


@app.get("/api/v1/corpus/aliases")
def get_crs_aliases() -> dict[str, Any]:
    """Return the CRS alias map. Clients can use this to stay in sync."""
    return {"aliases": config.CRS_ALIASES}


@app.get("/api/v1/corpus/all", response_model=list[CorpusResponse])
def get_all_corpus(db: Session = Depends(get_db)) -> Any:
    """Return every CORPUS entry — used by mobile clients to bulk-load the mapping.

    CRS codes are normalised through the alias map so clients receive
    canonical codes (e.g. SPL→STP, ABX→ABW).
    """
    rows = db.query(CorpusEntry).all()
    return [
        CorpusResponse(
            tiploc=r.tiploc,
            crs=config.canonical_crs(r.crs),
            stanox=r.stanox,
        )
        for r in rows
    ]


@app.get("/api/v1/corpus", response_model=list[CorpusResponse])
def lookup_corpus(
    tiplocs: str | None = Query(None, description="Comma-separated TIPLOC codes"),
    stanoxes: str | None = Query(None, description="Comma-separated STANOX codes"),
    crs: str | None = Query(None, description="Single CRS code — returns all matching TIPLOCs"),
    db: Session = Depends(get_db),
) -> Any:
    """Batch lookup of CORPUS location mappings.

    Provide **one** of the query parameters. Returns matching rows.
    """
    if tiplocs:
        codes = [c.strip().upper() for c in tiplocs.split(",") if c.strip()][:200]
        rows = db.query(CorpusEntry).filter(CorpusEntry.tiploc.in_(codes)).all()
    elif stanoxes:
        codes = [c.strip() for c in stanoxes.split(",") if c.strip()][:200]
        rows = db.query(CorpusEntry).filter(CorpusEntry.stanox.in_(codes)).all()
    elif crs:
        # Include alias codes so a query for "STP" also finds TIPLOCs stored as "SPL"
        target = crs.strip().upper()
        codes_to_search = [target] + config.aliases_for(target)
        # Also search for the canonical form if an alias was provided
        canonical = config.canonical_crs(target)
        if canonical != target:
            codes_to_search.append(canonical)
            codes_to_search += config.aliases_for(canonical)
        codes_to_search = list(set(codes_to_search))
        rows = db.query(CorpusEntry).filter(CorpusEntry.crs.in_(codes_to_search)).all()
    else:
        raise HTTPException(400, "Provide at least one of: tiplocs, stanoxes, crs")
    return rows


@app.get("/api/v1/stations/{crs}", response_model=StationResponse)
def get_station(crs: str, db: Session = Depends(get_db)) -> Any:
    """Retrieve station information by 3-letter CRS code (alias-aware)."""
    canonical = config.canonical_crs(crs.strip())
    station = db.query(Station).filter(Station.crs == canonical).first()
    if station is None:
        raise HTTPException(404, f"Station '{crs.upper()}' not found")
    return station


@app.get("/api/v1/stations", response_model=list[StationResponse])
def list_stations(
    q: str | None = Query(None, description="Search by name (partial match)"),
    db: Session = Depends(get_db),
) -> Any:
    """List stations, optionally filtered by name substring."""
    query = db.query(Station)
    if q:
        query = query.filter(Station.name.ilike(f"%{q}%"))
    return query.order_by(Station.name).limit(100).all()


@app.post("/api/v1/refresh")
def trigger_refresh() -> dict[str, Any]:
    """Manually trigger a data refresh (for admin use)."""
    results = refresh_all()
    return {"status": "ok", "counts": results}


@app.get("/api/v1/health")
def health(db: Session = Depends(get_db)) -> dict[str, Any]:
    corpus_count = db.query(CorpusEntry).count()
    station_count = db.query(Station).count()
    return {
        "status": "ok",
        "corpus_entries": corpus_count,
        "station_entries": station_count,
    }
