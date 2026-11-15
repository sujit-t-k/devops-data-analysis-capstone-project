import logging
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import List, Dict, Any

# Ensure project root is in sys.path regardless of how the script is invoked
PROJECT_ROOT = Path(__file__).resolve().parent.parent
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from src.config import load_database_config, load_source_config
from src.database import get_db_engine, init_database, insert_batch
from src.log_processor import (
    SOURCE_NGINX,
    SOURCE_SPRING_BOOT,
    validate_and_normalize_record,
)
from src.log_reader import read_jsonl_file

# Configure standard Python logging
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
    handlers=[logging.StreamHandler(sys.stdout)],
)
logger = logging.getLogger("log_processor_app")


@dataclass
class ProcessingMetrics:
    total_lines: int = 0
    spring_boot_records: int = 0
    nginx_records: int = 0
    successfully_inserted: int = 0
    rejected_records: int = 0
    db_failures: int = 0


def print_summary(metrics: ProcessingMetrics) -> None:
    """Display processing summary."""
    summary = f"""
============================================================
                   PROCESSING SUMMARY
============================================================
Total lines read:                  {metrics.total_lines}
Spring Boot records identified:    {metrics.spring_boot_records}
Nginx records identified:          {metrics.nginx_records}
Successfully inserted records:     {metrics.successfully_inserted}
Rejected records:                  {metrics.rejected_records}
Database insertion failures:       {metrics.db_failures}
============================================================
"""
    print(summary)


def run() -> None:
    """Main execution flow for reading, validating, normalizing, and storing logs."""
    logger.info("Starting Python Log Processing Application...")

    # 1. Load configuration
    try:
        db_config = load_database_config()
        source_config = load_source_config()
    except Exception as exc:
        logger.error(f"Configuration error: {exc}")
        sys.exit(1)

    logger.info(f"Target Database: {db_config.name} at {db_config.host}:{db_config.port}")
    logger.info(f"Target Table: {db_config.table_name}")
    logger.info(f"Source JSONL File: {source_config.source_file}")
    logger.info(f"Batch Size: {source_config.batch_size}")

    # 2. Initialize Database Engine and Schema
    try:
        engine = get_db_engine(db_config)
        # Test connection and ensure table exists
        init_database(engine, table_name=db_config.table_name)
    except Exception as exc:
        logger.error(f"Failed to connect to MySQL database: {exc}")
        logger.error(
            "Please ensure MySQL is running, the database 'aji_log_platform' exists, "
            "and credentials in mysql-config.env are correct."
        )
        sys.exit(1)

    metrics = ProcessingMetrics()
    batch: List[Dict[str, Any]] = []

    def flush_batch() -> None:
        nonlocal batch
        if not batch:
            return
        try:
            inserted_count = insert_batch(engine, batch)
            metrics.successfully_inserted += inserted_count
            logger.info(f"Inserted batch of {inserted_count} record(s) into database.")
        except Exception as exc:
            metrics.db_failures += len(batch)
            logger.error(f"Failed to insert batch of {len(batch)} record(s): {exc}")
        finally:
            batch = []

    # 3. Read and process JSONL file
    try:
        for entry in read_jsonl_file(source_config.source_file):
            metrics.total_lines += 1

            # Handle JSON parse errors
            if entry.error:
                metrics.rejected_records += 1
                logger.warning(f"REJECTED - {entry.error}")
                continue

            if entry.record is None:
                metrics.rejected_records += 1
                logger.warning(f"REJECTED - Line {entry.line_number}: Record is null")
                continue

            # Validate and normalize
            source, normalized_data, validation_error = validate_and_normalize_record(
                entry.record, entry.line_number
            )

            if validation_error or normalized_data is None:
                metrics.rejected_records += 1
                logger.warning(f"REJECTED - {validation_error}")
                continue

            # Track source stats
            if source == SOURCE_SPRING_BOOT:
                metrics.spring_boot_records += 1
            elif source == SOURCE_NGINX:
                metrics.nginx_records += 1

            batch.append(normalized_data)

            # Flush batch if threshold reached
            if len(batch) >= source_config.batch_size:
                flush_batch()

        # Flush any remaining records
        flush_batch()

    except FileNotFoundError as exc:
        logger.error(f"Source file not found: {exc}")
        sys.exit(1)
    except Exception as exc:
        logger.error(f"Unexpected error during log processing: {exc}")
        sys.exit(1)

    # 4. Display summary
    print_summary(metrics)
    logger.info("Log processing job completed.")


if __name__ == "__main__":
    run()
