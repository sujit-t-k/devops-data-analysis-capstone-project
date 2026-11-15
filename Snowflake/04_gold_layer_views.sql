-- =============================================================================
-- Phase 4: Gold Layer Analytics Views Creation
-- Purpose: Expose business-ready metrics, KPI dashboards, and reporting models.
--          Implemented as standard SQL Views over the Silver layer to:
--          1. Guarantee zero data duplication and zero redundant storage cost.
--          2. Eliminate the overhead of complex, periodic aggregate-table tasks.
--          3. Reflect Silver data modifications instantaneously with zero lag.
-- =============================================================================

USE DATABASE LOG_ANALYTICS_DB;
USE SCHEMA GOLD;
USE WAREHOUSE LOG_ANALYTICS_WH;

-- -----------------------------------------------------------------------------
-- 1. VW_LOG_EXECUTIVE_METRICS
-- Metric Coverage:
--   - Metric 1: Total requests (distinct non-null correlation IDs + null correlation IDs)
--   - Metric 2: Total log records
--   - Metric 4: Error percentage
--   - Metric 5: Warning percentage
--   - Metric 7: INFO/WARN/ERROR distribution
-- -----------------------------------------------------------------------------
CREATE OR REPLACE VIEW GOLD.VW_LOG_EXECUTIVE_METRICS AS
SELECT
    COUNT(*) AS TOTAL_LOG_RECORDS,
    (COUNT(DISTINCT CORRELATION_ID) + COUNT(IFF(CORRELATION_ID IS NULL, 1, NULL))) AS TOTAL_REQUESTS,
    COUNT_IF(LOG_LEVEL = 'INFO') AS TOTAL_INFO_LOGS,
    COUNT_IF(LOG_LEVEL = 'WARN') AS TOTAL_WARN_LOGS,
    COUNT_IF(LOG_LEVEL = 'ERROR') AS TOTAL_ERROR_LOGS,
    COUNT_IF(LOG_LEVEL NOT IN ('INFO', 'WARN', 'ERROR')) AS TOTAL_OTHER_LOGS,
    ROUND(100.0 * COUNT_IF(LOG_LEVEL = 'ERROR') / NULLIF(COUNT(*), 0), 2) AS ERROR_PERCENTAGE,
    ROUND(100.0 * COUNT_IF(LOG_LEVEL = 'WARN') / NULLIF(COUNT(*), 0), 2) AS WARN_PERCENTAGE,
    ROUND(100.0 * COUNT_IF(LOG_LEVEL = 'INFO') / NULLIF(COUNT(*), 0), 2) AS INFO_PERCENTAGE
FROM SILVER.LOG_RECORDS;

-- -----------------------------------------------------------------------------
-- 2. VW_REQUEST_TRAFFIC_3HOUR_INTERVALS
-- Metric Coverage:
--   - Metric 3: Request count by 3-hour UTC intervals
-- -----------------------------------------------------------------------------
CREATE OR REPLACE VIEW GOLD.VW_REQUEST_TRAFFIC_3HOUR_INTERVALS AS
SELECT
    CAST(EVENT_TIMESTAMP AS DATE) AS EVENT_DATE_UTC,
    FLOOR(EXTRACT(HOUR FROM EVENT_TIMESTAMP) / 3) * 3 AS INTERVAL_START_HOUR,
    CASE FLOOR(EXTRACT(HOUR FROM EVENT_TIMESTAMP) / 3)
        WHEN 0 THEN '00:00–02:59'
        WHEN 1 THEN '03:00–05:59'
        WHEN 2 THEN '06:00–08:59'
        WHEN 3 THEN '09:00–11:59'
        WHEN 4 THEN '12:00–14:59'
        WHEN 5 THEN '15:00–17:59'
        WHEN 6 THEN '18:00–20:59'
        WHEN 7 THEN '21:00–23:59'
    END AS INTERVAL_WINDOW_UTC,
    (COUNT(DISTINCT CORRELATION_ID) + COUNT(IFF(CORRELATION_ID IS NULL, 1, NULL))) AS TOTAL_REQUESTS,
    COUNT(*) AS TOTAL_LOG_RECORDS,
    COUNT_IF(LOG_LEVEL = 'ERROR') AS ERROR_LOG_RECORDS
FROM SILVER.LOG_RECORDS
GROUP BY 
    CAST(EVENT_TIMESTAMP AS DATE),
    FLOOR(EXTRACT(HOUR FROM EVENT_TIMESTAMP) / 3);

