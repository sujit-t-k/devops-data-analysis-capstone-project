-- =============================================================================
-- Phase 6: Tasks and Task Graph Creation
-- Purpose: Implement automated, event-driven data pipeline orchestration from
--          Bronze to Silver using Snowflake Streams and a parent-child Task Graph.
--
-- Orchestration Architecture:
-- 1. Root Task: TSK_PIPELINE_ROOT_ORCHESTRATOR runs on a 2-minute schedule.
-- 2. Child Task 1: TSK_TRANSFORM_SILVER_LOG_RECORDS executes AFTER root task
--    only WHEN SYSTEM$STREAM_HAS_DATA('BRONZE.LOG_RECORDS_STREAM') is TRUE.
-- 3. Child Task 2: TSK_TRANSFORM_SILVER_INCIDENTS executes AFTER root task
--    only WHEN SYSTEM$STREAM_HAS_DATA('BRONZE.INCIDENTS_STREAM') is TRUE.
--
-- Note on Gold Layer:
-- Because Gold is implemented as Views over Silver, Gold automatically and
-- instantaneously reflects the updated Silver data upon transaction commit.
-- No redundant compute or polling tasks are needed for Gold views.
-- =============================================================================

USE DATABASE LOG_ANALYTICS_DB;
USE SCHEMA SILVER;
USE WAREHOUSE LOG_ANALYTICS_WH;

-- -----------------------------------------------------------------------------
-- 1. Root Orchestrator Task
-- Runs every 2 minutes. Acts as the heartbeat trigger for downstream DAG tasks.
-- A lightweight root task prevents independent schedules from drifting out of sync.
-- -----------------------------------------------------------------------------
CREATE OR REPLACE TASK SILVER.TSK_PIPELINE_ROOT_ORCHESTRATOR
    WAREHOUSE = LOG_ANALYTICS_WH
    SCHEDULE = '2 MINUTE'
    COMMENT = 'Root task trigger for the Bronze-to-Silver ETL pipeline'
AS
    SELECT CURRENT_TIMESTAMP() AS PIPELINE_HEARTBEAT_UTC;

-- -----------------------------------------------------------------------------
-- 2. Silver Log Records Transformation Task (Child of Root)
-- Transforms, deduplicates, and filters raw logs from BRONZE.LOG_RECORDS_STREAM.
-- - Evaluates WHEN SYSTEM$STREAM_HAS_DATA: skips execution if no new rows arrived.
-- - Filters out root NGINX health probes (LOG_SOURCE = 'NGINX' AND REQUEST_URI = '/').
-- - Deduplicates stream changes using QUALIFY ROW_NUMBER() OVER (PARTITION BY ID).
-- - Consumes stream offset atomic with the MERGE commit.
-- -----------------------------------------------------------------------------
CREATE OR REPLACE TASK SILVER.TSK_TRANSFORM_SILVER_LOG_RECORDS
    WAREHOUSE = LOG_ANALYTICS_WH
    COMMENT = 'Child task: merges deduplicated and cleaned logs from Bronze stream into Silver'
    AFTER SILVER.TSK_PIPELINE_ROOT_ORCHESTRATOR
    WHEN SYSTEM$STREAM_HAS_DATA('LOG_ANALYTICS_DB.BRONZE.LOG_RECORDS_STREAM')
