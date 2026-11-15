import json
import logging
from dataclasses import dataclass
from pathlib import Path
from typing import Generator, Optional, Any, Dict, List

logger = logging.getLogger(__name__)


@dataclass
class RawLogEntry:
    line_number: int
    raw_text: str
    record: Optional[Dict[str, Any]]
    error: Optional[str] = None


def parse_json_line(raw_line: str, line_number: int) -> Optional[RawLogEntry]:
    """
    Parse a single raw line into a RawLogEntry.
    Returns None if the line is empty or whitespace-only.
    Catches invalid JSON or non-dict structures and returns entry with error message.
    """
    stripped_line = raw_line.strip()
    if not stripped_line:
        return None

    try:
        parsed_data = json.loads(stripped_line)
        if not isinstance(parsed_data, dict):
            return RawLogEntry(
                line_number=line_number,
                raw_text=stripped_line,
                record=None,
                error=f"Line {line_number} is valid JSON but not a JSON object (type: {type(parsed_data).__name__})",
            )

        return RawLogEntry(
            line_number=line_number,
            raw_text=stripped_line,
            record=parsed_data,
            error=None,
        )
    except json.JSONDecodeError as exc:
        return RawLogEntry(
            line_number=line_number,
            raw_text=stripped_line,
            record=None,
            error=f"Malformed JSON on line {line_number}: {exc.msg} at pos {exc.pos}",
        )


def read_jsonl_file(file_path: Path) -> Generator[RawLogEntry, None, None]:
    """
    Read a JSON Lines file line by line without loading the entire file into memory.
    Parses each line as JSON and tracks 1-based line numbers.
    Gracefully handles malformed JSON lines by yielding RawLogEntry with error details.
    """
    if not file_path.exists():
        raise FileNotFoundError(f"Source JSONL file does not exist: {file_path}")

    with file_path.open("r", encoding="utf-8") as file:
        for line_number, raw_line in enumerate(file, start=1):
            entry = parse_json_line(raw_line, line_number)
            if entry is not None:
                yield entry


def parse_ndjson_text(text: str) -> List[RawLogEntry]:
    """
    Parse a newline-delimited JSON (NDJSON) string into a list of RawLogEntry items.
    Supports single or multiple newline-separated JSON records.
    Ignores empty lines and tracks 1-based line numbers for accurate error reporting.
    """
    entries: List[RawLogEntry] = []
    lines = text.splitlines()
    for line_number, line in enumerate(lines, start=1):
        entry = parse_json_line(line, line_number)
        if entry is not None:
            entries.append(entry)
    return entries