-- -----------------------------------------------------------------------------
-- 3. VW_ERROR_DISTRIBUTION_BY_SERVICE_SOURCE
-- Metric Coverage:
--   - Metric 6: Error distribution by service and log source
-- -----------------------------------------------------------------------------
CREATE OR REPLACE VIEW GOLD.VW_ERROR_DISTRIBUTION_BY_SERVICE_SOURCE AS
SELECT
    COALESCE(SERVICE_NAME, 'UNKNOWN_SERVICE') AS SERVICE_NAME,
    COALESCE(LOG_SOURCE, 'UNKNOWN_SOURCE') AS LOG_SOURCE,
    COUNT_IF(LOG_LEVEL = 'ERROR') AS ERROR_COUNT,
    COUNT(*) AS TOTAL_LOGS,
    ROUND(100.0 * COUNT_IF(LOG_LEVEL = 'ERROR') / NULLIF(COUNT(*), 0), 2) AS ERROR_PERCENTAGE_WITHIN_SOURCE,
    ROUND(100.0 * COUNT_IF(LOG_LEVEL = 'ERROR') / NULLIF(SUM(COUNT_IF(LOG_LEVEL = 'ERROR')) OVER (), 0), 2) AS SHARE_OF_TOTAL_SYSTEM_ERRORS
FROM SILVER.LOG_RECORDS
GROUP BY SERVICE_NAME, LOG_SOURCE
ORDER BY ERROR_COUNT DESC;

-- -----------------------------------------------------------------------------
-- 4. VW_ACCOUNT_MODIFICATION_ACTIVITY
-- Metric Coverage:
--   - Metric 13: Account modification activity (OPERATION = 'UPDATING_ACCOUNT_INFORMATION')
--                Evaluated via distinct correlation IDs.
-- -----------------------------------------------------------------------------
CREATE OR REPLACE VIEW GOLD.VW_ACCOUNT_MODIFICATION_ACTIVITY AS
SELECT
    CAST(EVENT_TIMESTAMP AS DATE) AS ACTIVITY_DATE_UTC,
    COALESCE(SERVICE_NAME, 'UNKNOWN_SERVICE') AS SERVICE_NAME,
    COUNT(DISTINCT CORRELATION_ID) AS DISTINCT_ACCOUNT_UPDATE_REQUESTS,
    COUNT(*) AS TOTAL_AUDIT_LOG_ENTRIES,
    MIN(EVENT_TIMESTAMP) AS FIRST_SEEN_EVENT_UTC,
    MAX(EVENT_TIMESTAMP) AS LAST_SEEN_EVENT_UTC
FROM SILVER.LOG_RECORDS
WHERE UPPER(TRIM(OPERATION)) = 'UPDATING_ACCOUNT_INFORMATION'
   OR UPPER(TRIM(RAW_LOG:operation::VARCHAR)) = 'UPDATING_ACCOUNT_INFORMATION'
GROUP BY CAST(EVENT_TIMESTAMP AS DATE), SERVICE_NAME;

-- -----------------------------------------------------------------------------
-- 5. VW_INCIDENT_SUMMARY_MTTR
-- Metric Coverage:
--   - Metric 8: Mean Time To Resolve (MTTR) using resolved incidents only
--   - Metric 9: Total incidents
-- -----------------------------------------------------------------------------
CREATE OR REPLACE VIEW GOLD.VW_INCIDENT_SUMMARY_MTTR AS
SELECT
    COUNT(*) AS TOTAL_INCIDENTS,
    COUNT_IF(STATUS = 'RESOLVED') AS RESOLVED_INCIDENTS_COUNT,
    COUNT_IF(STATUS != 'RESOLVED') AS OPEN_OR_IN_PROGRESS_INCIDENTS_COUNT,
    ROUND(AVG(IFF(STATUS = 'RESOLVED', RESOLUTION_DURATION_SECONDS, NULL)), 2) AS MTTR_SECONDS,
    ROUND(AVG(IFF(STATUS = 'RESOLVED', RESOLUTION_DURATION_SECONDS, NULL)) / 60.0, 2) AS MTTR_MINUTES,
    ROUND(AVG(IFF(STATUS = 'RESOLVED', RESOLUTION_DURATION_SECONDS, NULL)) / 3600.0, 2) AS MTTR_HOURS,
    MIN(IFF(STATUS = 'RESOLVED', RESOLUTION_DURATION_SECONDS, NULL)) AS MIN_RESOLUTION_SECONDS,
    MAX(IFF(STATUS = 'RESOLVED', RESOLUTION_DURATION_SECONDS, NULL)) AS MAX_RESOLUTION_SECONDS
FROM SILVER.INCIDENTS;

