-- =============================================================================
-- Phase 3: Silver Layer Table Creation
-- Purpose: Schema definitions for cleaned, standardized, validated, and
--          deduplicated operational datasets.
-- =============================================================================

USE DATABASE LOG_ANALYTICS_DB;
USE SCHEMA SILVER;
USE WAREHOUSE LOG_ANALYTICS_WH;

-- 1. SILVER.LOG_RECORDS
-- Cleaned application and proxy logs:
-- - Filtered to exclude root NGINX health-check traffic (LOG_SOURCE = 'NGINX' AND REQUEST_URI = '/')
-- - Standardized UPPER(TRIM(LOG_LEVEL)) and UPPER(TRIM(LOG_SOURCE))
-- - OPERATION parsed and backfilled from RAW_LOG:operation if absent
-- - Deduplicated by primary log record ID
CREATE OR REPLACE TABLE SILVER.LOG_RECORDS (
    ID                          VARCHAR(128)        NOT NULL,
    EVENT_TIMESTAMP             TIMESTAMP_NTZ       NOT NULL,
    CORRELATION_ID              VARCHAR(128),
    SERVICE_NAME                VARCHAR(100),
    LOG_SOURCE                  VARCHAR(50),
    LOG_LEVEL                   VARCHAR(20),
    MESSAGE                     VARCHAR,
    LOGGER_NAME                 VARCHAR(255),
    THREAD_NAME                 VARCHAR(100),
    OPERATION                   VARCHAR(100),
    HTTP_METHOD                 VARCHAR(10),
    REQUEST_URI                 VARCHAR(4000),
    HTTP_STATUS                 NUMBER(5,0),
    REQUEST_TIME                NUMBER(12,4),
    UPSTREAM_RESPONSE_TIME      NUMBER(12,4),
    CLIENT_IP                   VARCHAR(45),
    USER_AGENT                  VARCHAR(1000),
    RAW_LOG                     VARIANT,
    
    -- Processing lineage tracking
    BRONZE_INGESTED_AT          TIMESTAMP_NTZ,
    PROCESSED_AT                TIMESTAMP_NTZ       DEFAULT CURRENT_TIMESTAMP(),
    
    CONSTRAINT PK_SILVER_LOG_RECORDS PRIMARY KEY (ID)
)
COMMENT = 'Silver cleaned, deduplicated, and validated operational log records';

-- 2. SILVER.INCIDENTS
-- Cleaned incident tracking records:
-- - Standardized statuses: INCIDENT_IDENTIFIED, REMEDIATION_DEFINED, REMEDIATION_IN_PROGRESS, RESOLVED
-- - Preserves nullable RESOLVED_AT
-- - Derived metric: RESOLUTION_DURATION_SECONDS (computed when status is RESOLVED and dates are valid)
-- - Deduplicated by incident ID (retaining latest state by UPDATED_AT)
CREATE OR REPLACE TABLE SILVER.INCIDENTS (
    ID                          VARCHAR(128)        NOT NULL,
    CORRELATION_ID              VARCHAR(128),
    TITLE                       VARCHAR(255),
    DESCRIPTION                 VARCHAR,
    REMEDIATION_PLAN            VARCHAR,
    STATUS                      VARCHAR(50)         NOT NULL,
    CREATED_AT                  TIMESTAMP_NTZ       NOT NULL,
    UPDATED_AT                  TIMESTAMP_NTZ       NOT NULL,
    RESOLVED_AT                 TIMESTAMP_NTZ,
    RESOLUTION_DURATION_SECONDS NUMBER(12,0),
    
    -- Processing lineage tracking
    BRONZE_INGESTED_AT          TIMESTAMP_NTZ,
    PROCESSED_AT                TIMESTAMP_NTZ       DEFAULT CURRENT_TIMESTAMP(),
    
    CONSTRAINT PK_SILVER_INCIDENTS PRIMARY KEY (ID)
)
COMMENT = 'Silver deduplicated and standardized incident records with derived resolution duration';

-- -----------------------------------------------------------------------------
-- Verification Queries (Phase 3)
-- -----------------------------------------------------------------------------

SHOW TABLES IN SCHEMA LOG_ANALYTICS_DB.SILVER;
DESCRIBE TABLE SILVER.LOG_RECORDS;
DESCRIBE TABLE SILVER.INCIDENTS;
