-- =============================================================================
-- Phase 8: Task Activation, Verification, and Pipeline Monitoring
-- Purpose: Activate the automated Task DAG, simulate end-to-end streaming data,
--          verify Bronze-to-Silver transformations and Gold metric views, and
--          provide operational troubleshooting and auditing queries.
-- =============================================================================

USE ROLE SYSADMIN;
USE DATABASE LOG_ANALYTICS_DB;
USE SCHEMA SILVER;
USE WAREHOUSE LOG_ANALYTICS_WH;

-- -----------------------------------------------------------------------------
-- 1. Resume Tasks (Strict Dependency Order)
-- CRITICAL SNOWFLAKE RULE:
-- In a task graph (DAG), child tasks MUST be resumed BEFORE the root task is resumed.
-- If you resume the root task first, Snowflake will not execute child tasks that
-- remain in the 'suspended' state.
-- -----------------------------------------------------------------------------

-- Step 1: Resume all child tasks first
ALTER TASK SILVER.TSK_TRANSFORM_SILVER_LOG_RECORDS RESUME;
ALTER TASK SILVER.TSK_TRANSFORM_SILVER_INCIDENTS RESUME;

-- Step 2: Resume the root task last (starts the 2-minute recurring heartbeat)
ALTER TASK SILVER.TSK_PIPELINE_ROOT_ORCHESTRATOR RESUME;

-- -----------------------------------------------------------------------------
-- 2. End-to-End Simulation: Insert Test Records into Bronze
-- We insert:
--  - Normal application log records
--  - Duplicate log record (same ID) to test Silver deduplication
--  - NGINX record with REQUEST_URI = '/' to test root health-check exclusion
--  - Account update operation to test metric 13
--  - Incident records (one open, one resolved) to test MTTR derivation
-- -----------------------------------------------------------------------------

USE ROLE SYSADMIN;

-- A. Ingest sample logs into BRONZE.LOG_RECORDS
-- In Snowflake, functions like PARSE_JSON() must be evaluated in a SELECT query rather than VALUES()
INSERT INTO LOG_ANALYTICS_DB.BRONZE.LOG_RECORDS (
    ID, EVENT_TIMESTAMP, CORRELATION_ID, SERVICE_NAME, LOG_SOURCE, LOG_LEVEL, 
    MESSAGE, LOGGER_NAME, THREAD_NAME, OPERATION, HTTP_METHOD, REQUEST_URI, 
    HTTP_STATUS, REQUEST_TIME, UPSTREAM_RESPONSE_TIME, CLIENT_IP, USER_AGENT, 
    RAW_LOG, INGESTED_AT, BATCH_ID, SOURCE_SYSTEM
)
SELECT
    'LOG-001', '2026-11-14 10:00:00'::TIMESTAMP_NTZ, 'CORR-AAA-111', 'order-service', 'SPRING_BOOT', 'INFO', 
    'Order lookup initialized', 'com.app.OrderController', 'http-nio-8080-exec-1', 'FETCH_ORDER', 
    'GET', '/api/v1/orders/1234', 200, 0.045, 0.035, '192.168.1.10', 'Mozilla/5.0', 
    PARSE_JSON('{"operation": "FETCH_ORDER", "details": "order retrieved successfully"}'), CURRENT_TIMESTAMP(), 'BATCH-001', 'SNOWPIPE_STREAMING'
UNION ALL
SELECT
    'LOG-002', '2026-11-14 10:05:10'::TIMESTAMP_NTZ, 'CORR-AAA-111', 'order-service', 'SPRING_BOOT', 'ERROR', 
    'Database timeout during order status update', 'com.app.OrderRepo', 'http-nio-8080-exec-2', 'UPDATE_ORDER', 
    'POST', '/api/v1/orders/1234/status', 504, 2.500, 2.480, '192.168.1.10', 'Mozilla/5.0', 
    PARSE_JSON('{"operation": "UPDATE_ORDER", "error_code": "DB_TIMEOUT_504"}'), CURRENT_TIMESTAMP(), 'BATCH-001', 'SNOWPIPE_STREAMING'
UNION ALL
SELECT
    'LOG-003', '2026-11-14 10:15:20'::TIMESTAMP_NTZ, 'CORR-BBB-222', 'auth-service', 'SPRING_BOOT', 'WARN', 
    'JWT token approaching expiration window', 'com.app.JwtFilter', 'http-nio-8080-exec-5', 'AUTHENTICATE', 
    'POST', '/api/v1/auth/verify', 200, 0.012, 0.008, '10.0.0.5', 'MobileClient/2.1', 
    PARSE_JSON('{"operation": "AUTHENTICATE"}'), CURRENT_TIMESTAMP(), 'BATCH-001', 'SNOWPIPE_STREAMING'