-- -----------------------------------------------------------------------------
-- 6. VW_INCIDENT_STATUS_DISTRIBUTION
-- Metric Coverage:
--   - Metric 10: Incident status distribution
-- -----------------------------------------------------------------------------
CREATE OR REPLACE VIEW GOLD.VW_INCIDENT_STATUS_DISTRIBUTION AS
SELECT
    STATUS,
    COUNT(*) AS INCIDENT_COUNT,
    ROUND(100.0 * COUNT(*) / NULLIF(SUM(COUNT(*)) OVER (), 0), 2) AS STATUS_PERCENTAGE
FROM SILVER.INCIDENTS
GROUP BY STATUS
ORDER BY INCIDENT_COUNT DESC;

-- -----------------------------------------------------------------------------
-- 7. VW_INCIDENT_TRENDS_DAILY
-- Metric Coverage:
--   - Metric 11: Incident trends over time (Creation vs Resolution)
-- -----------------------------------------------------------------------------
CREATE OR REPLACE VIEW GOLD.VW_INCIDENT_TRENDS_DAILY AS
SELECT
    CAST(CREATED_AT AS DATE) AS INCIDENT_DATE_UTC,
    COUNT(*) AS NEW_INCIDENTS_CREATED,
    COUNT_IF(STATUS = 'RESOLVED') AS TOTAL_RESOLVED_TO_DATE,
    ROUND(AVG(IFF(STATUS = 'RESOLVED', RESOLUTION_DURATION_SECONDS, NULL)) / 60.0, 2) AS DAILY_AVG_MTTR_MINUTES
FROM SILVER.INCIDENTS
GROUP BY CAST(CREATED_AT AS DATE)
ORDER BY INCIDENT_DATE_UTC DESC;

-- -----------------------------------------------------------------------------
-- 8. VW_INCIDENT_SERVICE_SOURCE_ATTRIBUTION
-- Metric Coverage:
--   - Metric 12: Incident distribution by service and log source
--                Derives attribution strictly via matching CORRELATION_ID.
--                Does NOT invent mock services for unlinked incidents.
-- -----------------------------------------------------------------------------
CREATE OR REPLACE VIEW GOLD.VW_INCIDENT_SERVICE_SOURCE_ATTRIBUTION AS
SELECT
    COALESCE(l.SERVICE_NAME, 'UNATTRIBUTED_SERVICE') AS SERVICE_NAME,
    COALESCE(l.LOG_SOURCE, 'UNATTRIBUTED_SOURCE') AS LOG_SOURCE,
    COUNT(DISTINCT i.ID) AS ATTRIBUTED_INCIDENT_COUNT,
    COUNT(DISTINCT IFF(i.STATUS = 'RESOLVED', i.ID, NULL)) AS RESOLVED_INCIDENT_COUNT,
    ROUND(AVG(IFF(i.STATUS = 'RESOLVED', i.RESOLUTION_DURATION_SECONDS, NULL)) / 60.0, 2) AS MTTR_MINUTES
FROM SILVER.INCIDENTS i
LEFT JOIN (
    -- Subquery to avoid fan-out if multiple log records share the same correlation_id
    SELECT DISTINCT
        CORRELATION_ID,
        SERVICE_NAME,
        LOG_SOURCE
    FROM SILVER.LOG_RECORDS
    WHERE CORRELATION_ID IS NOT NULL
) l ON i.CORRELATION_ID = l.CORRELATION_ID
GROUP BY l.SERVICE_NAME, l.LOG_SOURCE
ORDER BY ATTRIBUTED_INCIDENT_COUNT DESC;

-- -----------------------------------------------------------------------------
-- Verification Queries (Phase 4)
-- -----------------------------------------------------------------------------

SHOW VIEWS IN SCHEMA LOG_ANALYTICS_DB.GOLD;
DESCRIBE VIEW GOLD.VW_LOG_EXECUTIVE_METRICS;
DESCRIBE VIEW GOLD.VW_REQUEST_TRAFFIC_3HOUR_INTERVALS;
DESCRIBE VIEW GOLD.VW_ERROR_DISTRIBUTION_BY_SERVICE_SOURCE;
DESCRIBE VIEW GOLD.VW_ACCOUNT_MODIFICATION_ACTIVITY;
DESCRIBE VIEW GOLD.VW_INCIDENT_SUMMARY_MTTR;
DESCRIBE VIEW GOLD.VW_INCIDENT_STATUS_DISTRIBUTION;
DESCRIBE VIEW GOLD.VW_INCIDENT_TRENDS_DAILY;
DESCRIBE VIEW GOLD.VW_INCIDENT_SERVICE_SOURCE_ATTRIBUTION;