import logging
import sys
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Any, Dict

# Ensure project root is in sys.path
PROJECT_ROOT = Path(__file__).resolve().parent.parent
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from fastapi import FastAPI, Request, status
from fastapi.responses import JSONResponse
from sqlalchemy.engine import Engine

from src.config import load_database_config, load_http_server_config
from src.database import get_db_engine, init_database, insert_batch
from src.log_processor import process_entries
from src.log_reader import parse_ndjson_text

# Configure standard Python logging
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
    handlers=[logging.StreamHandler(sys.stdout)],
)
logger = logging.getLogger("log_ingestion_api")


@asynccontextmanager
async def lifespan(app: FastAPI):
    """
    Lifespan context manager to initialize and dispose the database engine
    resiliently across the FastAPI application lifecycle.
    """
    logger.info("Initializing database connection for FastAPI log ingestion service...")
    try:
        db_config = load_database_config()
        engine = get_db_engine(db_config)
        init_database(engine, table_name=db_config.table_name)
        app.state.db_engine = engine
        logger.info(f"Database connection ready: {db_config.name} at {db_config.host}:{db_config.port}")
    except Exception as exc:
        logger.error(f"Failed to connect to MySQL database during startup: {exc}")
        # Allow server to start so health/errors can be inspected, engine will be None
        app.state.db_engine = None

    yield

    if getattr(app.state, "db_engine", None) is not None:
        logger.info("Disposing database connection pool...")
        app.state.db_engine.dispose()


app = FastAPI(
    title="Log Ingestion Service",
    description="HTTP NDJSON log ingestion service for Spring Boot and Nginx logs",
    version="1.0.0",
    lifespan=lifespan,
)


def get_engine(request: Request) -> Engine:
    """Retrieve or lazily initialize the SQLAlchemy database engine."""
    engine = getattr(request.app.state, "db_engine", None)
    if engine is None:
        db_config = load_database_config()
        engine = get_db_engine(db_config)
        init_database(engine, table_name=db_config.table_name)
        request.app.state.db_engine = engine
    return engine


@app.get("/health")
async def health_check(request: Request):
    """Health check endpoint to verify service and database availability."""
    db_status = "connected" if getattr(request.app.state, "db_engine", None) is not None else "disconnected"
    return {
        "status": "healthy",
        "service": "log-ingestion-api",
        "database": db_status,
    }


@app.post("/ingest")
async def ingest_logs(request: Request):
    """
    Ingest NDJSON (Newline Delimited JSON) log records from HTTP request body.
    Supports single or multiple newline-separated JSON records in a single request.
    Decodes the raw body, parses lines independently, validates, normalizes,
    and batch-inserts valid records into MySQL.
    """
    # 1. Read raw request body
    raw_body = await request.body()
    if not raw_body or not raw_body.strip():
        return JSONResponse(
            status_code=status.HTTP_400_BAD_REQUEST,
            content={
                "status": "failed",
                "received": 0,
                "inserted": 0,
                "rejected": 0,
                "message": "Empty request body. Expected newline-delimited JSON (NDJSON).",
            },
        )

    # 2. Decode raw body as UTF-8
    try:
        body_text = raw_body.decode("utf-8")
    except UnicodeDecodeError as exc:
        logger.warning(f"Failed to decode request body as UTF-8: {exc}")
        return JSONResponse(
            status_code=status.HTTP_400_BAD_REQUEST,
            content={
                "status": "failed",
                "received": 0,
                "inserted": 0,
                "rejected": 0,
                "message": f"Invalid UTF-8 payload: {exc.reason}",
            },
        )

    # 3. Parse NDJSON lines
    entries = parse_ndjson_text(body_text)
    if not entries:
        return JSONResponse(
            status_code=status.HTTP_400_BAD_REQUEST,
            content={
                "status": "failed",
                "received": 0,
                "inserted": 0,
                "rejected": 0,
                "message": "No non-empty log lines found in request body.",
            },
        )

    # 4. Validate and normalize using the existing processing pipeline
    process_result = process_entries(entries)
    received_count = process_result.received
    rejected_count = len(process_result.rejections)
    inserted_count = 0

    # 5. Persist valid records in a single database transaction
    if process_result.normalized_records:
        try:
            engine = get_engine(request)
            inserted_count = insert_batch(engine, process_result.normalized_records)
            logger.info(
                f"Successfully persisted batch of {inserted_count} record(s) "
                f"(Spring Boot: {process_result.spring_boot_count}, Nginx: {process_result.nginx_count})."
            )
        except Exception as exc:
            logger.error(f"Database insertion failed for batch of {len(process_result.normalized_records)} records: {exc}")
            return JSONResponse(
                status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
                content={
                    "status": "error",
                    "received": received_count,
                    "inserted": 0,
                    "rejected": rejected_count,
                    "message": "Database insertion failure. Transaction was rolled back.",
                },
            )

    # 6. Determine status and construct response
    if rejected_count == 0:
        response_status = "success"
        http_status_code = status.HTTP_200_OK
    elif inserted_count > 0:
        response_status = "partial_success"
        http_status_code = status.HTTP_200_OK
    else:
        response_status = "failed"
        http_status_code = status.HTTP_422_UNPROCESSABLE_ENTITY

    response_payload: Dict[str, Any] = {
        "status": response_status,
        "received": received_count,
        "inserted": inserted_count,
        "rejected": rejected_count,
    }

    if process_result.rejections:
        response_payload["errors"] = process_result.rejections

    return JSONResponse(status_code=http_status_code, content=response_payload)


if __name__ == "__main__":
    import uvicorn
    server_cfg = load_http_server_config()
    logger.info(f"Starting Log Ingestion Service on {server_cfg.host}:{server_cfg.port}...")
    uvicorn.run("src.api:app", host=server_cfg.host, port=server_cfg.port, reload=False)

