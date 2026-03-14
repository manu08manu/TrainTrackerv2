"""SQLAlchemy database setup and models."""

from sqlalchemy import Column, String, Text, create_engine
from sqlalchemy.orm import DeclarativeBase, sessionmaker

import config

engine = create_engine(
    config.DATABASE_URL,
    connect_args={"check_same_thread": False} if "sqlite" in config.DATABASE_URL else {},
)
SessionLocal = sessionmaker(bind=engine, autoflush=False, expire_on_commit=False)


class Base(DeclarativeBase):
    pass


class CorpusEntry(Base):
    """TIPLOC / STANOX → CRS mapping row."""

    __tablename__ = "corpus"

    tiploc = Column(String(10), primary_key=True, index=True)
    crs = Column(String(3), nullable=False, index=True)
    stanox = Column(String(10), nullable=True, index=True)


class Station(Base):
    """Knowledgebase station information."""

    __tablename__ = "stations"

    crs = Column(String(3), primary_key=True, index=True)
    name = Column(String(256), nullable=False)
    address = Column(Text, default="")
    telephone = Column(String(64), default="")
    staffing_note = Column(Text, default="")
    ticket_office_hours = Column(Text, default="")
    sstm_availability = Column(String(128), default="")
    step_free_access = Column(String(128), default="")
    assistance_avail = Column(String(128), default="")
    wifi = Column(String(128), default="")
    toilets = Column(String(128), default="")
    waiting_room = Column(String(128), default="")
    cctv = Column(String(128), default="")
    taxi = Column(String(128), default="")
    bus_interchange = Column(String(128), default="")
    car_parking = Column(Text, default="")


def create_tables() -> None:
    Base.metadata.create_all(bind=engine)


def get_db():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()
