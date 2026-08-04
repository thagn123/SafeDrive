"""Deterministic Safety Core for the mobile compatibility path.

Rules are intentionally ordered, explicit, and fully testable. This module
does not call an LLM and does not perform tool execution or emergency dispatch.
"""

from __future__ import annotations

from dataclasses import dataclass

from app.api.schemas.mobile import RestRecommendation, RiskAssessment
from app.mobile.context import ContextSnapshot


@dataclass(frozen=True, slots=True)
class SafetyEvidence:
    code: str
    label: str


@dataclass(frozen=True, slots=True)
class SafetyEvaluation:
    risk: RiskAssessment
    rest: RestRecommendation
    evidence: tuple[SafetyEvidence, ...]
    allowed_action_types: tuple[str, ...]
    emergency_candidate: bool


class SafetyRiskEngine:
    """A small policy engine for the MVP's demonstrated safety scenarios."""

    HOT_CABIN_C = 31.0
    LONG_DRIVE_MINUTES = 240
    # Confirmed thresholds, not invented here: coolant runs 90-95C normally, 105C is the
    # point a driver should start easing off, 115C risks real engine damage if ignored.
    ENGINE_WARNING_C = 105.0
    ENGINE_CRITICAL_C = 115.0

    def evaluate(self, snapshot: ContextSnapshot, *, now_ms: int) -> SafetyEvaluation:
        state = snapshot.state
        support = snapshot.driver_support
        evidence = self._base_evidence(snapshot)

        # No automatic emergency escalation from stale or invalid state. The
        # assistant may still request human verification, but it must not claim
        # that stale data proves a current emergency.
        if not snapshot.state_is_fresh:
            return self._evaluation(
                level="MEDIUM",
                title="Cần làm mới dữ liệu an toàn",
                message="Trạng thái xe mới nhất chưa đủ mới để đưa ra kết luận an toàn. Hãy làm mới các tín hiệu cockpit.",
                reasons=["vehicle_state_not_fresh"],
                evidence=evidence,
                rest_level="INSUFFICIENT_DATA",
                rest_title="Thiếu dữ liệu hiện tại",
                rest_message="Cần cập nhật trạng thái xe mới trước khi đưa ra khuyến nghị nghỉ ngơi.",
                confidence="LOW",
                now_ms=now_ms,
                allowed=("SHOW_WARNING",),
                emergency_candidate=False,
            )

        if state.crashDetected and state.passengerResponse == "NO_RESPONSE":
            return self._evaluation(
                level="CRITICAL",
                title="Sự kiện an toàn nghiêm trọng",
                message="Tín hiệu va chạm và không phản hồi yêu cầu kiểm tra ngay tình trạng người trong xe.",
                reasons=["crash_detected", "occupant_no_response"],
                evidence=evidence,
                rest_level="INSUFFICIENT_DATA",
                rest_title="Đang xác minh tình huống khẩn cấp",
                rest_message="Chưa thể đưa ra khuyến nghị nghỉ trong khi quy trình khẩn cấp đang xác minh an toàn.",
                confidence="HIGH",
                now_ms=now_ms,
                allowed=("SHOW_WARNING", "START_SOS_COUNTDOWN"),
                emergency_candidate=True,
            )

        if state.crashDetected:
            return self._evaluation(
                level="HIGH",
                title="Đã phát hiện tín hiệu va chạm",
                message="Hệ thống đã nhận tín hiệu va chạm. Hãy kiểm tra người trong xe và tình trạng phương tiện.",
                reasons=["crash_detected"],
                evidence=evidence,
                rest_level="INSUFFICIENT_DATA",
                rest_title="Cần kiểm tra an toàn",
                rest_message="Hệ thống cần phản hồi từ người trong xe trước khi đưa ra khuyến nghị lái xe thông thường.",
                confidence="HIGH",
                now_ms=now_ms,
                allowed=("SHOW_WARNING",),
                emergency_candidate=False,
            )

        if state.engineTemperatureC >= self.ENGINE_CRITICAL_C:
            return self._evaluation(
                level="CRITICAL",
                title="Động cơ quá nhiệt nghiêm trọng",
                message=(
                    f"Nhiệt độ động cơ đã đạt {state.engineTemperatureC:g} độ C, vượt ngưỡng nguy hiểm. "
                    "Hãy tấp vào lề và tắt máy ngay lập tức để tránh hỏng động cơ."
                ),
                reasons=["engine_overheat_critical"],
                evidence=evidence,
                rest_level="NORMAL",
                rest_title="Chưa có khuyến nghị nghỉ do mệt mỏi",
                rest_message="Hãy xử lý cảnh báo quá nhiệt động cơ trước khi tiếp tục lái xe.",
                confidence="HIGH",
                now_ms=now_ms,
                allowed=("SHOW_WARNING", "SUGGEST_REST_STOP"),
                emergency_candidate=False,
            )

        if state.engineTemperatureC >= self.ENGINE_WARNING_C:
            return self._evaluation(
                level="HIGH",
                title="Nhiệt độ động cơ đang tăng cao",
                message=(
                    f"Nhiệt độ động cơ đang ở {state.engineTemperatureC:g} độ C. Hãy giảm tốc, tắt điều hòa "
                    "nếu cần và tìm vị trí an toàn để kiểm tra."
                ),
                reasons=["engine_overheat_warning"],
                evidence=evidence,
                rest_level="NORMAL",
                rest_title="Chưa có khuyến nghị nghỉ do mệt mỏi",
                rest_message="Hãy xử lý cảnh báo nhiệt độ động cơ trước khi tiếp tục lái xe.",
                confidence="HIGH",
                now_ms=now_ms,
                allowed=("SHOW_WARNING", "SUGGEST_REST_STOP"),
                emergency_candidate=False,
            )

        high_dtc = next(
            (dtc for dtc in state.activeDtcs if dtc.severity in {"HIGH", "CRITICAL"}), None
        )
        if high_dtc is not None:
            return self._evaluation(
                level="HIGH",
                title="Lỗi xe mức độ nghiêm trọng cao",
                message="Cần xem xét lỗi chẩn đoán đang hoạt động trước khi tiếp tục hành trình.",
                reasons=["high_severity_dtc", high_dtc.code],
                evidence=evidence,
                rest_level="NORMAL",
                rest_title="Chưa có khuyến nghị nghỉ do mệt mỏi",
                rest_message="Hãy xem hướng dẫn chẩn đoán trước khi quyết định có tiếp tục lái xe hay không.",
                confidence="HIGH",
                now_ms=now_ms,
                allowed=("SHOW_WARNING", "OPEN_DIAGNOSTICS"),
                emergency_candidate=False,
            )

        duration = state.continuousDrivingMinutes or 0
        fatigue_reported = support.userReportedFatigue is True
        if fatigue_reported or duration >= self.LONG_DRIVE_MINUTES:
            reasons: list[str] = []
            if fatigue_reported:
                reasons.append("user_reported_fatigue")
            if duration >= self.LONG_DRIVE_MINUTES:
                reasons.append("driving_over_4_hours")
            return self._evaluation(
                level="HIGH" if fatigue_reported else "MEDIUM",
                title="Nguy cơ mệt mỏi của tài xế" if fatigue_reported else "Thời gian lái liên tục dài",
                message=(
                    "Tài xế báo đang mệt. Hãy dừng ở vị trí an toàn sớm nhất có thể."
                    if fatigue_reported
                    else "Thời gian lái xe đã vượt bốn giờ. Hãy cân nhắc dừng nghỉ ở vị trí an toàn trước khi tiếp tục."
                ),
                reasons=reasons,
                evidence=evidence,
                rest_level="REST_RECOMMENDED",
                rest_title="Khuyến nghị dừng nghỉ",
                rest_message="Hãy lên kế hoạch dừng ở vị trí an toàn. Điều chỉnh cabin chỉ hỗ trợ tạm thời, không làm việc lái xe khi mệt trở nên an toàn.",
                confidence="HIGH"
                if fatigue_reported and duration >= self.LONG_DRIVE_MINUTES
                else "MEDIUM",
                now_ms=now_ms,
                allowed=("SHOW_WARNING", "SUGGEST_REST_STOP"),
                emergency_candidate=False,
            )

        if state.cabinTemperatureC >= self.HOT_CABIN_C:
            return self._evaluation(
                level="MEDIUM",
                title="Cabin đang nóng",
                message="Nhiệt độ cabin đang cao. Hãy điều chỉnh tiện nghi nhưng vẫn giữ tập trung vào việc lái xe.",
                reasons=["hot_cabin"],
                evidence=evidence,
                rest_level="MONITOR",
                rest_title="Theo dõi tiện nghi cabin",
                rest_message="Hãy điều chỉnh cabin và theo dõi cảm giác của bạn.",
                confidence="MEDIUM",
                now_ms=now_ms,
                allowed=(),
                emergency_candidate=False,
            )

        return self._evaluation(
            level="LOW",
            title="Trạng thái vận hành bình thường",
            message="Không có điều kiện rủi ro cao nào trong dữ liệu xe mới nhất.",
            reasons=[],
            evidence=evidence,
            rest_level="NORMAL",
            rest_title="Chưa cần khuyến nghị nghỉ",
            rest_message="Các tín hiệu hiện có chưa yêu cầu đưa ra khuyến nghị nghỉ ngơi.",
            confidence="LOW",
            now_ms=now_ms,
            allowed=(),
            emergency_candidate=False,
        )

    @staticmethod
    def _base_evidence(snapshot: ContextSnapshot) -> tuple[SafetyEvidence, ...]:
        items: list[SafetyEvidence] = []
        state = snapshot.state
        if state.crashDetected:
            items.append(SafetyEvidence("crash_detected", "Đã nhận tín hiệu va chạm"))
        if state.passengerResponse == "NO_RESPONSE":
            items.append(
                SafetyEvidence("occupant_no_response", "Chưa nhận được phản hồi từ người trong xe")
            )
        if snapshot.driver_support.userReportedFatigue is True:
            items.append(SafetyEvidence("user_reported_fatigue", "Tài xế báo đang mệt"))
        if (state.continuousDrivingMinutes or 0) >= SafetyRiskEngine.LONG_DRIVE_MINUTES:
            items.append(
                SafetyEvidence("driving_over_4_hours", "Lái xe liên tục vượt bốn giờ")
            )
        if any(dtc.severity in {"HIGH", "CRITICAL"} for dtc in state.activeDtcs):
            items.append(
                SafetyEvidence("high_severity_dtc", "Đang có mã lỗi chẩn đoán mức độ nghiêm trọng cao")
            )
        if state.engineTemperatureC >= SafetyRiskEngine.ENGINE_WARNING_C:
            items.append(SafetyEvidence("engine_overheat", "Nhiệt độ động cơ đang cao bất thường"))
        if state.cabinTemperatureC >= SafetyRiskEngine.HOT_CABIN_C:
            items.append(SafetyEvidence("hot_cabin", "Nhiệt độ cabin đang cao"))
        return tuple(items)

    @staticmethod
    def _evaluation(
        *,
        level: str,
        title: str,
        message: str,
        reasons: list[str],
        evidence: tuple[SafetyEvidence, ...],
        rest_level: str,
        rest_title: str,
        rest_message: str,
        confidence: str,
        now_ms: int,
        allowed: tuple[str, ...],
        emergency_candidate: bool,
    ) -> SafetyEvaluation:
        return SafetyEvaluation(
            risk=RiskAssessment(
                level=level,
                title=title,
                message=message,
                reasonCodes=reasons,
            ),
            rest=RestRecommendation(
                level=rest_level,
                title=rest_title,
                message=rest_message,
                confidence=confidence,
                reasonCodes=reasons,
                updatedAtMs=now_ms,
            ),
            evidence=evidence,
            allowed_action_types=allowed,
            emergency_candidate=emergency_candidate,
        )
