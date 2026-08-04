import re
import tempfile
from collections import Counter
from dataclasses import dataclass
from pathlib import Path

import pytest

PROJECT_ROOT = Path(__file__).resolve().parents[1]
REFERENCE_MASTER_PLAN_PATH = (
    PROJECT_ROOT
    / "docs"
    / "reference"
    / "SafeDrive_AI_Backend_Master_Implementation_Plan_V2_CLAUDE.md"
)
MATRIX_PATH = PROJECT_ROOT / "docs" / "task_status_matrix.md"

VALID_STATUSES = {"DONE", "PARTIAL", "NOT_STARTED", "BLOCKED"}


@dataclass(frozen=True, slots=True)
class MatrixRow:
    task_id: str
    task_name: str
    status: str
    evidence: str
    line_number: int


def parse_matrix_content(content: str) -> list[MatrixRow]:
    lines = content.splitlines()
    rows: list[MatrixRow] = []
    in_table = False

    for line_no, line in enumerate(lines, start=1):
        stripped = line.strip()
        if not stripped.startswith("|"):
            if in_table:
                # End of table block reached
                break
            continue

        parts = [p.strip() for p in stripped.split("|")[1:-1]]

        # Skip header row and header separator row
        if len(parts) >= 1 and (parts[0] in ("Task ID", "---") or parts[0].startswith("---")):
            in_table = True
            continue

        if not in_table:
            continue

        # Strict validation: every data row must have exactly 4 columns
        if len(parts) != 4:
            raise ValueError(
                f"Line {line_no}: Malformed table row (expected 4 columns, got {len(parts)}): '{line}'"
            )

        task_id, task_name, status, evidence = parts

        if not re.fullmatch(r"^SD-\d{4}$", task_id):
            raise ValueError(f"Line {line_no}: Invalid task_id format '{task_id}'")

        if status not in VALID_STATUSES:
            raise ValueError(f"Line {line_no}: Invalid status '{status}' for task '{task_id}'")

        rows.append(MatrixRow(task_id, task_name, status, evidence, line_no))

    return rows


def test_task_status_matrix_self_contained_and_exact_match(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    original_cwd = Path.cwd()
    with tempfile.TemporaryDirectory(dir=PROJECT_ROOT) as temp_dir:
        monkeypatch.chdir(temp_dir)

        assert REFERENCE_MASTER_PLAN_PATH.exists(), (
            f"Reference master plan missing at {REFERENCE_MASTER_PLAN_PATH}"
        )
        assert MATRIX_PATH.exists(), f"Task status matrix missing at {MATRIX_PATH}"

        with open(REFERENCE_MASTER_PLAN_PATH, "r", encoding="utf-8") as f:
            master_text = f.read()

        expected_matches = re.findall(r"###\s+(SD-\d{4})\s+—\s+(.+)", master_text)
        assert len(expected_matches) == 52, (
            f"Expected 52 tasks in Master Plan, found {len(expected_matches)}"
        )

        expected_tasks = {task_id: name.strip() for task_id, name in expected_matches}
        assert len(expected_tasks) == 52, "Duplicate task IDs found in reference Master Plan"

        with open(MATRIX_PATH, "r", encoding="utf-8") as f:
            matrix_text = f.read()

        rows = parse_matrix_content(matrix_text)

        # 1. Total row count check
        assert len(rows) == 52, f"Expected 52 task rows in matrix, found {len(rows)}"

        # 2. Duplicate ID detection using Counter
        ids = [row.task_id for row in rows]
        duplicates = {task_id: count for task_id, count in Counter(ids).items() if count > 1}
        assert not duplicates, f"Duplicate task IDs found in matrix: {duplicates}"

        # 3. Missing and extra ID detection
        assert set(ids) == set(expected_tasks.keys()), (
            f"Matrix task IDs do not match Master Plan. "
            f"Missing: {set(expected_tasks.keys()) - set(ids)}, "
            f"Extra: {set(ids) - set(expected_tasks.keys())}"
        )

        # 4. Verbatim task name validation
        for row in rows:
            expected_name = expected_tasks[row.task_id]
            assert row.task_name == expected_name, (
                f"Line {row.line_number} task_id {row.task_id} name mismatch: "
                f"got '{row.task_name}', expected '{expected_name}'"
            )

        monkeypatch.chdir(original_cwd)


def test_matrix_parser_rejects_malformed_rows() -> None:
    # 1. Invalid Task ID format
    malformed_id_matrix = """
| Task ID | Task Name | Status | Evidence / Notes |
| --- | --- | --- | --- |
| BAD-ID | Invalid task | DONE | notes |
"""
    with pytest.raises(ValueError, match="Invalid task_id format"):
        parse_matrix_content(malformed_id_matrix)

    # 2. Missing columns
    missing_cols_matrix = """
| Task ID | Task Name | Status | Evidence / Notes |
| --- | --- | --- | --- |
| SD-0001 | Missing columns |
"""
    with pytest.raises(ValueError, match="Malformed table row"):
        parse_matrix_content(missing_cols_matrix)

    # 3. Invalid Status
    invalid_status_matrix = """
| Task ID | Task Name | Status | Evidence / Notes |
| --- | --- | --- | --- |
| SD-0001 | Chốt capability profile | INVALID_STATUS | notes |
"""
    with pytest.raises(ValueError, match="Invalid status"):
        parse_matrix_content(invalid_status_matrix)