AS
MERGE INTO SILVER.LOG_RECORDS target
USING (
    SELECT
        ID,
        EVENT_TIMESTAMP,
        CORRELATION_ID,
        SERVICE_NAME,
        UPPER(TRIM(LOG_SOURCE)) AS LOG_SOURCE,
        UPPER(TRIM(LOG_LEVEL)) AS LOG_LEVEL,
        MESSAGE,
        LOGGER_NAME,
        THREAD_NAME,
        -- Extract operation from raw JSON payload if top-level field is null
        COALESCE(OPERATION, RAW_LOG:operation::VARCHAR) AS OPERATION,
        HTTP_METHOD,
        REQUEST_URI,
        HTTP_STATUS,
        REQUEST_TIME,
        UPSTREAM_RESPONSE_TIME,
        CLIENT_IP,
        USER_AGENT,
        RAW_LOG,
        INGESTED_AT AS BRONZE_INGESTED_AT
    FROM LOG_ANALYTICS_DB.BRONZE.LOG_RECORDS_STREAM
    -- Filter requirement: Exclude NGINX access records where REQUEST_URI is exactly '/'
    WHERE NOT (
        UPPER(COALESCE(LOG_SOURCE, '')) = 'NGINX'
        AND REQUEST_URI = '/'
    )
    -- Deduplication: select the latest record per ID within the stream window
    QUALIFY ROW_NUMBER() OVER (
        PARTITION BY ID 
        ORDER BY INGESTED_AT DESC
    ) = 1
) source
ON target.ID = source.ID
WHEN MATCHED THEN UPDATE SET
    target.EVENT_TIMESTAMP        = source.EVENT_TIMESTAMP,
    target.CORRELATION_ID         = source.CORRELATION_ID,
    target.SERVICE_NAME           = source.SERVICE_NAME,
    target.LOG_SOURCE             = source.LOG_SOURCE,
    target.LOG_LEVEL              = source.LOG_LEVEL,
    target.MESSAGE                = source.MESSAGE,
    target.LOGGER_NAME            = source.LOGGER_NAME,
    target.THREAD_NAME            = source.THREAD_NAME,
    target.OPERATION              = source.OPERATION,
    target.HTTP_METHOD            = source.HTTP_METHOD,
    target.REQUEST_URI            = source.REQUEST_URI,
    target.HTTP_STATUS            = source.HTTP_STATUS,
    target.REQUEST_TIME           = source.REQUEST_TIME,
    target.UPSTREAM_RESPONSE_TIME = source.UPSTREAM_RESPONSE_TIME,
    target.CLIENT_IP              = source.CLIENT_IP,
    target.USER_AGENT             = source.USER_AGENT,
    target.RAW_LOG                = source.RAW_LOG,
    target.BRONZE_INGESTED_AT     = source.BRONZE_INGESTED_AT,
    target.PROCESSED_AT           = CURRENT_TIMESTAMP()
WHEN NOT MATCHED THEN INSERT (
    ID,
    EVENT_TIMESTAMP,
    CORRELATION_ID,
    SERVICE_NAME,
    LOG_SOURCE,
    LOG_LEVEL,
    MESSAGE,
    LOGGER_NAME,
    THREAD_NAME,
    OPERATION,
    HTTP_METHOD,
    REQUEST_URI,
    HTTP_STATUS,
    REQUEST_TIME,
    UPSTREAM_RESPONSE_TIME,
    CLIENT_IP,
    USER_AGENT,
    RAW_LOG,
    BRONZE_INGESTED_AT,
    PROCESSED_AT
) VALUES (
    source.ID,
    source.EVENT_TIMESTAMP,
    source.CORRELATION_ID,
    source.SERVICE_NAME,
    source.LOG_SOURCE,
    source.LOG_LEVEL,
    source.MESSAGE,
    source.LOGGER_NAME,
    source.THREAD_NAME,
    source.OPERATION,
    source.HTTP_METHOD,
    source.REQUEST_URI,
    source.HTTP_STATUS,
    source.REQUEST_TIME,
    source.UPSTREAM_RESPONSE_TIME,
    source.CLIENT_IP,
    source.USER_AGENT,
    source.RAW_LOG,
    source.BRONZE_INGESTED_AT,
    CURRENT_TIMESTAMP()
);

