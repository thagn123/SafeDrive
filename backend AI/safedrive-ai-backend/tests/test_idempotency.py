import datetime
from collections import OrderedDict

import pytest

from app.api.idempotency import IdempotencyStore, is_valid_idempotency_key
from app.api.schemas.signals import SignalBatchRequest
from app.domain.models.signal import SpeedSignalInput
from app.ingestion.canonicalizer import Canonicalizer
from app.ingestion.registry import SignalRegistry
from app.services.signal_ingestion import (
    IngestionError,
    SignalBatchBusinessResult,
    SignalIngestionService,
)
from app.state.manager import LatestStateManager
from app.state.rolling_window import RollingWindowManager


def make_speed_input(sig_id: str, v_id: str, t_id: str, speed: float, seq: int) -> SpeedSignalInput:
    return SpeedSignalInput.model_validate(
        {
            "signal_id": sig_id,
            "source": "VHAL",
            "signal_type": "vehicle.speed_kmh",
            "occurred_at": datetime.datetime.now(datetime.UTC).isoformat(),
            "value": {"value": speed},
            "quality": "VALID",
            "vehicle_id": v_id,
            "trip_id": t_id,
            "sequence": seq,
        }
    )


def test_idempotency_key_format_validation() -> None:
    assert is_valid_idempotency_key("valid-key-12345") is True
    assert is_valid_idempotency_key("a" * 128) is True

    # Invalid cases
    assert is_valid_idempotency_key("") is False
    assert is_valid_idempotency_key("   ") is False
    assert is_valid_idempotency_key("key with spaces") is False
    assert is_valid_idempotency_key("key\r\ninjection") is False
    assert is_valid_idempotency_key("a" * 129) is False


def test_payload_hash_is_recursive_object_order_independent() -> None:
    first = make_speed_input("sig-hash", "v-hash", "t-hash", 60.0, seq=1)
    second = make_speed_input("sig-hash", "v-hash", "t-hash", 60.0, seq=1)
    second.occurred_at = first.occurred_at
    first.metadata = OrderedDict(
        [
            ("outer_b", {"nested_b": 2, "nested_a": 1}),
            ("outer_a", ["order", "is", "preserved"]),
        ]
    )
    second.metadata = OrderedDict(
        [
            ("outer_a", ["order", "is", "preserved"]),
            ("outer_b", {"nested_a": 1, "nested_b": 2}),
        ]
    )

    assert SignalIngestionService.compute_payload_hash(
        [first]
    ) == SignalIngestionService.compute_payload_hash([second])

    second.metadata["outer_a"] = ["preserved", "is", "order"]
    assert SignalIngestionService.compute_payload_hash(
        [first]
    ) != SignalIngestionService.compute_payload_hash([second])


@pytest.mark.asyncio
async def test_idempotency_store_replay_and_reuse_conflict(
    test_registry: SignalRegistry,
) -> None:
    canonicalizer = Canonicalizer(test_registry)
    latest_state_manager = LatestStateManager(test_registry)
    rolling_window_manager = RollingWindowManager(test_registry)

    current_monotonic = 1000.0

    def fake_monotonic() -> float:
        return current_monotonic

    idempotency_store: IdempotencyStore[SignalBatchBusinessResult] = IdempotencyStore(
        maxsize=10, ttl_seconds=86400.0, clock=fake_monotonic
    )
    service = SignalIngestionService(
        registry=test_registry,
        canonicalizer=canonicalizer,
        latest_state_manager=latest_state_manager,
        rolling_window_manager=rolling_window_manager,
        idempotency_store=idempotency_store,
    )

    v_id, t_id = "v-idem", "t-idem"
    sig1 = make_speed_input("sig-idem-1", v_id, t_id, 60.0, seq=1)
    req1 = SignalBatchRequest(signals=[sig1])

    # 1. First execution
    res1 = await service.process_batch(req1, idempotency_key="idem-key-999")
    assert res1.accepted == 1
    assert res1.state_version == 1

    # 2. Replay with SAME idempotency key and SAME payload -> Cached result returned
    res2 = await service.process_batch(req1, idempotency_key="idem-key-999")
    assert res2.accepted == 1
    assert res2.state_version == 1

    # State manager was NOT invoked again
    state = latest_state_manager.get_state(v_id, t_id)
    assert state is not None
    assert state.state_version == 1

    # 3. Reused key with DIFFERENT payload -> 409 IDEMPOTENCY_KEY_REUSED
    sig2_diff = make_speed_input("sig-idem-2", v_id, t_id, 80.0, seq=2)
    req2_diff = SignalBatchRequest(signals=[sig2_diff])
    with pytest.raises(IngestionError) as exc_info:
        await service.process_batch(req2_diff, idempotency_key="idem-key-999")
    assert exc_info.value.code == "IDEMPOTENCY_KEY_REUSED"

    # 4. Advance fake clock past 24 hours (86401 seconds) -> Expiry allows new request
    current_monotonic += 86401.0
    res3 = await service.process_batch(req2_diff, idempotency_key="idem-key-999")
    assert res3.accepted == 1
    assert res3.state_version == 2
