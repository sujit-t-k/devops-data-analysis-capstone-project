# Python Log Processing Application

A lightweight, robust Python application that ingests mixed logs (Fluent Bit output containing Spring Boot and Nginx access logs), validates and normalizes them, and persists them into a local MySQL database using SQLAlchemy and PyMySQL over TCP/IP.

The application supports dual ingestion modes:
1. **File-based Streaming Job**: Reads large `.jsonl` files line-by-line without loading entire files into memory.
2. **HTTP NDJSON Ingestion API**: A FastAPI server listening on `0.0.0.0:8081` with endpoint `POST /ingest` supporting single or multi-line NDJSON payloads.

Both ingestion paths share the exact same validation, normalization, and database persistence pipeline.

---

## 1. Project Structure

```text
log-processing/
│
├── data/
│   └── fluent-bit-combined.jsonl   # Source JSON Lines log data
│
├── src/
│   ├── __init__.py
│   ├── config.py                  # Loads & validates environment variables
│   ├── log_reader.py              # Line-by-line streaming file & NDJSON string parser
│   ├── log_processor.py           # Core validation, source detection & UTC normalization
│   ├── database.py                # SQLAlchemy TCP/IP engine, log_records table & batch insert
│   ├── main.py                    # File-based CLI processing job & summary metrics
│   └── api.py                     # FastAPI HTTP NDJSON ingestion service (POST /ingest)
│
├── mysql-config.env               # MySQL connection settings (gitignored)
├── source-config.env              # File source & batch size settings (gitignored)
├── .env.example                   # Template configuration file
├── .gitignore                     # Git ignore rules
├── requirements.txt               # Dependencies (FastAPI, Uvicorn, SQLAlchemy, PyMySQL, python-dotenv)
├── schema.sql                     # MySQL DDL script for database and table setup
└── README.md                      # Instructions & documentation
```

---

## 2. Prerequisites & Setup

### A. Python Environment
Activate the virtual environment in PowerShell or CMD:

**PowerShell:**
```powershell
.\.venv\Scripts\Activate.ps1
```

**CMD:**
```cmd
.\.venv\Scripts\activate.bat
```

Install/verify dependencies:
```powershell
pip install -r requirements.txt
```

### B. MySQL Database Setup
In **MySQL Workbench** or MySQL CLI, execute the `schema.sql` script:

```sql
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
```

---

## 3. Configuration

Two separate environment configuration files are used:

### `mysql-config.env`
```properties
DB_HOST=localhost
DB_PORT=3306
DB_NAME=aji_log_platform
DB_USERNAME=root
DB_PASSWORD=12345678
DB_TABLE=log_records
```

### `source-config.env`
```properties
SOURCE_FILE=data/fluent-bit-combined.jsonl
BATCH_SIZE=500
HTTP_HOST=0.0.0.0
HTTP_PORT=8081
```

---

## 4. Running the HTTP Log Ingestion Service

Start the FastAPI ingestion server on `0.0.0.0:8081`:

```powershell
python src/api.py
```
*(Or alternatively: `uvicorn src.api:app --host 0.0.0.0 --port 8081`)*

### Endpoints
- `GET /health`: Health check and database connection status.
- `POST /ingest`: Accepts raw newline-delimited JSON (NDJSON) in the request body.

---

## 5. Postman Example: Sending Multiple NDJSON Records

Follow these steps in Postman to send multiple newline-separated JSON records in one HTTP request:

### Step 1: Request Setup
- **Method**: `POST`
- **URL**: `http://localhost:8081/ingest`

### Step 2: Headers
Under the **Headers** tab, ensure the following is configured:
- `Content-Type`: `application/x-ndjson` (or `text/plain`)

### Step 3: Body
1. Click the **Body** tab.
2. Select **raw**.
3. In the format dropdown on the right, select **JSON** (or **Text**).
4. Paste your newline-delimited JSON records (each record on a new line, **without** commas between lines, and **without** enclosing square brackets `[` `]`):

```text
{"timestamp":"2026-11-12T10:01:00Z","message":"Retrieving all products","level":"INFO","correlation_id":"abc123","service":"aji-orders-app"}
{"timestamp":"2026-11-12T10:01:01Z","message":"Product retrieved successfully","level":"INFO","correlation_id":"abc123","service":"aji-orders-app"}
{"timestamp":"2026-11-12T10:01:02Z","source":"nginx","client_ip":"14.99.219.164","method":"GET","request_uri":"/api/products","status":200,"correlation_id":"abc123"}
```

### Step 4: Send & View Response

#### 1. Full Success Response (`200 OK`)
```json
{
    "status": "success",
    "received": 3,
    "inserted": 3,
    "rejected": 0
}
```

#### 2. Partial Success Response (`200 OK`)
When some records are valid but others have malformed JSON or validation errors:
```json
{
    "status": "partial_success",
    "received": 3,
    "inserted": 2,
    "rejected": 1,
    "errors": [
        {
            "line": 2,
            "error": "Line 2: Missing required 'timestamp' field"
        }
    ]
}
```

#### 3. All Records Rejected (`422 Unprocessable Entity`)
```json
{
    "status": "failed",
    "received": 2,
    "inserted": 0,
    "rejected": 2,
    "errors": [
        {
            "line": 1,
            "error": "Malformed JSON on line 1: Expecting value at pos 0"
        },
        {
            "line": 2,
            "error": "Line 2: Missing required 'message' in SPRING_BOOT log"
        }
    ]
}
```

---

## 6. Running the File-Based Streaming Job

To process the local `data/fluent-bit-combined.jsonl` file as a standalone batch job:

```powershell
python src/main.py
```

### Expected Output
```text
2026-11-12 10:00:00 [INFO] log_processor_app: Starting Python Log Processing Application...
2026-11-12 10:00:00 [INFO] log_processor_app: Target Database: aji_log_platform at localhost:3306
2026-11-12 10:00:00 [INFO] log_processor_app: Target Table: log_records
2026-11-12 10:00:00 [INFO] log_processor_app: Source JSONL File: ...\data\fluent-bit-combined.jsonl
2026-11-12 10:00:00 [INFO] log_processor_app: Batch Size: 500
2026-11-12 10:00:00 [INFO] src.database: Database schema checked/initialized successfully for table 'log_records'.
2026-11-12 10:00:00 [INFO] log_processor_app: Inserted batch of 30 record(s) into database.

============================================================
                   PROCESSING SUMMARY
============================================================
Total lines read:                  30
Spring Boot records identified:    25
Nginx records identified:          5
Successfully inserted records:     30
Rejected records:                  0
Database insertion failures:       0
============================================================

2026-11-12 10:00:00 [INFO] log_processor_app: Log processing job completed.
```

---

## 7. Portability to EC2 / Docker Deployment

No code changes are required to deploy the application in an EC2 Docker container environment. Simply update `mysql-config.env`:

```properties
DB_HOST=aji-log-mysql-container
DB_PORT=3306
DB_NAME=aji_log_platform
DB_USERNAME=aji_log_user
DB_PASSWORD=<production-password>
DB_TABLE=log_records
```
