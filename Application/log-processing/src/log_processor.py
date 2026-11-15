import json
import logging
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any, Dict, Optional, Tuple, List, Iterable

from src.log_reader import RawLogEntry

logger = logging.getLogger(__name__)

# Constants for log sources
SOURCE_SPRING_BOOT = "SPRING_BOOT"
SOURCE_NGINX = "NGINX"


def parse_iso_timestamp(timestamp_val: Any) -> datetime:
    """
    Parse an ISO-8601 timestamp string supporting:
    - UTC timestamps ending in 'Z' (e.g. 2026-11-12T10:01:00.599Z)
    - Timezone offsets (e.g. 2026-11-12T10:01:00+00:00)
    Converts and returns a naive datetime in UTC suitable for MySQL DATETIME(6).
    """
    if not isinstance(timestamp_val, str) or not timestamp_val.strip():
        raise ValueError("Timestamp is missing or not a non-empty string")

    cleaned = timestamp_val.strip()
    try:
        dt = datetime.fromisoformat(cleaned)
    except ValueError as exc:
        raise ValueError(f"Invalid ISO-8601 timestamp '{cleaned}': {exc}")

    # Normalize to UTC
    if dt.tzinfo is not None:
        dt_utc = dt.astimezone(timezone.utc)
    else:
        # If no timezone is specified, assume UTC
        dt_utc = dt.replace(tzinfo=timezone.utc)

    # Return naive datetime representing UTC for MySQL DATETIME column
    return dt_utc.replace(tzinfo=None)


def identify_source(record: Dict[str, Any]) -> str:
    """
    Identify the log source as SPRING_BOOT or NGINX.
    Returns: 'SPRING_BOOT' or 'NGINX'.
    Raises ValueError if unsupported or ambiguous.
    """
    is_nginx = (
        record.get("source") == "nginx"
        or ("client_ip" in record and "method" in record and "request_uri" in record)
    )
    is_spring = (
        "logger_name" in record
        or "thread" in record
        or "level" in record
        or record.get("service") == "aji-orders-app"
    )

    if is_nginx and is_spring:
        raise ValueError("Ambiguous record: matches both NGINX and SPRING_BOOT signatures")
    if is_nginx:
        return SOURCE_NGINX
    if is_spring:
        return SOURCE_SPRING_BOOT

    raise ValueError(f"Unsupported log record format: unrecognized fields {list(record.keys())}")


