"""Application configuration — loaded from environment variables."""

import os

# ── CORPUS (TIPLOC/STANOX → CRS) ──────────────────────────────────────────────
# URL provided by your Rail Data Marketplace CORPUS subscription.
CORPUS_DOWNLOAD_URL: str = os.environ.get("CORPUS_DOWNLOAD_URL", "")

# ── Knowledgebase Stations (JSON) ──────────────────────────────────────────────
KB_STATIONS_URL: str = os.environ.get("KB_STATIONS_URL", "")
KB_STATIONS_KEY: str = os.environ.get("KB_STATIONS_KEY", "")

# ── Database ───────────────────────────────────────────────────────────────────
DATABASE_URL: str = os.environ.get("DATABASE_URL", "sqlite:///./traintracker.db")

# ── Scheduler ──────────────────────────────────────────────────────────────────
# Hour (0-23) at which the daily refresh runs. Default 04:00 UTC.
REFRESH_HOUR: int = int(os.environ.get("REFRESH_HOUR", "4"))

# ── CRS Aliases ────────────────────────────────────────────────────────────────
# Some stations have multiple CRS codes in CORPUS depending on platform/operator.
# Keys are alternative CRS codes; values are the canonical CRS used in station data.
# Update this dict when new aliases are discovered.
CRS_ALIASES: dict[str, str] = {
    "SPL": "STP",   # St Pancras International (Thameslink low-level)
    "ABX": "ABW",   # Abbey Wood (Elizabeth Line)
}


def canonical_crs(crs: str) -> str:
    """Resolve an alternative CRS to its canonical form, or return as-is."""
    return CRS_ALIASES.get(crs.upper(), crs.upper())


def aliases_for(canonical: str) -> list[str]:
    """Return all alternative CRS codes that map to the given canonical CRS."""
    target = canonical.upper()
    return [alt for alt, canon in CRS_ALIASES.items() if canon == target]
