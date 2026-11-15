-- =============================================================================
-- Phase 1: Database, Warehouse, and Schema Creation
-- Purpose: Provision foundational Snowflake compute, storage, and schemas
--          configured for a cost-conscious development/evaluation tier.
-- =============================================================================

USE ROLE SYSADMIN;

-- 1. Create a cost-conscious virtual warehouse
-- Size: X-SMALL (1 credit/hour when active)
-- Auto-suspend: 60 seconds (minimizes idle credit burn on trial account)
-- Auto-resume: TRUE (spins up automatically when tasks or queries execute)
CREATE OR REPLACE WAREHOUSE LOG_ANALYTICS_WH
    WITH 
    WAREHOUSE_SIZE = 'X-SMALL'
    AUTO_SUSPEND = 60
    AUTO_RESUME = TRUE
    INITIALLY_SUSPENDED = TRUE
    COMMENT = 'Development warehouse for log analytics ingestion, transformations, and querying';

-- 2. Create the primary database
CREATE OR REPLACE DATABASE LOG_ANALYTICS_DB
    COMMENT = 'Central database for log and incident analytics platform';

-- Timezone Note:
-- In Snowflake, TIMEZONE is a session/account parameter, not a database/schema parameter.
-- Because all tables in Bronze, Silver, and Gold use TIMESTAMP_NTZ (Timestamp No Time Zone),
-- timestamps are stored and queried exactly as ingested (UTC) with zero automatic offset shifts.
-- Downstream transformations and queries always treat these timestamps as native UTC.

USE DATABASE LOG_ANALYTICS_DB;
USE WAREHOUSE LOG_ANALYTICS_WH;

-- 3. Create Medallion Architecture Schemas
-- BRONZE: Append-only raw ingested log data
CREATE OR REPLACE SCHEMA BRONZE
    COMMENT = 'Raw, faithful ingestion layer for log records and incident events';

-- SILVER: Cleaned, normalized, and deduplicated operational data
CREATE OR REPLACE SCHEMA SILVER
    COMMENT = 'Cleaned, standardized, validated, and deduplicated dataset layer';

-- GOLD: Analytics-ready semantic views and aggregations
CREATE OR REPLACE SCHEMA GOLD
    COMMENT = 'Analytics-ready reporting views and key performance indicators';

-- -----------------------------------------------------------------------------
-- Verification Queries (Phase 1)
-- -----------------------------------------------------------------------------
SHOW WAREHOUSES LIKE 'LOG_ANALYTICS_WH';
SHOW SCHEMAS IN DATABASE LOG_ANALYTICS_DB;
SELECT CURRENT_WAREHOUSE(), CURRENT_DATABASE(), CURRENT_SCHEMA(), CURRENT_TIMESTAMP();