def validate_and_normalize_record(
    record: Dict[str, Any], line_number: int
) -> Tuple[Optional[str], Optional[Dict[str, Any]], Optional[str]]:
    """
    Validates and normalizes a log record dictionary.
    Returns:
        (log_source, normalized_dict, error_message)
        If valid: (source, normalized_dict, None)
        If invalid: (source_or_None, None, error_message)
    """
    try:
        source = identify_source(record)
    except ValueError as exc:
        return None, None, f"Line {line_number}: {exc}"

    try:
        # 1. Timestamp validation (required for both sources)
        raw_ts = record.get("timestamp")
        if not raw_ts:
            return source, None, f"Line {line_number}: Missing required 'timestamp' field"
        event_timestamp = parse_iso_timestamp(raw_ts)

        # 2. Correlation ID validation
        # Missing correlation IDs must be stored as SQL NULL (None)
        # Preserve correlation IDs exactly as received; never generate one.
        correlation_id = record.get("correlation_id")
        if correlation_id is not None:
            if not isinstance(correlation_id, str) or not correlation_id.strip():
                return source, None, f"Line {line_number}: 'correlation_id' must be a non-empty string if provided"
            correlation_id = correlation_id.strip()

        # 3. Source-specific validation and normalization
        if source == SOURCE_SPRING_BOOT:
            message = record.get("message")
            if message is None or not str(message).strip():
                return source, None, f"Line {line_number}: Missing required 'message' in SPRING_BOOT log"

            service_name = record.get("service") or "aji-orders-app"
            log_level = record.get("level")
            logger_name = record.get("logger_name")
            thread_name = record.get("thread")
            operation = record.get("operation")

            normalized = {
                "event_timestamp": event_timestamp,
                "correlation_id": correlation_id,
                "service_name": service_name,
                "log_source": SOURCE_SPRING_BOOT,
                "log_level": str(log_level) if log_level is not None else None,
                "message": str(message),
                "logger_name": str(logger_name) if logger_name is not None else None,
                "thread_name": str(thread_name) if thread_name is not None else None,
                "operation": str(operation) if operation is not None else None,
                "http_method": None,
                "request_uri": None,
                "http_status": None,
                "request_time": None,
                "upstream_response_time": None,
                "client_ip": None,
                "user_agent": None,
                "raw_log": record,
            }
            return source, normalized, None

        elif source == SOURCE_NGINX:
            # Validate required NGINX fields
            method = record.get("method")
            if not method or not isinstance(method, str):
                return source, None, f"Line {line_number}: Missing or invalid 'method' in NGINX log"

            request_uri = record.get("request_uri")
            if not request_uri or not isinstance(request_uri, str):
                return source, None, f"Line {line_number}: Missing or invalid 'request_uri' in NGINX log"

            # Validate HTTP status
            raw_status = record.get("status")
            if raw_status is None:
                return source, None, f"Line {line_number}: Missing required 'status' in NGINX log"
            try:
                http_status = int(raw_status)
                if not (100 <= http_status <= 599):
                    return source, None, f"Line {line_number}: Invalid HTTP status code: {http_status}"
            except (ValueError, TypeError):
                return source, None, f"Line {line_number}: 'status' must be an integer, got '{raw_status}'"

            # Validate request_time duration
            raw_req_time = record.get("request_time")
            request_time = None
            if raw_req_time is not None:
                try:
                    request_time = float(raw_req_time)
                    if request_time < 0.0:
                        return source, None, f"Line {line_number}: 'request_time' cannot be negative ({request_time})"
                except (ValueError, TypeError):
                    return source, None, f"Line {line_number}: 'request_time' must be a valid float"

            # Validate upstream_response_time
            raw_upstream = record.get("upstream_response_time")
            upstream_response_time = None
            if raw_upstream is not None and str(raw_upstream).strip() not in ("", "-"):
                try:
                    upstream_response_time = float(raw_upstream)
                    if upstream_response_time < 0.0:
                        return source, None, f"Line {line_number}: 'upstream_response_time' cannot be negative ({upstream_response_time})"
                except (ValueError, TypeError):
                    return source, None, f"Line {line_number}: 'upstream_response_time' must be a valid float"

            # Service name for Nginx
            service_name = record.get("service") or record.get("source") or "nginx"

            # Message: Application message or meaningful HTTP request summary
            explicit_msg = record.get("message")
            if explicit_msg:
                message = str(explicit_msg)
            else:
                message = f"{method} {request_uri} {http_status}"

            client_ip = record.get("client_ip")
            user_agent = record.get("user_agent")

            normalized = {
                "event_timestamp": event_timestamp,
                "correlation_id": correlation_id,
                "service_name": service_name,
                "log_source": SOURCE_NGINX,
                "log_level": None,
                "message": message,
                "logger_name": None,
                "thread_name": None,
                "operation": None,
                "http_method": method,
                "request_uri": request_uri,
                "http_status": http_status,
                "request_time": request_time,
                "upstream_response_time": upstream_response_time,
                "client_ip": str(client_ip) if client_ip is not None else None,
                "user_agent": str(user_agent) if user_agent is not None else None,
                "raw_log": record,
            }
            return source, normalized, None

    except Exception as exc:
        return source, None, f"Line {line_number}: Unexpected validation error: {exc}"


@dataclass
class BatchProcessResult:
    received: int
    normalized_records: List[Dict[str, Any]]
    rejections: List[Dict[str, Any]]
    spring_boot_count: int
    nginx_count: int


def process_entries(entries: Iterable[RawLogEntry]) -> BatchProcessResult:
    """
    Process an iterable of RawLogEntry records using the core validation and normalization logic.
    Tracks received counts, identified sources, normalized payloads, and rejections.
    """
    normalized_records: List[Dict[str, Any]] = []
    rejections: List[Dict[str, Any]] = []
    spring_count = 0
    nginx_count = 0
    received_count = 0

    for entry in entries:
        received_count += 1
        if entry.error:
            rejections.append({"line": entry.line_number, "error": entry.error})
            continue

        if entry.record is None:
            rejections.append({
                "line": entry.line_number,
                "error": f"Line {entry.line_number}: Record is null",
            })
            continue

        source, normalized, err = validate_and_normalize_record(entry.record, entry.line_number)
        if err or normalized is None:
            rejections.append({
                "line": entry.line_number,
                "error": err or f"Line {entry.line_number}: Record validation failed",
            })
            continue

        if source == SOURCE_SPRING_BOOT:
            spring_count += 1
        elif source == SOURCE_NGINX:
            nginx_count += 1

        normalized_records.append(normalized)

    return BatchProcessResult(
        received=received_count,
        normalized_records=normalized_records,
        rejections=rejections,
        spring_boot_count=spring_count,
        nginx_count=nginx_count,
    )


