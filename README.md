# DevOps Capstone Project: LogPulse Platform

LogPulse is an end-to-end distributed observability, log investigation, and analytical platform deployed across AWS EC2 instances, Snowflake Cloud Data Warehouse, and Power BI.
The system captures end-to-end request traces across microservices with distributed correlation IDs, streams structured logs in real time via Fluent Bit and Python FastAPI into an incident management platform, and synchronizes telemetry into Snowflake using Snowpipe Streaming.
Through automated Medallion CDC pipelines (Bronze -> Silver -> Gold), data is continuously transformed to empower real-time system diagnostics, incident remediation, and interactive Power BI operational dashboards.

## Architecture Diagram

```mermaid
flowchart TD
    subgraph ClientLayer ["1. Edge / Client Interaction"]
        Client["Client / Postman"]
    end

    subgraph OrderEC2 ["2. aji-orders-ec2 (Elastic IP: 13.61.41.57)"]
        Nginx["Nginx Reverse Proxy (:80)\n- Maps/Generates X-Correlation-ID\n- Emits access.jsonl"]
        OrdersApp["Orders Spring Boot Service (:8080)\n- MDC Correlation Propagation\n- Business Operations & Sensitive Masking\n- Emits orders-app.jsonl"]
        OrderDB[("Orders MySQL 8.4\n- Internal / Non-Public\n- Stores Products, Orders, Items")]
        FluentBit["Fluent Bit Collector v5.1.5\n- Tails access.jsonl & orders-app.jsonl\n- In-memory DB offsets\n- HTTP Output to Python Collector"]
    end

    subgraph LogPlatformEC2 ["3. aji-log-investigation-ec2 (Elastic IP: 13.61.239.115)"]
        PyCollector["Python FastAPI Collector (:8081)\n- Normalizes Nginx & Spring Boot schemas\n- Enforces UTC timestamps\n- Persists to log_records"]
        LogDB[("Log Platform MySQL 8.4\n- log_records & incidents tables")]
        BackendAPI["Log Investigation Spring Boot API (:8080)\n- Exposes logs, timelines, & incident lifecycle"]
        ReactUI["React + Vite Frontend (:3000 / served via Nginx)\n- Incident timeline reconstruction & remediation tracking"]
        DataPusher["Log Data Pusher Spring Boot Exporter\n- Scheduled every 5 mins\n- Snowpipe Streaming High-Performance Ingestion"]
    end

    subgraph SnowflakeCloud ["4. Snowflake Cloud Data Warehouse (LOG_ANALYTICS_DB)"]
        Bronze["BRONZE Layer (Append-Only Raw)\n- BRONZE.LOG_RECORDS & BRONZE.INCIDENTS"]
        Streams["Snowflake CDC Streams\n- LOG_RECORDS_STREAM & INCIDENTS_STREAM"]
        Tasks["Automated Tasks & DAG Orchestration\n- Root Heartbeat (2-min) -> Merge into Silver"]
        Silver["SILVER Layer (Cleaned & Standardized)\n- Deduplication, filter health probes, duration derivation"]
        Gold["GOLD Layer (Reporting Views)\n- Zero-lag analytical models & KPIs"]
    end

    subgraph AnalyticsLayer ["5. Business Intelligence"]
        PowerBI["Power BI Desktop (LogPulse_Analytics.pbix)\n- Direct Snowflake Import\n- Application Health & Incident Analytics Dashboards"]
    end

    Client -->|HTTP Request| Nginx
    Nginx -->|Reverse Proxy with X-Correlation-ID| OrdersApp
    OrdersApp --> OrderDB
    Nginx -.->|access.jsonl| FluentBit
    OrdersApp -.->|orders-app.jsonl| FluentBit
    FluentBit -->|HTTP POST /ingest via VPC Private IP 172.31.16.112:8081| PyCollector
    PyCollector --> LogDB
    LogDB <--> BackendAPI
    BackendAPI <--> ReactUI
    LogDB --> DataPusher
    DataPusher -->|Snowpipe Streaming RSA Key-Pair Auth| Bronze
    Bronze --> Streams
    Streams --> Tasks
    Tasks --> Silver
    Silver --> Gold
    Gold -->|Import Mode| PowerBI
```

## Documentation & Repository Structure

### Documentation
- **`Documentation/Raw`**: Contains raw documentation made during the development, testing, and deployment phases.
- **`Documentation`**: Contains refined documentation with improved phrasing, structure, and comprehensive technical guides.

### Application
- **`Application`**: Contains the application source code.
  - Aside from this repository, two applications are maintained in an external GitHub repository:
    - 🔗 **[log-investigation-app](https://github.com/sujit-t-k/log-investigation-app)** (contains `aji-log-investigation-frontend` and `aji-log-investigation-backend`).

### Snowflake
- **`Snowflake`**: Contains Snowflake queries in order to create a database structure and automated pipelines that clean and transform data through a multi-tier Medallion architecture (Bronze -> Silver -> Gold).

### Power BI
- **`Power BI`**: Contains the `.pbix` analytical file (`LogPulse_Analytics.pbix`).
- **`Power BI/dashboard illustrations`**: Contains dashboard preview illustrations and exported report visualizations.