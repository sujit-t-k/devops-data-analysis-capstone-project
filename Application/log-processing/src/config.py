import os
import urllib.parse
from dataclasses import dataclass
from pathlib import Path
from dotenv import load_dotenv

# Base directory of the project
BASE_DIR = Path(__file__).resolve().parent.parent

# Load environment configuration files
MYSQL_CONFIG_FILE = BASE_DIR / "mysql-config.env"
SOURCE_CONFIG_FILE = BASE_DIR / "source-config.env"

if MYSQL_CONFIG_FILE.exists():
    load_dotenv(dotenv_path=MYSQL_CONFIG_FILE, override=False)
else:
    load_dotenv(override=False)

if SOURCE_CONFIG_FILE.exists():
    load_dotenv(dotenv_path=SOURCE_CONFIG_FILE, override=False)
else:
    load_dotenv(override=False)


@dataclass(frozen=True)
class DatabaseConfig:
    host: str
    port: int
    name: str
    username: str
    password: str
    table_name: str = "log_records"

    @property
    def connection_url(self) -> str:
        """
        Construct standard TCP/IP SQLAlchemy database URL using PyMySQL driver.
        Handles special characters in passwords via URL quoting.
        Works seamlessly for both local development and Docker container networks.
        """
        encoded_password = urllib.parse.quote_plus(self.password)
        encoded_username = urllib.parse.quote_plus(self.username)
        return (
            f"mysql+pymysql://{encoded_username}:{encoded_password}"
            f"@{self.host}:{self.port}/{self.name}?charset=utf8mb4"
        )


@dataclass(frozen=True)
class SourceConfig:
    source_file: Path
    batch_size: int


def load_database_config() -> DatabaseConfig:
    """Load and validate MySQL database configuration from environment."""
    host = os.getenv("DB_HOST", "localhost").strip()
    port_str = os.getenv("DB_PORT", "3306").strip()
    name = os.getenv("DB_NAME", "aji_log_platform").strip()
    username = os.getenv("DB_USERNAME", "root").strip()
    password = os.getenv("DB_PASSWORD", "").strip()
    table_name = os.getenv("DB_TABLE", "log_records").strip() or "log_records"

    try:
        port = int(port_str)
    except ValueError:
        raise ValueError(f"Invalid DB_PORT value: {port_str}. Must be an integer.")

    if not name:
        raise ValueError("DB_NAME must not be empty.")
    if not username:
        raise ValueError("DB_USERNAME must not be empty.")

    return DatabaseConfig(
        host=host,
        port=port,
        name=name,
        username=username,
        password=password,
        table_name=table_name,
    )


@dataclass(frozen=True)
class HttpServerConfig:
    host: str = "0.0.0.0"
    port: int = 8081


def load_http_server_config() -> HttpServerConfig:
    """Load HTTP server host and port from environment with defaults."""
    host = os.getenv("HTTP_HOST", "0.0.0.0").strip() or "0.0.0.0"
    port_str = os.getenv("HTTP_PORT", "8081").strip()
    try:
        port = int(port_str)
    except ValueError:
        port = 8081
    return HttpServerConfig(host=host, port=port)


def load_source_config() -> SourceConfig:
    """Load and validate source log file configuration from environment."""
    source_file_str = os.getenv("SOURCE_FILE", "data/fluent-bit-combined.jsonl").strip()
    batch_size_str = os.getenv("BATCH_SIZE", "500").strip()

    source_path = Path(source_file_str)
    if not source_path.is_absolute():
        source_path = BASE_DIR / source_path

    try:
        batch_size = int(batch_size_str)
        if batch_size <= 0:
            batch_size = 500
    except ValueError:
        batch_size = 500

    return SourceConfig(
        source_file=source_path,
        batch_size=batch_size,
    )

