"""Fetchers that download CORPUS and KB Stations data and populate the database."""

from __future__ import annotations

import csv
import io
import logging
import zipfile
from typing import Any

import httpx
from sqlalchemy.orm import Session

import config
from database import CorpusEntry, SessionLocal, Station

logger = logging.getLogger(__name__)

HTTPX_TIMEOUT = httpx.Timeout(connect=30.0, read=60.0, write=30.0, pool=30.0)


# ── CORPUS ─────────────────────────────────────────────────────────────────────


def refresh_corpus() -> int:
    """Download and ingest the CORPUS CSV into the database. Returns row count."""
    url = config.CORPUS_DOWNLOAD_URL
    if not url:
        logger.warning("CORPUS_DOWNLOAD_URL not set — skipping CORPUS refresh")
        return 0

    logger.info("Downloading CORPUS from %s …", url)
    with httpx.Client(timeout=HTTPX_TIMEOUT) as client:
        resp = client.get(url)
        resp.raise_for_status()

    csv_text = _extract_csv(resp)
    rows = _parse_corpus_csv(csv_text)

    db: Session = SessionLocal()
    try:
        db.query(CorpusEntry).delete()
        db.bulk_save_objects(rows)
        db.commit()
        count = len(rows)
        logger.info("CORPUS refresh complete — %d entries", count)
        return count
    finally:
        db.close()


def _extract_csv(resp: httpx.Response) -> str:
    content_type = resp.headers.get("content-type", "")
    if "zip" in content_type or resp.url.path.endswith(".zip"):
        with zipfile.ZipFile(io.BytesIO(resp.content)) as zf:
            name = zf.namelist()[0]
            return zf.read(name).decode("utf-8-sig")
    return resp.text


def _parse_corpus_csv(csv_text: str) -> list[CorpusEntry]:
    reader = csv.reader(io.StringIO(csv_text))
    header = next(reader, None)
    if header is None:
        return []

    rows: list[CorpusEntry] = []
    for cols in reader:
        if len(cols) < 7:
            continue
        tiploc = cols[0].strip().upper()
        crs = cols[6].strip().upper()
        stanox = cols[4].strip()
        if len(crs) != 3 or not tiploc:
            continue
        rows.append(CorpusEntry(tiploc=tiploc, crs=crs, stanox=stanox or None))
    return rows


# ── KB Stations ────────────────────────────────────────────────────────────────


def refresh_stations() -> int:
    """Download and ingest the KB Stations JSON into the database. Returns row count."""
    url = config.KB_STATIONS_URL
    key = config.KB_STATIONS_KEY
    if not url or not key:
        logger.warning("KB_STATIONS_URL / KB_STATIONS_KEY not set — skipping stations refresh")
        return 0

    logger.info("Downloading KB Stations from %s …", url)
    with httpx.Client(timeout=HTTPX_TIMEOUT) as client:
        resp = client.get(url, headers={"x-apikey": key})
        resp.raise_for_status()

    data = resp.json()
    stations = _parse_stations_json(data)

    db: Session = SessionLocal()
    try:
        db.query(Station).delete()
        db.bulk_save_objects(stations)
        db.commit()
        count = len(stations)
        logger.info("KB Stations refresh complete — %d stations", count)
        return count
    finally:
        db.close()


def _parse_stations_json(data: Any) -> list[Station]:
    if isinstance(data, list):
        array = data
    elif isinstance(data, dict):
        array = (
            data.get("stations")
            or data.get("StationList")
            or data.get("Station")
            or []
        )
    else:
        return []

    stations: list[Station] = []
    for s in array:
        station = _parse_station_object(s)
        if station is not None:
            stations.append(station)
    return stations


def _str(s: dict, *keys: str) -> str:
    for k in keys:
        v = s.get(k, "")
        if isinstance(v, str) and v.strip():
            return v.strip()
    return ""


def _avail(s: dict, *keys: str) -> str:
    for k in keys:
        o = s.get(k)
        if isinstance(o, dict):
            return (o.get("Availability") or o.get("availability") or "").strip()
    return ""


def _parse_station_object(s: dict) -> Station | None:
    crs = _str(s, "CrsCode", "crsCode", "crs")
    if len(crs) != 3:
        return None

    address_parts = [
        _str(s, "Address1", "address1"),
        _str(s, "Address2", "address2"),
        _str(s, "Town", "town"),
        _str(s, "County", "county"),
        _str(s, "Postcode", "postcode"),
    ]
    address = ", ".join(p for p in address_parts if p)

    # Ticket office hours
    to_hours_parts: list[str] = []
    to_obj = s.get("TicketOfficeAvailability") or s.get("ticketOfficeAvailability") or {}
    if isinstance(to_obj, dict):
        for day in ("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"):
            h = (to_obj.get(day) or to_obj.get(day.lower()) or "").strip()
            if h:
                to_hours_parts.append(f"{day}: {h}")
    ticket_office_hours = "\n".join(to_hours_parts)

    # Car parking
    cp = s.get("CarPark") or s.get("carPark") or {}
    parking_parts: list[str] = []
    if isinstance(cp, dict):
        a = (cp.get("Availability") or "").strip()
        if a:
            parking_parts.append(a)
        n = (cp.get("Notes") or "").strip()
        if n:
            parking_parts.append(n)
    car_parking = "\n".join(parking_parts)

    # Staffing
    staffing = _str(s, "Staffing", "staffing")
    if not staffing:
        staffing_obj = s.get("Staffing") or s.get("staffing") or {}
        if isinstance(staffing_obj, dict):
            staffing = (staffing_obj.get("Note") or "").strip()

    return Station(
        crs=crs,
        name=_str(s, "Name", "name"),
        address=address,
        telephone=_str(s, "Telephone", "telephone"),
        staffing_note=staffing,
        ticket_office_hours=ticket_office_hours,
        sstm_availability=_avail(s, "SelfServiceTicketMachines", "selfServiceTicketMachines"),
        step_free_access=_avail(s, "StepFreeAccess", "stepFreeAccess"),
        assistance_avail=_avail(s, "AssistanceAvailability", "assistanceAvailability"),
        wifi=_avail(s, "WiFi", "wifi"),
        toilets=_avail(s, "Toilets", "toilets"),
        waiting_room=_avail(s, "WaitingRoom", "waitingRoom"),
        cctv=_avail(s, "CCTV", "cctv"),
        taxi=_avail(s, "Taxi", "taxi"),
        bus_interchange=_avail(s, "BusInterchange", "busInterchange"),
        car_parking=car_parking,
    )


# ── Combined refresh ──────────────────────────────────────────────────────────


def refresh_all() -> dict[str, int]:
    """Run all refresh tasks. Returns counts per dataset."""
    results: dict[str, int] = {}
    try:
        results["corpus"] = refresh_corpus()
    except Exception:
        logger.exception("CORPUS refresh failed")
        results["corpus"] = -1
    try:
        results["stations"] = refresh_stations()
    except Exception:
        logger.exception("KB Stations refresh failed")
        results["stations"] = -1
    return results
