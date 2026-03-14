"""Tests for the TrainTracker Data API."""

from __future__ import annotations

import io
import os
import zipfile

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

# Override config BEFORE importing app modules so they pick up the test DB.
os.environ["DATABASE_URL"] = "sqlite:///./test_traintracker.db"

from database import Base, CorpusEntry, Station, get_db  # noqa: E402
from fetchers import _parse_corpus_csv, _parse_station_object, _parse_stations_json  # noqa: E402
from main import create_test_app  # noqa: E402

# ── Test DB setup ──────────────────────────────────────────────────────────────

engine = create_engine("sqlite:///./test_traintracker.db", connect_args={"check_same_thread": False})
TestSession = sessionmaker(bind=engine, autoflush=False, expire_on_commit=False)


def override_get_db():
    db = TestSession()
    try:
        yield db
    finally:
        db.close()


app = create_test_app()
app.dependency_overrides[get_db] = override_get_db
client = TestClient(app)


@pytest.fixture(autouse=True)
def _setup_db():
    """Create tables before each test, drop after."""
    Base.metadata.create_all(bind=engine)
    yield
    Base.metadata.drop_all(bind=engine)


def _seed_corpus(*entries: tuple[str, str, str | None]) -> None:
    db = TestSession()
    for tiploc, crs, stanox in entries:
        db.add(CorpusEntry(tiploc=tiploc, crs=crs, stanox=stanox))
    db.commit()
    db.close()


def _seed_station(**kwargs) -> None:
    defaults = dict(
        crs="PAD", name="London Paddington", address="Praed Street, London, W2 1HQ",
        telephone="03457 000 125", staffing_note="Full time", ticket_office_hours="Monday: 06:00-22:00",
        sstm_availability="Yes", step_free_access="Yes", assistance_avail="Yes",
        wifi="Yes", toilets="Yes", waiting_room="Yes", cctv="Yes",
        taxi="Yes", bus_interchange="Yes", car_parking="NCP car park nearby",
    )
    defaults.update(kwargs)
    db = TestSession()
    db.add(Station(**defaults))
    db.commit()
    db.close()


# ── CORPUS endpoint tests ─────────────────────────────────────────────────────


class TestCorpusAll:
    def test_empty_db(self):
        resp = client.get("/api/v1/corpus/all")
        assert resp.status_code == 200
        assert resp.json() == []

    def test_returns_all_entries(self):
        _seed_corpus(("PADTON", "PAD", "87801"), ("VICTRIA", "VIC", "87102"))
        resp = client.get("/api/v1/corpus/all")
        assert resp.status_code == 200
        data = resp.json()
        assert len(data) == 2
        tiplocs = {r["tiploc"] for r in data}
        assert tiplocs == {"PADTON", "VICTRIA"}


class TestCorpusLookup:
    def test_no_params_returns_400(self):
        resp = client.get("/api/v1/corpus")
        assert resp.status_code == 400

    def test_lookup_by_tiplocs(self):
        _seed_corpus(("PADTON", "PAD", "87801"), ("VICTRIA", "VIC", "87102"), ("EUSTON", "EUS", "87200"))
        resp = client.get("/api/v1/corpus", params={"tiplocs": "PADTON,EUSTON"})
        assert resp.status_code == 200
        data = resp.json()
        assert len(data) == 2
        assert {r["crs"] for r in data} == {"PAD", "EUS"}

    def test_lookup_by_stanoxes(self):
        _seed_corpus(("PADTON", "PAD", "87801"), ("VICTRIA", "VIC", "87102"))
        resp = client.get("/api/v1/corpus", params={"stanoxes": "87801"})
        assert resp.status_code == 200
        data = resp.json()
        assert len(data) == 1
        assert data[0]["tiploc"] == "PADTON"

    def test_lookup_by_crs(self):
        _seed_corpus(("PADTON", "PAD", "87801"), ("PADNBRY", "PAD", None))
        resp = client.get("/api/v1/corpus", params={"crs": "PAD"})
        assert resp.status_code == 200
        data = resp.json()
        assert len(data) == 2
        assert all(r["crs"] == "PAD" for r in data)

    def test_case_insensitive_tiploc(self):
        _seed_corpus(("PADTON", "PAD", "87801"))
        resp = client.get("/api/v1/corpus", params={"tiplocs": "padton"})
        assert resp.status_code == 200
        assert len(resp.json()) == 1

    def test_case_insensitive_crs(self):
        _seed_corpus(("PADTON", "PAD", "87801"))
        resp = client.get("/api/v1/corpus", params={"crs": "pad"})
        assert resp.status_code == 200
        assert len(resp.json()) == 1

    def test_unknown_tiploc_returns_empty(self):
        resp = client.get("/api/v1/corpus", params={"tiplocs": "ZZZZZ"})
        assert resp.status_code == 200
        assert resp.json() == []


# ── Station endpoint tests ─────────────────────────────────────────────────────


