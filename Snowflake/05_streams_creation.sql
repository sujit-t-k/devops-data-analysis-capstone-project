-- =============================================================================
-- Phase 5: Streams Creation
-- Purpose: Create CDC (Change Data Capture) streams on the Bronze tables.
--          Configured with APPEND_ONLY = TRUE to track newly arrived rows
--          from Snowpipe Streaming without overhead of update/delete tracking.
-- =============================================================================

USE DATABASE LOG_ANALYTICS_DB;
USE SCHEMA BRONZE;
USE WAREHOUSE LOG_ANALYTICS_WH;

-- 1. Stream on BRONZE.LOG_RECORDS
-- Tracks newly ingested log events.
-- When consumed inside a DML transaction (INSERT/MERGE), the stream offset
-- automatically advances to the transaction commit time.
CREATE OR REPLACE STREAM BRONZE.LOG_RECORDS_STREAM
    ON TABLE BRONZE.LOG_RECORDS
    APPEND_ONLY = TRUE
    SHOW_INITIAL_ROWS = FALSE
    COMMENT = 'Append-only stream tracking newly ingested raw log records for Silver ETL';

-- 2. Stream on BRONZE.INCIDENTS
-- Tracks newly ingested incident status events and remediations.
CREATE OR REPLACE STREAM BRONZE.INCIDENTS_STREAM
    ON TABLE BRONZE.INCIDENTS
    APPEND_ONLY = TRUE
    SHOW_INITIAL_ROWS = FALSE
    COMMENT = 'Append-only stream tracking newly ingested raw incident records for Silver ETL';

-- -----------------------------------------------------------------------------
-- Verification Queries (Phase 5)
-- -----------------------------------------------------------------------------
SHOW STREAMS IN SCHEMA LOG_ANALYTICS_DB.BRONZE;

-- Verify stream change detection functions (initially FALSE when tables are empty)
SELECT 
    SYSTEM$STREAM_HAS_DATA('BRONZE.LOG_RECORDS_STREAM') AS LOGS_STREAM_HAS_DATA,
    SYSTEM$STREAM_HAS_DATA('BRONZE.INCIDENTS_STREAM') AS INCIDENTS_STREAM_HAS_DATA;