-- -----------------------------------------------------------------------------
-- 3. Silver Incidents Transformation Task (Child of Root)
-- Transforms, deduplicates, and calculates MTTR fields from BRONZE.INCIDENTS_STREAM.
-- - Standardizes status values (INCIDENT_IDENTIFIED, REMEDIATION_DEFINED, REMEDIATION_IN_PROGRESS, RESOLVED).
-- - Derives RESOLUTION_DURATION_SECONDS for RESOLVED status where timestamps exist.
-- - Deduplicates by incident ID keeping the latest state by (UPDATED_AT DESC, INGESTED_AT DESC).
-- -----------------------------------------------------------------------------
CREATE OR REPLACE TASK SILVER.TSK_TRANSFORM_SILVER_INCIDENTS
    WAREHOUSE = LOG_ANALYTICS_WH
    COMMENT = 'Child task: merges deduplicated incident records and calculates resolution duration'
    AFTER SILVER.TSK_PIPELINE_ROOT_ORCHESTRATOR
    WHEN SYSTEM$STREAM_HAS_DATA('LOG_ANALYTICS_DB.BRONZE.INCIDENTS_STREAM')
AS
MERGE INTO SILVER.INCIDENTS target
USING (
    SELECT
        ID,
        CORRELATION_ID,
        TITLE,
        DESCRIPTION,
        REMEDIATION_PLAN,
        UPPER(TRIM(STATUS)) AS STATUS,
        CREATED_AT,
        UPDATED_AT,
        RESOLVED_AT,
        -- Calculate resolution duration in seconds for resolved incidents
        CASE 
            WHEN UPPER(TRIM(STATUS)) = 'RESOLVED' AND RESOLVED_AT IS NOT NULL 
            THEN DATEDIFF('second', CREATED_AT, RESOLVED_AT)
            ELSE NULL 
        END AS RESOLUTION_DURATION_SECONDS,
        INGESTED_AT AS BRONZE_INGESTED_AT
    FROM LOG_ANALYTICS_DB.BRONZE.INCIDENTS_STREAM
    -- Deduplication: select the latest incident update state per ID within the stream window
    QUALIFY ROW_NUMBER() OVER (
        PARTITION BY ID 
        ORDER BY UPDATED_AT DESC, INGESTED_AT DESC
    ) = 1
) source
ON target.ID = source.ID
WHEN MATCHED THEN UPDATE SET
    target.CORRELATION_ID              = source.CORRELATION_ID,
    target.TITLE                       = source.TITLE,
    target.DESCRIPTION                 = source.DESCRIPTION,
    target.REMEDIATION_PLAN            = source.REMEDIATION_PLAN,
    target.STATUS                      = source.STATUS,
    target.CREATED_AT                  = source.CREATED_AT,
    target.UPDATED_AT                  = source.UPDATED_AT,
    target.RESOLVED_AT                 = source.RESOLVED_AT,
    target.RESOLUTION_DURATION_SECONDS = source.RESOLUTION_DURATION_SECONDS,
    target.BRONZE_INGESTED_AT          = source.BRONZE_INGESTED_AT,
    target.PROCESSED_AT                = CURRENT_TIMESTAMP()
WHEN NOT MATCHED THEN INSERT (
    ID,
    CORRELATION_ID,
    TITLE,
    DESCRIPTION,
    REMEDIATION_PLAN,
    STATUS,
    CREATED_AT,
    UPDATED_AT,
    RESOLVED_AT,
    RESOLUTION_DURATION_SECONDS,
    BRONZE_INGESTED_AT,
    PROCESSED_AT
) VALUES (
    source.ID,
    source.CORRELATION_ID,
    source.TITLE,
    source.DESCRIPTION,
    source.REMEDIATION_PLAN,
    source.STATUS,
    source.CREATED_AT,
    source.UPDATED_AT,
    source.RESOLVED_AT,
    source.RESOLUTION_DURATION_SECONDS,
    source.BRONZE_INGESTED_AT,
    CURRENT_TIMESTAMP()
);

-- -----------------------------------------------------------------------------
-- Verification Queries (Phase 6)
-- -----------------------------------------------------------------------------
SHOW TASKS IN SCHEMA LOG_ANALYTICS_DB.SILVER;