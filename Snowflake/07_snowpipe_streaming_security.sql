-- =============================================================================
-- Phase 7: Snowpipe Streaming Infrastructure & Security Configuration
-- Purpose: Configure custom roles, grants, and security privileges for
--          Snowpipe Streaming (High-Performance Architecture) and Task execution.
--
-- Architectural Note on Ingestion:
-- - Snowpipe Streaming bypasses file staging, external stages, storage integrations,
--   and manual pipe DDL. Instead, the Snowflake Ingestion SDK opens streaming
--   channels directly into Snowflake table micro-partitions over gRPC/HTTPS.
-- - Snowflake automatically manages internal default streaming pipes behind the
--   scenes (associated with table metadata). No CREATE STAGE, CREATE FILE FORMAT,
--   or CREATE PIPE statements are required or recommended.
-- =============================================================================

USE ROLE ACCOUNTADMIN;

-- -----------------------------------------------------------------------------
-- 1. Create Dedicated Pipeline Roles
-- -----------------------------------------------------------------------------

-- Role for ingestion service / SDK streaming client
CREATE OR REPLACE ROLE LOG_INGEST_ROLE
    COMMENT = 'Role restricted to streaming row data into Bronze tables via Snowpipe Streaming';

-- Role for Task execution and transformation processing
CREATE OR REPLACE ROLE LOG_TRANSFORM_ROLE
    COMMENT = 'Role owning and executing Bronze-to-Silver transformation tasks and streams';

-- Grant roles to SYSADMIN to maintain standard role hierarchy
GRANT ROLE LOG_INGEST_ROLE TO ROLE SYSADMIN;
GRANT ROLE LOG_TRANSFORM_ROLE TO ROLE SYSADMIN;

-- Grant EXECUTE TASK privilege at the account level to the transformation role
GRANT EXECUTE TASK ON ACCOUNT TO ROLE LOG_TRANSFORM_ROLE;

-- -----------------------------------------------------------------------------
-- 2. Grant Permissions for LOG_INGEST_ROLE (Snowpipe Streaming Ingest Client)
-- Ingest client only requires:
--   - USAGE on database and BRONZE schema
--   - INSERT on BRONZE target tables
--   - USAGE on the virtual warehouse (if client authentication or channel registration queries run)
-- -----------------------------------------------------------------------------
GRANT USAGE ON WAREHOUSE LOG_ANALYTICS_WH TO ROLE LOG_INGEST_ROLE;
GRANT USAGE ON DATABASE LOG_ANALYTICS_DB TO ROLE LOG_INGEST_ROLE;
GRANT USAGE ON SCHEMA LOG_ANALYTICS_DB.BRONZE TO ROLE LOG_INGEST_ROLE;

-- Minimal privilege: INSERT on Bronze tables (no access to Silver, Gold, or Tasks)
GRANT INSERT, SELECT ON TABLE LOG_ANALYTICS_DB.BRONZE.LOG_RECORDS TO ROLE LOG_INGEST_ROLE;
GRANT INSERT, SELECT ON TABLE LOG_ANALYTICS_DB.BRONZE.INCIDENTS TO ROLE LOG_INGEST_ROLE;

-- -----------------------------------------------------------------------------
-- 3. Grant Permissions for LOG_TRANSFORM_ROLE (Task & Stream Pipeline Owner)
-- Transformation role requires:
--   - USAGE on warehouse
--   - USAGE on database and all schemas (BRONZE, SILVER, GOLD)
--   - SELECT on Bronze tables and streams
--   - SELECT, INSERT, UPDATE on Silver tables
--   - SELECT on Gold views
-- -----------------------------------------------------------------------------
GRANT USAGE ON WAREHOUSE LOG_ANALYTICS_WH TO ROLE LOG_TRANSFORM_ROLE;
GRANT USAGE ON DATABASE LOG_ANALYTICS_DB TO ROLE LOG_TRANSFORM_ROLE;
GRANT USAGE ON SCHEMA LOG_ANALYTICS_DB.BRONZE TO ROLE LOG_TRANSFORM_ROLE;
GRANT USAGE ON SCHEMA LOG_ANALYTICS_DB.SILVER TO ROLE LOG_TRANSFORM_ROLE;
GRANT USAGE ON SCHEMA LOG_ANALYTICS_DB.GOLD TO ROLE LOG_TRANSFORM_ROLE;

