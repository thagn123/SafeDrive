"""Grounded deterministic assistant for the current SafeDrive MVP."""

from __future__ import annotations

from dataclasses import dataclass

from app.api.schemas.mobile import (
    AssistantQueryRequest,
    AssistantQueryResponse,
    ChatMessage,
    Dtc,
    SafeDriveAction,
    VehicleState,
)
from app.mobile import dtc_catalog
from app.mobile.context import ContextPack, ContextSnapshot, MobileContextBuilder
from app.mobile.intent import IntentResolution, IntentResolver
from app.mobile.safety import SafetyEvaluation


@dataclass(frozen=True, slots=True)
class AssistantPlan:
    response: AssistantQueryResponse
    context_pack: ContextPack
    resolution: IntentResolution


def _fmt_temp(temp: float) -> str:
    return f"{temp:g}"


# Deterministic, severity-driven guidance for a DTC reply. The client-supplied
# ``Dtc.recommendation`` free-text field is never spoken to the driver: a
# buggy or malicious client could send "you can keep driving normally" for a
# HIGH/CRITICAL code, and the backend has no way to validate arbitrary prose.
# ``severity`` itself is trusted the same way SafetyRiskEngine already trusts
# it (it already drives risk.level and allowed_action_types) -- this policy
# only makes the assistant's wording consistent with that same trust boundary
# instead of separately echoing untrusted free text.
_DTC_SEVERITY_LABEL: dict[str, str] = {
    "CRITICAL": "nghiêm trọng",
    "HIGH": "nghiêm trọng cao",
    "MEDIUM": "trung bình",
    "LOW": "thấp",
}
_DTC_SEVERITY_GUIDANCE: dict[str, str] = {
    "CRITICAL": (
        "Không nên tiếp tục hành trình dài. Hãy giảm tải cho xe, tránh tăng tốc mạnh và dừng tại vị trí "
        "an toàn để kiểm tra. Đây chưa phải chẩn đoán kỹ thuật cuối cùng."
    ),
    "HIGH": (
        "Không nên tiếp tục hành trình dài. Hãy giảm tải cho xe, tránh tăng tốc mạnh và dừng tại vị trí "
        "an toàn để kiểm tra. Đây chưa phải chẩn đoán kỹ thuật cuối cùng."
    ),
    "MEDIUM": "Bạn có thể tiếp tục lái thận trọng, nhưng hãy theo dõi dấu hiệu bất thường và kiểm tra xe sớm.",
    "LOW": "Mức độ hiện chưa nghiêm trọng, nhưng bạn vẫn nên kiểm tra xe khi thuận tiện.",
}