UNION ALL
SELECT
    'LOG-004', '2026-11-14 10:30:00'::TIMESTAMP_NTZ, 'CORR-CCC-333', 'user-service', 'SPRING_BOOT', 'INFO', 
    'User profile email updated', 'com.app.UserController', 'http-nio-8080-exec-7', 'UPDATING_ACCOUNT_INFORMATION', 
    'PUT', '/api/v1/users/profile', 200, 0.085, 0.070, '172.16.0.22', 'Mozilla/5.0', 
    PARSE_JSON('{"operation": "UPDATING_ACCOUNT_INFORMATION", "fields_changed": ["email"]}'), CURRENT_TIMESTAMP(), 'BATCH-002', 'SNOWPIPE_STREAMING'
UNION ALL
SELECT
    'LOG-004', '2026-11-14 10:30:00'::TIMESTAMP_NTZ, 'CORR-CCC-333', 'user-service', 'SPRING_BOOT', 'INFO', 
    'User profile email updated', 'com.app.UserController', 'http-nio-8080-exec-7', 'UPDATING_ACCOUNT_INFORMATION', 
    'PUT', '/api/v1/users/profile', 200, 0.085, 0.070, '172.16.0.22', 'Mozilla/5.0', 
    PARSE_JSON('{"operation": "UPDATING_ACCOUNT_INFORMATION", "fields_changed": ["email"]}'), CURRENT_TIMESTAMP(), 'BATCH-002', 'SNOWPIPE_STREAMING'
UNION ALL
SELECT
    'LOG-005', '2026-11-14 10:45:00'::TIMESTAMP_NTZ, 'CORR-DDD-444', 'gateway-nginx', 'NGINX', 'INFO', 
    'Health check probe', 'nginx.access', 'worker-1', 'PROBE', 
    'GET', '/', 200, 0.001, 0.001, '127.0.0.1', 'kube-probe/1.28', 
    PARSE_JSON('{}'), CURRENT_TIMESTAMP(), 'BATCH-002', 'SNOWPIPE_STREAMING'
UNION ALL
SELECT
    'LOG-006', '2026-11-14 10:46:00'::TIMESTAMP_NTZ, 'CORR-EEE-555', 'gateway-nginx', 'NGINX', 'INFO', 
    'Reverse proxy forward', 'nginx.access', 'worker-2', 'PROXY', 
    'GET', '/api/v1/catalog/items', 200, 0.020, 0.018, '10.244.0.1', 'Mozilla/5.0', 
    PARSE_JSON('{}'), CURRENT_TIMESTAMP(), 'BATCH-002', 'SNOWPIPE_STREAMING';

-- B. Ingest sample incidents into BRONZE.INCIDENTS
INSERT INTO LOG_ANALYTICS_DB.BRONZE.INCIDENTS (
    ID, CORRELATION_ID, TITLE, DESCRIPTION, REMEDIATION_PLAN, STATUS, 
    CREATED_AT, UPDATED_AT, RESOLVED_AT, INGESTED_AT, BATCH_ID, SOURCE_SYSTEM
)
SELECT
    'INC-001', 'CORR-AAA-111', 'Database connection pool exhausted', 
    'Backend services experiencing intermittent connection timeouts', 
    'Scaled connection pool min-idle to 20 connections', 'RESOLVED', 
    '2026-11-14 10:05:00'::TIMESTAMP_NTZ, '2026-11-14 10:20:00'::TIMESTAMP_NTZ, '2026-11-14 10:20:00'::TIMESTAMP_NTZ, CURRENT_TIMESTAMP(), 'BATCH-001', 'SNOWPIPE_STREAMING'
UNION ALL
SELECT
    'INC-002', 'CORR-CCC-333', 'Rate limit spikes detected on auth endpoints', 
    'Abnormal authorization request frequency detected from subnet 172.16.0.0/24', 
    'Investigating IP throttle policies', 'REMEDIATION_IN_PROGRESS', 
    '2026-11-14 10:35:00'::TIMESTAMP_NTZ, '2026-11-14 10:40:00'::TIMESTAMP_NTZ, NULL, CURRENT_TIMESTAMP(), 'BATCH-002', 'SNOWPIPE_STREAMING';

-- -----------------------------------------------------------------------------
-- 3. Trigger Immediate Pipeline Execution (Optional fast-forward)
-- Rather than waiting for the 2-minute schedule, manually execute the root task.
-- -----------------------------------------------------------------------------
USE ROLE SYSADMIN;

-- Verify streams detect the newly inserted rows
SELECT 
    SYSTEM$STREAM_HAS_DATA('LOG_ANALYTICS_DB.BRONZE.LOG_RECORDS_STREAM') AS LOGS_HAS_DATA,
    SYSTEM$STREAM_HAS_DATA('LOG_ANALYTICS_DB.BRONZE.INCIDENTS_STREAM') AS INCIDENTS_HAS_DATA;


-- Manually fire the root orchestrator task (downstream child tasks execute automatically)
EXECUTE TASK SILVER.TSK_PIPELINE_ROOT_ORCHESTRATOR;

-- -----------------------------------------------------------------------------
-- 4. Verification: Inspect Data Movement & Integrity
-- -----------------------------------------------------------------------------

