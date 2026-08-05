"""Small, curated reference catalog of well-known, standardized OBD-II DTC meanings.

This is generic background knowledge only -- "what does this code generically mean" --
never a source of severity, guidance, or action. For a DTC the vehicle is CURRENTLY
reporting, its own active-DTC state (app/api/schemas/mobile.py's Dtc.severity/title,
trusted the same way SafetyRiskEngine already trusts it) is always the authoritative
source, regardless of whether this catalog also happens to cover the code -- live vehicle
state is a stronger signal than generic reference data for "what is happening right now."
This catalog exists only to answer "what does code X mean" for a code the vehicle is NOT
currently reporting.

Deliberately small and limited to codes with a single, universally standardized meaning
under SAE J2012 / ISO 15031-6 (powertrain "P" and network "U" codes). Body ("B") and
chassis ("C") codes are intentionally NOT included: their meaning is largely
manufacturer-specific, and a generic entry for one would risk being wrong for a
specific vehicle -- better to honestly say "not in the catalog" than to guess.
"""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True, slots=True)
class DtcCatalogEntry:
    code: str
    meaning: str


_ENTRIES = (
    DtcCatalogEntry(
        "P0128",
        "Nhiệt độ nước làm mát thấp hơn ngưỡng vận hành của van hằng nhiệt -- van hằng nhiệt "
        "có thể bị kẹt mở hoặc hỏng.",
    ),
    DtcCatalogEntry(
        "P0171",
        "Hệ thống nhiên liệu quá nghèo (Bank 1) -- động cơ nhận quá nhiều không khí hoặc quá "
        "ít nhiên liệu so với tính toán.",
    ),
    DtcCatalogEntry(
        "P0300",
        "Phát hiện bỏ máy ngẫu nhiên hoặc nhiều xi-lanh -- một hoặc nhiều xi-lanh không đốt "
        "cháy đúng cách.",
    ),
    DtcCatalogEntry("P0301", "Phát hiện bỏ máy ở xi-lanh số 1."),
    DtcCatalogEntry(
        "P0420",
        "Hiệu suất hệ thống xúc tác dưới ngưỡng cho phép (Bank 1) -- bộ chuyển đổi xúc tác có "
        "thể đã suy giảm hiệu quả.",
    ),
    DtcCatalogEntry(
        "U0100",
        "Mất giao tiếp với mô-đun điều khiển động cơ hoặc hệ truyền động (ECM/PCM).",
    ),
    DtcCatalogEntry("U0101", "Mất giao tiếp với mô-đun điều khiển hộp số (TCM)."),
)

DTC_CATALOG: dict[str, DtcCatalogEntry] = {entry.code: entry for entry in _ENTRIES}


def lookup(code: str) -> DtcCatalogEntry | None:
    return DTC_CATALOG.get(code.upper())