class ContextAwareAssistant:
    """Plans a concise reply from fresh structured context and fixed policies."""

    def __init__(self, intent_resolver: IntentResolver | None = None) -> None:
        self._intent_resolver = intent_resolver or IntentResolver()

    def build_reply(
        self,
        resolution: IntentResolution,
        snapshot: ContextSnapshot,
        safety: SafetyEvaluation,
        request_id: str,
    ) -> tuple[str, list[SafeDriveAction]]:
        """Builds the deterministic reply/actions for an explicit resolution, bypassing
        intent resolution itself. Used by MobileSessionStore to re-render a reply after
        an advisory LLM reclassification (app/mobile/llm.py's OllamaIntentClassifier)
        substitutes a different, still-fixed route -- the wording/action logic below is
        exactly the same regardless of how the route was chosen, so a reclassification
        can only ever select an existing grounded template, never invent one."""
        return self._message_and_actions(resolution, snapshot, safety, request_id)

    def answer(
        self,
        request: AssistantQueryRequest,
        snapshot: ContextSnapshot,
        safety: SafetyEvaluation,
        *,
        started_at_ms: int,
        completed_at_ms: int,
    ) -> AssistantPlan:
        context_pack = MobileContextBuilder.to_context_pack(snapshot)
        resolution = self._intent_resolver.resolve(request.text, snapshot, safety)
        text, actions = self._message_and_actions(resolution, snapshot, safety, request.requestId)
        return AssistantPlan(
            response=AssistantQueryResponse(
                requestId=request.requestId,
                message=ChatMessage(
                    id=f"msg_{request.requestId}",
                    sender="SAFEDRIVE",
                    text=text,
                    timestampMs=completed_at_ms,
                    risk=safety.risk,
                    actions=actions,
                    route=resolution.route,
                    latencyMs=completed_at_ms - started_at_ms,
                ),
                serverTimeMs=completed_at_ms,
                serverProcessingMs=completed_at_ms - started_at_ms,
                model="deterministic-context-router",
                finishReason="STOP",
            ),
            context_pack=context_pack,
            resolution=resolution,
        )

    @staticmethod
    def _message_and_actions(
        resolution: IntentResolution,
        snapshot: ContextSnapshot,
        safety: SafetyEvaluation,
        request_id: str,
    ) -> tuple[str, list[SafeDriveAction]]:
        route = resolution.route
        state = snapshot.state

        if route == "assistant.missing_context" or not snapshot.state_is_fresh:
            return (
                (
                    "Tôi cần một cập nhật trạng thái xe mới trước khi đưa ra khuyến nghị an toàn. "
                    "Dữ liệu hiện tại đã cũ hoặc chưa đủ."
                ),
                ContextAwareAssistant._actions(safety, request_id),
            )

        if safety.emergency_candidate:
            # A live crash+no-response emergency always takes priority over
            # whatever keyword the query happened to match (e.g. a fatigue
            # word) — the reply must surface the active emergency, not
            # relabel crash evidence as an unrelated risk category.
            return (
                (
                    "Tôi ghi nhận tín hiệu va chạm và xe hiện chưa có phản hồi từ người trong xe. "
                    "Đây là ưu tiên an toàn cao nhất lúc này — quy trình SOS mô phỏng đang được theo dõi. "
                    "Vui lòng xác nhận nếu bạn ổn, hoặc hệ thống sẽ tiếp tục đếm ngược mô phỏng."
                ),
                ContextAwareAssistant._actions(safety, request_id),
            )

        if route in {"safety.driver_fatigue", "safety.user_discomfort_check"}:
            return (
                ContextAwareAssistant._fatigue_situation(state, safety)
                + " Hãy giảm tốc và dừng ở vị trí an toàn sớm nhất có thể để nghỉ ngơi. Điều chỉnh cabin chỉ "
                "hỗ trợ tạm thời, không thay thế việc nghỉ khi mệt.",
                ContextAwareAssistant._actions(safety, request_id),
            )

        if route == "climate.set_temperature":
            target = resolution.hvac_target_temperature_c
            if target is None:
                return (
                    "Tôi cần mức nhiệt độ cụ thể từ 16 đến 30 độ C trước khi tạo thao tác HVAC.",
                    [],
                )
            return (
                (
                    f"Cabin hiện ở {_fmt_temp(state.cabinTemperatureC)} độ C, năng lượng {state.energyPercent}% và "
                    f"bạn yêu cầu {_fmt_temp(target)} độ C. Tôi sẽ chuẩn bị đặt HVAC về {_fmt_temp(target)} độ C ở chế độ mô phỏng."
                ),
                [ContextAwareAssistant._hvac_action(target, request_id)],
            )

        if route == "climate.enable_default":
            target = resolution.hvac_target_temperature_c
            if target is None:
                return (
                    "Tôi chưa xác định được nhiệt độ điều hòa phù hợp từ trạng thái xe hiện tại.",
                    [],
                )
            return (
                (
                    "Bạn chưa nêu nhiệt độ cụ thể. Dựa trên cabin hiện ở "
                    f"{_fmt_temp(state.cabinTemperatureC)} độ C và năng lượng {state.energyPercent}%, "
                    f"tôi đề xuất đặt HVAC về {_fmt_temp(target)} độ C ở chế độ mô phỏng."
                ),
                [ContextAwareAssistant._hvac_action(target, request_id)],
            )

        if route == "climate.invalid_temperature":
            requested = resolution.requested_temperature_c
            requested_text = _fmt_temp(requested) if requested is not None else "mức này"
            return (
                (
                    f"{requested_text} độ C nằm ngoài dải HVAC an toàn của MVP (16 đến 30 độ C). "
                    "Tôi sẽ không tạo thao tác điều hòa; bạn có thể chọn một mức trong dải này."
                ),
                [],
            )

        if route == "comfort.too_hot":
            target = 24.0 if state.energyPercent <= 20 else 22.0
            return (
                (
                    f"Cabin hiện ở {_fmt_temp(state.cabinTemperatureC)} độ C và mức năng lượng là {state.energyPercent}%. "
                    f"Tôi đề xuất đặt HVAC về {_fmt_temp(target)} độ C để làm mát dần, thay vì giảm quá sâu khi năng lượng thấp."
                ),
                [ContextAwareAssistant._hvac_action(target, request_id)],
            )

        if route == "vehicle.fault_concern":
            mentioned_code = resolution.mentioned_dtc_code
            if mentioned_code is not None:
                # The driver named a specific DTC-shaped code (e.g. "Ma U0100 nghia la gi?").
                # Three tiers, in order of trust:
                #  1. KNOWN_AND_ACTIVE -- the vehicle's own live state is reporting this exact
                #     code right now. Always wins over the static catalog below: live state is
                #     a stronger signal than generic reference data for "what's happening now."
                #  2. KNOWN_BUT_NOT_ACTIVE -- not currently reported, but the static reference
                #     catalog (app/mobile/dtc_catalog.py) has a standardized, generic meaning
                #     for it. Explicitly say it is NOT active -- never implies a current fault.
                #  3. UNKNOWN_TO_CATALOG -- syntactically DTC-shaped but covered by neither
                #     source. Say so honestly; never invent a meaning.
                active_match = ContextAwareAssistant._find_dtc_by_code(state, mentioned_code)
                if active_match is not None:
                    return (
                        ContextAwareAssistant._dtc_reply_text(active_match),
                        ContextAwareAssistant._actions(safety, request_id),
                    )
                catalog_entry = dtc_catalog.lookup(mentioned_code)
                if catalog_entry is not None:
                    return (
                        (
                            f"Mã {catalog_entry.code} thường có nghĩa là: {catalog_entry.meaning} "
                            f"Tuy nhiên, mã {catalog_entry.code} hiện KHÔNG có trong danh sách lỗi "
                            "đang hoạt động của xe bạn -- đây chỉ là thông tin tham khảo chung, "
                            "không phải tình trạng hiện tại."
                        ),
                        [],
                    )
                other = ContextAwareAssistant._primary_dtc(state)
                other_clause = (
                    f" Xe hiện có mã {other.code} đang hoạt động, mức độ "
                    f"{_DTC_SEVERITY_LABEL[other.severity]}; bạn có thể hỏi cụ thể về mã này."
                    if other is not None
                    else ""
                )
                return (
                    (
                        f"Mã {mentioned_code} hiện không có trong danh mục tham khảo đáng tin cậy "
                        "của tôi, nên tôi không thể xác nhận ý nghĩa của mã này. Bạn có thể tra cứu "
                        f"tài liệu chẩn đoán chính hãng hoặc dùng máy quét OBD-II để xác nhận.{other_clause}"
                    ),
                    ContextAwareAssistant._actions(safety, request_id) if other is not None else [],
                )

            dtc = ContextAwareAssistant._primary_dtc(state)
            if dtc is not None:
                return (
                    ContextAwareAssistant._dtc_reply_text(dtc),
                    ContextAwareAssistant._actions(safety, request_id),
                )
            return (
                "Tôi chưa thấy mã lỗi hoạt động trong trạng thái xe mới nhất. Bạn có thể kiểm tra lại bảng chẩn đoán.",
                [],
            )

        if route == "safety.emergency_request":
            return (
                (
                    "Tôi có thể bắt đầu quy trình SOS mô phỏng sau khi bạn xác nhận. "
                    "MVP này không liên hệ dịch vụ cứu hộ thật."
                ),
                [
                    SafeDriveAction(
                        id=f"act_sos_{request_id}",
                        type="START_SOS_COUNTDOWN",
                        title="Bắt đầu SOS mô phỏng",
                        requiresConfirmation=True,
                    )
                ],
            )

        if route == "assistant.vehicle_status":
            # Leads with the risk-driven headline (the one abnormal condition that matters,
            # if any) instead of dumping every field -- speed/cabin stay as concise grounded
            # facts, and a DTC is only pointed at (count + "open diagnostics"), never detailed
            # here, since the fault_concern route already owns severity-aware DTC wording.
            headline = (
                "Xe đang vận hành bình thường, chưa có cảnh báo nào đáng chú ý."
                if safety.risk.level == "LOW"
                else f"{safety.risk.title}."
            )
            duration = state.continuousDrivingMinutes
            duration_clause = (
                f" Bạn đã lái liên tục khoảng {duration} phút." if duration is not None else ""
            )
            dtc_clause = (
                f" Xe có {len(state.activeDtcs)} mã lỗi đang hoạt động, bạn có thể mở phần chẩn đoán để xem chi tiết."
                if state.activeDtcs
                else ""
            )
            engine_overheating = bool(
                {"engine_overheat_warning", "engine_overheat_critical"} & set(safety.risk.reasonCodes)
            )
            engine_clause = (
                f", động cơ {_fmt_temp(state.engineTemperatureC)} độ C" if engine_overheating else ""
            )
            return (
                (
                    f"{headline} Tốc độ hiện tại {state.speedKmh:.0f} km/h, cabin "
                    f"{_fmt_temp(state.cabinTemperatureC)} độ C{engine_clause}.{dtc_clause}{duration_clause}"
                ),
                ContextAwareAssistant._actions(safety, request_id),
            )

        if route == "companion.conversation":
            duration = state.continuousDrivingMinutes
            duration_text = f" Bạn đã lái khoảng {duration} phút rồi." if duration is not None else ""
            return (
                (
                    f"Tôi ở đây cùng bạn. Xe đang chạy {state.speedKmh:.0f} km/h, cabin "
                    f"{_fmt_temp(state.cabinTemperatureC)} độ C.{duration_text} Nếu bạn bắt đầu mệt, "
                    "hãy nói với tôi hoặc dừng ở vị trí an toàn khi có thể."
                ),
                ContextAwareAssistant._actions(safety, request_id),
            )

        if route == "assistant.clarify":
            # Genuine ambiguity among specific known intents (fatigue vs. cabin vs. vehicle
            # concern) -- IntentResolver._resolve_ambiguous's own final fallback. Distinct from
            # assistant.general below: this route means the driver's message plausibly matched
            # a safety-relevant category, just not confidently enough to pick one.
            return (
                (
                    "Bạn đang thấy mệt, khó chịu trong cabin, hay lo về tình trạng xe? "
                    "Tôi sẽ dùng thông tin phù hợp để hỗ trợ bạn."
                ),
                [],
            )

        if route == "assistant.general":
            # IntentResolver.resolve()'s true catch-all -- nothing matched any known
            # category at all, so this text must not pretend the driver asked about
            # fatigue/cabin/vehicle-concern (that was the old, misleading shared branch).
            # It's also the deterministic fallback for the narrator's genuine open-answer
            # path (OllamaNarrator.answer_open_query) when Ollama is unavailable or both
            # attempts are rejected -- it must not be a flat "out of scope" non-answer,
            # since many assistant.general hits are genuinely vehicle-related questions
            # phrased in a way no keyword matched (e.g. "xe cua toi the nao"). Leading with
            # real, current vehicle facts means the driver gets something useful either
            # way, while the closing sentence still honestly scopes what SafeDrive helps
            # with for questions that truly are unrelated (trivia, math, general chat).
            return (
                (
                    f"Dữ liệu hiện tại cho thấy xe đang chạy {state.speedKmh:.0f} km/h, nhiệt độ cabin "
                    f"{_fmt_temp(state.cabinTemperatureC)} độ C và mức năng lượng còn {state.energyPercent}%. "
                    "Câu hỏi này có thể nằm ngoài phạm vi hỗ trợ của tôi; tôi có thể giúp cụ thể hơn về "
                    "tình trạng xe, cabin, cảnh báo lỗi hoặc nhu cầu nghỉ ngơi."
                ),
                [],
            )

        # Defensive catch-all: IntentResolver.resolve() cannot currently produce any route
        # not already handled above, so this is unreachable today. Kept rather than removed
        # so a future new route added to the resolver without a matching branch here fails
        # safely with a scoped message instead of an unhandled exception.
        return (
            "Tôi có thể hỗ trợ kiểm tra tình trạng xe, cabin, cảnh báo hoặc nhu cầu nghỉ ngơi. Bạn muốn xem phần nào?",
            [],
        )

    @staticmethod
    def _primary_dtc(state: VehicleState) -> Dtc | None:
        if not state.activeDtcs:
            return None
        return next(
            (candidate for candidate in state.activeDtcs if candidate.severity in {"CRITICAL", "HIGH"}),
            state.activeDtcs[0],
        )

    @staticmethod
    def _find_dtc_by_code(state: VehicleState, code: str) -> Dtc | None:
        return next(
            (candidate for candidate in state.activeDtcs if candidate.code.upper() == code.upper()),
            None,
        )

    @staticmethod
    def _dtc_reply_text(dtc: Dtc) -> str:
        # dtc.recommendation is client-supplied free text and is never spoken here -- only
        # the validated severity enum drives guidance, matching the same field
        # SafetyRiskEngine already treats as authoritative. See _DTC_SEVERITY_GUIDANCE.
        severity_label = _DTC_SEVERITY_LABEL[dtc.severity]
        guidance = _DTC_SEVERITY_GUIDANCE[dtc.severity]
        return f"Xe đang có mã {dtc.code}: {dtc.title}, mức độ {severity_label}. {guidance}"

    @staticmethod
    def required_narration_snippets(
        resolution: IntentResolution,
        snapshot: ContextSnapshot,
        safety: SafetyEvaluation,
    ) -> tuple[str, ...]:
        """Non-numeric literal clauses a narrated reply must preserve verbatim.

        Generalizes OllamaNarrator's own number-preservation guardrail to the safety-
        relevant content that check can't see: a DTC code such as "U0100" is invisible to
        that regex (every digit is preceded by a letter), and directive clauses like the
        rest-stop instruction have no preservation check at all otherwise. Mirrors
        _message_and_actions's own branch precedence so the two can never disagree about
        which fact set backs the approved reply being validated against.
        """

        route = resolution.route
        state = snapshot.state

        if route == "assistant.missing_context" or not snapshot.state_is_fresh:
            return ()
        if safety.emergency_candidate:
            return ()
        if route in {"safety.driver_fatigue", "safety.user_discomfort_check"}:
            return ("Hãy giảm tốc và dừng ở vị trí an toàn sớm nhất có thể để nghỉ ngơi.",)
        if route == "vehicle.fault_concern":
            mentioned_code = resolution.mentioned_dtc_code
            if mentioned_code is not None:
                active_match = ContextAwareAssistant._find_dtc_by_code(state, mentioned_code)
                if active_match is not None:
                    return (active_match.code, _DTC_SEVERITY_GUIDANCE[active_match.severity])
                catalog_entry = dtc_catalog.lookup(mentioned_code)
                if catalog_entry is not None:
                    return (catalog_entry.code, catalog_entry.meaning)
                # Unknown-to-catalog case: the code itself must still survive verbatim
                # so the narrator can't silently claim a different code is unavailable.
                return (mentioned_code,)
            dtc = ContextAwareAssistant._primary_dtc(state)
            if dtc is None:
                return ()
            return (dtc.code, _DTC_SEVERITY_GUIDANCE[dtc.severity])
        if route == "assistant.vehicle_status" and safety.risk.level != "LOW":
            return (safety.risk.title,)
        return ()

    @staticmethod
    def _fatigue_situation(state: VehicleState, safety: SafetyEvaluation) -> str:
        """Translates SafetyRiskEngine's internal reason codes into a natural Vietnamese
        clause instead of joining and speaking the raw codes themselves (e.g.
        "user_reported_fatigue, driving_over_4_hours"), and grounds it in the real
        driving duration only when that field is actually present."""

        reasons = set(safety.risk.reasonCodes)
        duration = state.continuousDrivingMinutes
        fatigue_reported = "user_reported_fatigue" in reasons
        long_drive = "driving_over_4_hours" in reasons
        if fatigue_reported and duration is not None:
            return f"Bạn báo đang mệt sau khi đã lái liên tục khoảng {duration} phút."
        if fatigue_reported:
            return "Bạn báo đang mệt."
        if long_drive and duration is not None:
            return f"Bạn đã lái xe liên tục khoảng {duration} phút, hơi lâu so với khuyến nghị."
        return "Hệ thống chưa xác nhận được dấu hiệu mệt mỏi cụ thể, nhưng bạn nên tự đánh giá cảm giác của mình."

    @staticmethod
    def _actions(safety: SafetyEvaluation, request_id: str) -> list[SafeDriveAction]:
        titles = {
            "SHOW_WARNING": "Hiển thị cảnh báo an toàn",
            "OPEN_DIAGNOSTICS": "Mở chẩn đoán xe",
            "SUGGEST_REST_STOP": "Đề xuất điểm dừng nghỉ",
            "START_SOS_COUNTDOWN": "Bắt đầu SOS mô phỏng",
        }
        confirmation_required = {"SUGGEST_REST_STOP", "START_SOS_COUNTDOWN"}
        priority = {
            "START_SOS_COUNTDOWN": 0,
            "SUGGEST_REST_STOP": 1,
            "OPEN_DIAGNOSTICS": 2,
            "SHOW_WARNING": 3,
        }
        return [
            SafeDriveAction(
                id=f"act_{action.lower()}_{request_id}",
                type=action,
                title=titles[action],
                requiresConfirmation=action in confirmation_required,
            )
            for action in sorted(safety.allowed_action_types, key=lambda item: priority[item])
        ]

    @staticmethod
    def _hvac_action(target: float, request_id: str) -> SafeDriveAction:
        return SafeDriveAction(
            id=f"act_set_hvac_{_fmt_temp(target)}_{request_id}",
            type="SET_HVAC_TEMPERATURE",
            title=f"Đặt điều hòa {_fmt_temp(target)}°C (mô phỏng)",
            requiresConfirmation=True,
            hvacTargetTemperatureC=target,
        )