-- Confirm Bronze retains all rows (including duplicate and NGINX '/' probe)
SELECT 'BRONZE.LOG_RECORDS' AS TABLE_NAME, COUNT(*) AS TOTAL_COUNT FROM LOG_ANALYTICS_DB.BRONZE.LOG_RECORDS
UNION ALL
SELECT 'BRONZE.INCIDENTS', COUNT(*) FROM LOG_ANALYTICS_DB.BRONZE.INCIDENTS;

-- Confirm Silver filtered NGINX '/' and deduplicated LOG-004
SELECT 
    ID, CORRELATION_ID, SERVICE_NAME, LOG_SOURCE, LOG_LEVEL, REQUEST_URI, OPERATION, PROCESSED_AT
FROM LOG_ANALYTICS_DB.SILVER.LOG_RECORDS
ORDER BY ID;

-- Verify NGINX '/' exclusion: This query should return 0 rows in Silver
SELECT COUNT(*) AS EXCLUDED_NGINX_ROOT_COUNT
FROM LOG_ANALYTICS_DB.SILVER.LOG_RECORDS
WHERE UPPER(LOG_SOURCE) = 'NGINX' AND REQUEST_URI = '/';

-- Confirm Silver Incidents derived MTTR duration (INC-001 should have 900 seconds)
SELECT 
    ID, CORRELATION_ID, TITLE, STATUS, CREATED_AT, RESOLVED_AT, RESOLUTION_DURATION_SECONDS
FROM LOG_ANALYTICS_DB.SILVER.INCIDENTS;

-- -----------------------------------------------------------------------------
-- 5. Query Gold Analytics Views
-- -----------------------------------------------------------------------------
-- Metric 1, 2, 4, 5, 7: Executive log summary
SELECT * FROM LOG_ANALYTICS_DB.GOLD.VW_LOG_EXECUTIVE_METRICS;

-- Metric 3: 3-Hour UTC interval traffic distribution
SELECT * FROM LOG_ANALYTICS_DB.GOLD.VW_REQUEST_TRAFFIC_3HOUR_INTERVALS;

-- Metric 6: Error distribution by service and source
SELECT * FROM LOG_ANALYTICS_DB.GOLD.VW_ERROR_DISTRIBUTION_BY_SERVICE_SOURCE;

-- Metric 13: Account modification activity
SELECT * FROM LOG_ANALYTICS_DB.GOLD.VW_ACCOUNT_MODIFICATION_ACTIVITY;

-- Metric 8, 9: Incident MTTR summary
SELECT * FROM LOG_ANALYTICS_DB.GOLD.VW_INCIDENT_SUMMARY_MTTR;

-- Metric 10: Incident status distribution
SELECT * FROM LOG_ANALYTICS_DB.GOLD.VW_INCIDENT_STATUS_DISTRIBUTION;

-- Metric 11: Incident daily trends
SELECT * FROM LOG_ANALYTICS_DB.GOLD.VW_INCIDENT_TRENDS_DAILY;

-- Metric 12: Incident attribution to service & source via correlation ID
SELECT * FROM LOG_ANALYTICS_DB.GOLD.VW_INCIDENT_SERVICE_SOURCE_ATTRIBUTION;

-- -----------------------------------------------------------------------------
-- 6. Operational Monitoring & Diagnostics Queries
-- -----------------------------------------------------------------------------

-- A. Inspect Task Execution State
SHOW TASKS IN DATABASE LOG_ANALYTICS_DB;

-- B. Task Run History, Schedules, and Error Diagnostics (Last 1 hour)
SELECT 
    NAME,
    DATABASE_NAME,
    SCHEMA_NAME,
    STATE,
    ERROR_CODE,
    ERROR_MESSAGE,
    SCHEDULED_TIME,
    QUERY_START_TIME,
    COMPLETED_TIME,
    DATEDIFF('millisecond', QUERY_START_TIME, COMPLETED_TIME) AS EXECUTION_TIME_MS
FROM TABLE(INFORMATION_SCHEMA.TASK_HISTORY(
    SCHEDULED_TIME_RANGE_START => DATEADD('hour', -1, CURRENT_TIMESTAMP()),
    RESULT_LIMIT => 50
))
ORDER BY SCHEDULED_TIME DESC;

-- C. Check Stream Status and Offsets
SHOW STREAMS IN DATABASE LOG_ANALYTICS_DB;

-- D. Inspect Internal Snowpipe Streaming Pipes (Created automatically by Snowflake)
SHOW PIPES IN DATABASE LOG_ANALYTICS_DB;

-- -----------------------------------------------------------------------------
-- 7. Suspension Procedure (To conserve trial credits when idle)
-- Suspend root task first, then child tasks.
-- -----------------------------------------------------------------------------
/*
ALTER TASK SILVER.TSK_PIPELINE_ROOT_ORCHESTRATOR SUSPEND;
ALTER TASK SILVER.TSK_TRANSFORM_SILVER_LOG_RECORDS SUSPEND;
ALTER TASK SILVER.TSK_TRANSFORM_SILVER_INCIDENTS SUSPEND;
ALTER WAREHOUSE LOG_ANALYTICS_WH SUSPEND;
*/