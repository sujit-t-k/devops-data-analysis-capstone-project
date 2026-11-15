-- =============================================================================
-- Phase 2: Bronze Layer Table Creation
-- Purpose: Create raw append-only target tables for Snowpipe Streaming.
--          Retains unaltered source records, structured payloads, and
--          ingestion audit metadata.
-- =============================================================================

USE DATABASE LOG_ANALYTICS_DB;
USE SCHEMA BRONZE;
USE WAREHOUSE LOG_ANALYTICS_WH;

-- 1. BRONZE.LOG_RECORDS
-- Stores raw log stream events exactly as delivered by services/agents.
-- Timestamps are defined as TIMESTAMP_NTZ to represent wall-clock UTC without local conversions.
CREATE OR REPLACE TABLE BRONZE.LOG_RECORDS (
    -- Source fields
    ID                          VARCHAR(128)        NOT NULL COMMENT 'Unique log identifier from source',
    EVENT_TIMESTAMP             TIMESTAMP_NTZ       NOT NULL COMMENT 'Event generation timestamp in UTC',
    CORRELATION_ID              VARCHAR(128)        COMMENT 'Request correlation identifier across distributed calls',
    SERVICE_NAME                VARCHAR(100)        COMMENT 'Originating microservice or component name',
    LOG_SOURCE                  VARCHAR(50)         COMMENT 'Source runtime or subsystem (e.g. NGINX, SPRING_BOOT)',
    LOG_LEVEL                   VARCHAR(20)         COMMENT 'Log severity level (INFO, WARN, ERROR, DEBUG)',
    MESSAGE                     VARCHAR             COMMENT 'Raw log message string',
    LOGGER_NAME                 VARCHAR(255)        COMMENT 'Logger or class hierarchy name',
    THREAD_NAME                 VARCHAR(100)        COMMENT 'Worker thread name',
    OPERATION                   VARCHAR(100)        COMMENT 'Business operation name if present in top-level payload',
    HTTP_METHOD                 VARCHAR(10)         COMMENT 'HTTP request verb (GET, POST, PUT, etc.)',
    REQUEST_URI                 VARCHAR(4000)       COMMENT 'Target URI or endpoint requested',
    HTTP_STATUS                 NUMBER(5,0)         COMMENT 'HTTP response status code',
    REQUEST_TIME                NUMBER(12,4)        COMMENT 'Request processing duration in seconds/ms',
    UPSTREAM_RESPONSE_TIME      NUMBER(12,4)        COMMENT 'Upstream backend response duration',
    CLIENT_IP                   VARCHAR(45)         COMMENT 'Client IPv4/IPv6 address',
    USER_AGENT                  VARCHAR(1000)       COMMENT 'Client user agent string',
    RAW_LOG                     VARIANT             COMMENT 'Original unflattened JSON log payload',
    
    -- Ingestion audit metadata
    INGESTED_AT                 TIMESTAMP_NTZ       DEFAULT CURRENT_TIMESTAMP() COMMENT 'Timestamp when Snowflake received the record (UTC)',
    BATCH_ID                    VARCHAR(64)         COMMENT 'Client ingestion batch or channel transaction identifier',
    SOURCE_SYSTEM               VARCHAR(50)         DEFAULT 'SNOWPIPE_STREAMING' COMMENT 'Ingestion conduit or source identifier'
)
COMMENT = 'Bronze raw table storing faithful, unparsed application and proxy logs';

-- 2. BRONZE.INCIDENTS
-- Stores incident status updates and remediation tracking events.
CREATE OR REPLACE TABLE BRONZE.INCIDENTS (
    -- Source fields
    ID                          VARCHAR(128)        NOT NULL COMMENT 'Unique incident identifier from application',
    CORRELATION_ID              VARCHAR(128)        COMMENT 'Correlation ID linking incident to triggering log traces',
    TITLE                       VARCHAR(255)        COMMENT 'Incident headline or brief summary',
    DESCRIPTION                 VARCHAR             COMMENT 'Detailed incident symptoms or stack traces',
    REMEDIATION_PLAN            VARCHAR             COMMENT 'Mitigation instructions or automated remediation plan',
    STATUS                      VARCHAR(50)         NOT NULL COMMENT 'Incident lifecycle status',
    CREATED_AT                  TIMESTAMP_NTZ       NOT NULL COMMENT 'Incident creation timestamp in UTC',
    UPDATED_AT                  TIMESTAMP_NTZ       NOT NULL COMMENT 'Incident update timestamp in UTC',
    RESOLVED_AT                 TIMESTAMP_NTZ       COMMENT 'Resolution timestamp in UTC (nullable)',
    
    -- Ingestion audit metadata
    INGESTED_AT                 TIMESTAMP_NTZ       DEFAULT CURRENT_TIMESTAMP() COMMENT 'Timestamp when Snowflake received the record (UTC)',
    BATCH_ID                    VARCHAR(64)         COMMENT 'Client ingestion batch or channel transaction identifier',
    SOURCE_SYSTEM               VARCHAR(50)         DEFAULT 'SNOWPIPE_STREAMING' COMMENT 'Ingestion conduit or source identifier'
)
COMMENT = 'Bronze raw table storing operational incident events and status lifecycle updates';

-- Remove DEFAULT constraints from BRONZE.LOG_RECORDS
ALTER TABLE LOG_ANALYTICS_DB.BRONZE.LOG_RECORDS ALTER COLUMN INGESTED_AT DROP DEFAULT;
ALTER TABLE LOG_ANALYTICS_DB.BRONZE.LOG_RECORDS ALTER COLUMN SOURCE_SYSTEM DROP DEFAULT;

-- Remove DEFAULT constraints from BRONZE.INCIDENTS
ALTER TABLE LOG_ANALYTICS_DB.BRONZE.INCIDENTS ALTER COLUMN INGESTED_AT DROP DEFAULT;
ALTER TABLE LOG_ANALYTICS_DB.BRONZE.INCIDENTS ALTER COLUMN SOURCE_SYSTEM DROP DEFAULT;

-- -----------------------------------------------------------------------------
-- Verification Queries (Phase 2)
-- -----------------------------------------------------------------------------

SHOW TABLES IN SCHEMA LOG_ANALYTICS_DB.BRONZE;
DESCRIBE TABLE BRONZE.LOG_RECORDS;
DESCRIBE TABLE BRONZE.INCIDENTS;

SELECT 
    'Log Records' AS TABLE_NAME,
    COUNT(*) AS COUNT_ROWS FROM BRONZE.LOG_RECORDS
    UNION ALL
SELECT 
    'Incidents' AS TABLE_NAME,
    COUNT(*) AS COUNT_ROWS FROM BRONZE.INCIDENTS;