-- =====================================================================
-- Database and Table Creation Script for Normalized Log Platform
-- =====================================================================

CREATE DATABASE IF NOT EXISTS aji_log_platform;

USE aji_log_platform;

CREATE TABLE IF NOT EXISTS log_records (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,

    event_timestamp DATETIME(6) NOT NULL,
    correlation_id VARCHAR(128) NULL,

    service_name VARCHAR(100) NULL,
    log_source VARCHAR(30) NOT NULL,
    log_level VARCHAR(20) NULL,

    message TEXT NULL,
    logger_name VARCHAR(255) NULL,
    thread_name VARCHAR(255) NULL,
    operation VARCHAR(100) NULL,

    http_method VARCHAR(10) NULL,
    request_uri TEXT NULL,
    http_status SMALLINT UNSIGNED NULL,

    request_time DECIMAL(12, 6) NULL,
    upstream_response_time DECIMAL(12, 6) NULL,

    client_ip VARCHAR(45) NULL,
    user_agent TEXT NULL,

    raw_log JSON NOT NULL,

    PRIMARY KEY (id),

    INDEX idx_correlation_id (correlation_id),
    INDEX idx_event_timestamp (event_timestamp),
    INDEX idx_log_source (log_source),
    INDEX idx_correlation_timestamp (correlation_id, event_timestamp)
);