class TestStationGet:
    def test_existing_station(self):
        _seed_station()
        resp = client.get("/api/v1/stations/PAD")
        assert resp.status_code == 200
        data = resp.json()
        assert data["crs"] == "PAD"
        assert data["name"] == "London Paddington"
        assert data["step_free_access"] == "Yes"

    def test_case_insensitive(self):
        _seed_station()
        resp = client.get("/api/v1/stations/pad")
        assert resp.status_code == 200
        assert resp.json()["crs"] == "PAD"

    def test_not_found(self):
        resp = client.get("/api/v1/stations/ZZZ")
        assert resp.status_code == 404


class TestStationList:
    def test_list_all(self):
        _seed_station(crs="PAD", name="London Paddington")
        _seed_station(crs="VIC", name="London Victoria")
        resp = client.get("/api/v1/stations")
        assert resp.status_code == 200
        assert len(resp.json()) == 2

    def test_search_by_name(self):
        _seed_station(crs="PAD", name="London Paddington")
        _seed_station(crs="VIC", name="London Victoria")
        _seed_station(crs="BHM", name="Birmingham New Street")
        resp = client.get("/api/v1/stations", params={"q": "London"})
        assert resp.status_code == 200
        data = resp.json()
        assert len(data) == 2
        assert all("London" in s["name"] for s in data)

    def test_search_case_insensitive(self):
        _seed_station(crs="PAD", name="London Paddington")
        resp = client.get("/api/v1/stations", params={"q": "paddington"})
        assert resp.status_code == 200
        assert len(resp.json()) == 1


# ── Health endpoint ────────────────────────────────────────────────────────────


class TestHealth:
    def test_health_empty(self):
        resp = client.get("/api/v1/health")
        assert resp.status_code == 200
        data = resp.json()
        assert data["status"] == "ok"
        assert data["corpus_entries"] == 0
        assert data["station_entries"] == 0

    def test_health_with_data(self):
        _seed_corpus(("PADTON", "PAD", "87801"))
        _seed_station()
        resp = client.get("/api/v1/health")
        data = resp.json()
        assert data["corpus_entries"] == 1
        assert data["station_entries"] == 1


# ── Refresh endpoint ──────────────────────────────────────────────────────────


class TestRefresh:
    def test_refresh_no_urls_configured(self):
        # With no CORPUS_DOWNLOAD_URL / KB_STATIONS_URL set, refresh returns 0 counts
        resp = client.post("/api/v1/refresh")
        assert resp.status_code == 200
        data = resp.json()
        assert data["status"] == "ok"
        assert data["counts"]["corpus"] == 0
        assert data["counts"]["stations"] == 0


# ── Parser unit tests ─────────────────────────────────────────────────────────


class TestParseCorpusCsv:
    def test_basic_csv(self):
        csv_text = (
            "TIPLOC,ABBREVIATION,STARTDATE,ENDDATE,STANOX,UIC,CRS,DESCRIPTION,16CHA,NLC\n"
            "PADTON,PADD,,,87801,,PAD,LONDON PADDINGTON,,\n"
            "VICTRIA,VIC,,,87102,,VIC,LONDON VICTORIA,,\n"
        )
        rows = _parse_corpus_csv(csv_text)
        assert len(rows) == 2
        assert rows[0].tiploc == "PADTON"
        assert rows[0].crs == "PAD"
        assert rows[0].stanox == "87801"
        assert rows[1].tiploc == "VICTRIA"
        assert rows[1].crs == "VIC"

    def test_skips_short_rows(self):
        csv_text = "TIPLOC,ABBREVIATION,STARTDATE,ENDDATE,STANOX,UIC,CRS\nFOO,BAR\n"
        rows = _parse_corpus_csv(csv_text)
        assert len(rows) == 0

    def test_skips_invalid_crs(self):
        csv_text = (
            "TIPLOC,ABBREVIATION,STARTDATE,ENDDATE,STANOX,UIC,CRS,DESC,16CHA,NLC\n"
            "BADTIP,BBB,,,12345,,,BAD DESC,,\n"
        )
        rows = _parse_corpus_csv(csv_text)
        assert len(rows) == 0

    def test_empty_csv(self):
        assert _parse_corpus_csv("") == []

    def test_header_only(self):
        assert _parse_corpus_csv("TIPLOC,A,B,C,D,E,CRS\n") == []


