import logging
from typing import Any, Dict, List
from sqlalchemy import (
    Column,
    Index,
    MetaData,
    String,
    Table,
    Text,
    create_engine,
    insert,
)
from sqlalchemy.dialects.mysql import (
    BIGINT,
    DATETIME,
    DECIMAL,
    JSON,
    SMALLINT,
)
from sqlalchemy.engine import Engine
from sqlalchemy.exc import SQLAlchemyError

from src.config import DatabaseConfig

logger = logging.getLogger(__name__)

metadata = MetaData()

# Definition of the log_records table matching the MySQL schema
log_records = Table(
    "log_records",
    metadata,
    Column("id", BIGINT(unsigned=True), primary_key=True, autoincrement=True),
    Column("event_timestamp", DATETIME(fsp=6), nullable=False),
    Column("correlation_id", String(128), nullable=True),
    Column("service_name", String(100), nullable=True),
    Column("log_source", String(30), nullable=False),
    Column("log_level", String(20), nullable=True),
    Column("message", Text, nullable=True),
    Column("logger_name", String(255), nullable=True),
    Column("thread_name", String(255), nullable=True),
    Column("operation", String(100), nullable=True),
    Column("http_method", String(10), nullable=True),
    Column("request_uri", Text, nullable=True),
    Column("http_status", SMALLINT(unsigned=True), nullable=True),
    Column("request_time", DECIMAL(12, 6), nullable=True),
    Column("upstream_response_time", DECIMAL(12, 6), nullable=True),
    Column("client_ip", String(45), nullable=True),
    Column("user_agent", Text, nullable=True),
    Column("raw_log", JSON, nullable=False),
    Index("idx_correlation_id", "correlation_id"),
    Index("idx_event_timestamp", "event_timestamp"),
    Index("idx_log_source", "log_source"),
    Index("idx_correlation_timestamp", "correlation_id", "event_timestamp"),
)


def get_db_engine(config: DatabaseConfig) -> Engine:
    """
    Create and configure SQLAlchemy engine connecting over TCP/IP via PyMySQL.
    Applies connection pooling and pre-ping for resilient database connectivity.
    """
    engine = create_engine(
        config.connection_url,
        pool_pre_ping=True,
        pool_size=5,
        max_overflow=10,
        pool_recycle=3600,
    )
    return engine


def init_database(engine: Engine, table_name: str = "log_records") -> None:
    """
    Ensure the target log table and its indexes exist.
    """
    try:
        metadata.create_all(engine)
        logger.info(f"Database schema checked/initialized successfully for table '{table_name}'.")
    except SQLAlchemyError as exc:
        logger.error(f"Failed to initialize database tables: {exc}")
        raise


def insert_batch(engine: Engine, records: List[Dict[str, Any]]) -> int:
    """
    Insert a batch of normalized records using a single transaction.
    Uses parameterized query via SQLAlchemy Core insert statement targeting log_records.
    Returns the number of records inserted in this batch.
    Raises SQLAlchemyError on failure (triggers transaction rollback).
    """
    if not records:
        return 0

    try:
        with engine.begin() as connection:
            stmt = insert(log_records)
            connection.execute(stmt, records)
        return len(records)
    except SQLAlchemyError as exc:
        logger.error(f"Database batch insertion error ({len(records)} records failed): {exc}")
        raise
