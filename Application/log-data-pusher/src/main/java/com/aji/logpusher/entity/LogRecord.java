package com.aji.logpusher.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "log_records")
@Immutable
@Getter
@Setter
@NoArgsConstructor
public class LogRecord {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "event_timestamp", nullable = false)
    private LocalDateTime eventTimestamp;

    @Column(name = "correlation_id", length = 128)
    private String correlationId;

    @Column(name = "service_name", length = 100)
    private String serviceName;

    @Column(name = "log_source", length = 30, nullable = false)
    private String logSource;

    @Column(name = "log_level", length = 20)
    private String logLevel;

    @Column(name = "message", columnDefinition = "TEXT")
    private String message;

    @Column(name = "logger_name", length = 255)
    private String loggerName;

    @Column(name = "thread_name", length = 255)
    private String threadName;

    @Column(name = "operation", length = 100)
    private String operation;

    @Column(name = "http_method", length = 10)
    private String httpMethod;

    @Column(name = "request_uri", columnDefinition = "TEXT")
    private String requestUri;

    @Column(name = "http_status")
    private Integer httpStatus;

    @Column(name = "request_time", precision = 12, scale = 6)
    private BigDecimal requestTime;

    @Column(name = "upstream_response_time", precision = 12, scale = 6)
    private BigDecimal upstreamResponseTime;

    @Column(name = "client_ip", length = 45)
    private String clientIp;

    @Column(name = "user_agent", columnDefinition = "TEXT")
    private String userAgent;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_log", columnDefinition = "json", nullable = false)
    private String rawLog;
}