class TestParseStationObject:
    def test_basic_station(self):
        s = {
            "CrsCode": "PAD",
            "Name": "London Paddington",
            "Address1": "Praed Street",
            "Town": "London",
            "Postcode": "W2 1HQ",
            "Telephone": "03457 000 125",
            "StepFreeAccess": {"Availability": "Yes"},
            "WiFi": {"Availability": "Yes"},
        }
        station = _parse_station_object(s)
        assert station is not None
        assert station.crs == "PAD"
        assert station.name == "London Paddington"
        assert station.address == "Praed Street, London, W2 1HQ"
        assert station.step_free_access == "Yes"
        assert station.wifi == "Yes"

    def test_lowercase_keys(self):
        s = {
            "crs": "VIC",
            "name": "London Victoria",
            "stepFreeAccess": {"availability": "Partial"},
        }
        station = _parse_station_object(s)
        assert station is not None
        assert station.crs == "VIC"
        assert station.step_free_access == "Partial"

    def test_invalid_crs_returns_none(self):
        s = {"CrsCode": "XX", "Name": "Bad"}
        assert _parse_station_object(s) is None

    def test_missing_crs_returns_none(self):
        s = {"Name": "No CRS"}
        assert _parse_station_object(s) is None

    def test_ticket_office_hours(self):
        s = {
            "CrsCode": "PAD",
            "Name": "Test",
            "TicketOfficeAvailability": {
                "Monday": "06:00-22:00",
                "Saturday": "07:00-20:00",
            },
        }
        station = _parse_station_object(s)
        assert station is not None
        assert "Monday: 06:00-22:00" in station.ticket_office_hours
        assert "Saturday: 07:00-20:00" in station.ticket_office_hours

    def test_car_parking(self):
        s = {
            "CrsCode": "PAD",
            "Name": "Test",
            "CarPark": {"Availability": "Yes", "Notes": "500 spaces"},
        }
        station = _parse_station_object(s)
        assert station is not None
        assert "Yes" in station.car_parking
        assert "500 spaces" in station.car_parking

    def test_staffing_as_object(self):
        s = {
            "CrsCode": "PAD",
            "Name": "Test",
            "Staffing": {"Note": "Part time"},
        }
        station = _parse_station_object(s)
        assert station is not None
        assert station.staffing_note == "Part time"


class TestParseStationsJson:
    def test_array_format(self):
        data = [
            {"CrsCode": "PAD", "Name": "London Paddington"},
            {"CrsCode": "VIC", "Name": "London Victoria"},
        ]
        stations = _parse_stations_json(data)
        assert len(stations) == 2

    def test_dict_with_stations_key(self):
        data = {"stations": [{"CrsCode": "PAD", "Name": "London Paddington"}]}
        stations = _parse_stations_json(data)
        assert len(stations) == 1

    def test_dict_with_station_list_key(self):
        data = {"StationList": [{"CrsCode": "VIC", "Name": "London Victoria"}]}
        stations = _parse_stations_json(data)
        assert len(stations) == 1

    def test_empty(self):
        assert _parse_stations_json([]) == []
        assert _parse_stations_json({}) == []
        assert _parse_stations_json("bad") == []


# ── CRS Alias tests ──────────────────────────────────────────────────────────


class TestCrsAliases:
    """Tests that CRS alias resolution works across all affected endpoints."""

    def test_aliases_endpoint(self):
        resp = client.get("/api/v1/corpus/aliases")
        assert resp.status_code == 200
        data = resp.json()
        assert "SPL" in data["aliases"]
        assert data["aliases"]["SPL"] == "STP"
        assert "ABX" in data["aliases"]
        assert data["aliases"]["ABX"] == "ABW"

    def test_corpus_all_resolves_aliases(self):
        """SPL entries should come back as STP in the bulk export."""
        _seed_corpus(("STPXINT", "SPL", "87701"), ("STPX", "STP", "87700"))
        resp = client.get("/api/v1/corpus/all")
        data = resp.json()
        # Both should be normalised to STP
        assert all(r["crs"] == "STP" for r in data)

    def test_corpus_lookup_by_canonical_finds_aliases(self):
        """Querying crs=STP should also return rows stored as SPL."""
        _seed_corpus(("STPXINT", "SPL", "87701"), ("STPX", "STP", "87700"))
        resp = client.get("/api/v1/corpus", params={"crs": "STP"})
        data = resp.json()
        tiplocs = {r["tiploc"] for r in data}
        assert "STPXINT" in tiplocs
        assert "STPX" in tiplocs

    def test_corpus_lookup_by_alias_finds_canonical(self):
        """Querying crs=SPL should also return rows stored as STP."""
        _seed_corpus(("STPXINT", "SPL", "87701"), ("STPX", "STP", "87700"))
        resp = client.get("/api/v1/corpus", params={"crs": "SPL"})
        data = resp.json()
        tiplocs = {r["tiploc"] for r in data}
        assert "STPXINT" in tiplocs
        assert "STPX" in tiplocs

    def test_station_lookup_by_alias(self):
        """Looking up station by alias CRS should find the canonical station."""
        _seed_station(crs="ABW", name="Abbey Wood")
        resp = client.get("/api/v1/stations/ABX")
        assert resp.status_code == 200
        assert resp.json()["crs"] == "ABW"
        assert resp.json()["name"] == "Abbey Wood"

    def test_station_lookup_by_canonical_still_works(self):
        _seed_station(crs="STP", name="London St Pancras International")
        resp = client.get("/api/v1/stations/STP")
        assert resp.status_code == 200
        assert resp.json()["crs"] == "STP"