-- Table and Stream Privileges
GRANT SELECT ON TABLE LOG_ANALYTICS_DB.BRONZE.LOG_RECORDS TO ROLE LOG_TRANSFORM_ROLE;
GRANT SELECT ON TABLE LOG_ANALYTICS_DB.BRONZE.INCIDENTS TO ROLE LOG_TRANSFORM_ROLE;
GRANT SELECT ON STREAM LOG_ANALYTICS_DB.BRONZE.LOG_RECORDS_STREAM TO ROLE LOG_TRANSFORM_ROLE;
GRANT SELECT ON STREAM LOG_ANALYTICS_DB.BRONZE.INCIDENTS_STREAM TO ROLE LOG_TRANSFORM_ROLE;

GRANT SELECT, INSERT, UPDATE ON TABLE LOG_ANALYTICS_DB.SILVER.LOG_RECORDS TO ROLE LOG_TRANSFORM_ROLE;
GRANT SELECT, INSERT, UPDATE ON TABLE LOG_ANALYTICS_DB.SILVER.INCIDENTS TO ROLE LOG_TRANSFORM_ROLE;

GRANT SELECT ON ALL VIEWS IN SCHEMA LOG_ANALYTICS_DB.GOLD TO ROLE LOG_TRANSFORM_ROLE;
GRANT SELECT ON FUTURE VIEWS IN SCHEMA LOG_ANALYTICS_DB.GOLD TO ROLE LOG_TRANSFORM_ROLE;

-- Task ownership and execution permissions
-- Keep ownership with SYSADMIN for effortless development & evaluation,
-- while granting full execution privileges to LOG_TRANSFORM_ROLE.
GRANT OWNERSHIP ON TASK LOG_ANALYTICS_DB.SILVER.TSK_PIPELINE_ROOT_ORCHESTRATOR TO ROLE SYSADMIN REVOKE CURRENT GRANTS;
GRANT OWNERSHIP ON TASK LOG_ANALYTICS_DB.SILVER.TSK_TRANSFORM_SILVER_LOG_RECORDS TO ROLE SYSADMIN REVOKE CURRENT GRANTS;
GRANT OWNERSHIP ON TASK LOG_ANALYTICS_DB.SILVER.TSK_TRANSFORM_SILVER_INCIDENTS TO ROLE SYSADMIN REVOKE CURRENT GRANTS;

GRANT ALL ON TASK LOG_ANALYTICS_DB.SILVER.TSK_PIPELINE_ROOT_ORCHESTRATOR TO ROLE LOG_TRANSFORM_ROLE;
GRANT ALL ON TASK LOG_ANALYTICS_DB.SILVER.TSK_TRANSFORM_SILVER_LOG_RECORDS TO ROLE LOG_TRANSFORM_ROLE;
GRANT ALL ON TASK LOG_ANALYTICS_DB.SILVER.TSK_TRANSFORM_SILVER_INCIDENTS TO ROLE LOG_TRANSFORM_ROLE;

-- -----------------------------------------------------------------------------
-- 4. Optional User Assignment
-- NOTE: For single-user development/evaluation on a Snowflake trial, you do NOT
-- need to grant roles to your user. SYSADMIN already inherits all privileges from
-- LOG_INGEST_ROLE and LOG_TRANSFORM_ROLE (via lines 30-31), so you can run the
-- entire pipeline and verification directly using SYSADMIN.
--
-- The grant below is only needed in multi-user production environments where an
-- external service account or dedicated ingestion user is configured.
-- -----------------------------------------------------------------------------
-- USE ROLE SECURITYADMIN;
-- GRANT ROLE LOG_INGEST_ROLE TO USER <YOUR_USERNAME>;
-- GRANT ROLE LOG_TRANSFORM_ROLE TO USER <YOUR_USERNAME>;

-- -----------------------------------------------------------------------------
-- Verification Queries (Phase 7)
-- -----------------------------------------------------------------------------
SHOW GRANTS TO ROLE LOG_INGEST_ROLE;
SHOW GRANTS TO ROLE LOG_TRANSFORM_ROLE;
SHOW GRANTS ON TABLE LOG_ANALYTICS_DB.BRONZE.LOG_RECORDS;
SHOW GRANTS ON TASK LOG_ANALYTICS_DB.SILVER.TSK_PIPELINE_ROOT_ORCHESTRATOR;