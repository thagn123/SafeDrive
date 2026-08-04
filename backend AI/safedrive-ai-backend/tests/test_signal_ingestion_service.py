import asyncio
import datetime
from typing import Any

import pytest

from app.api.schemas.signals import SignalBatchRequest
from app.domain.models.signal import SpeedSignalInput
from app.ingestion.canonicalizer import Canonicalizer
from app.ingestion.registry import SignalRegistry
from app.services.signal_ingestion import IngestionError, SignalIngestionService
from app.state.manager import LatestStateManager
from app.state.rolling_window import RollingWindowManager


def make_speed_input(
    sig_id: str,
    v_id: str,
    t_id: str,
    speed: float,
    seq: int,
    occurred_at: str | None = None,
) -> SpeedSignalInput:
    if occurred_at is None:
        occurred_at = datetime.datetime.now(datetime.UTC).isoformat()
    return SpeedSignalInput.model_validate(
        {
            "signal_id": sig_id,
            "source": "VHAL",
            "signal_type": "vehicle.speed_kmh",
            "occurred_at": occurred_at,
            "value": {"value": speed},
            "quality": "VALID",
            "vehicle_id": v_id,
            "trip_id": t_id,
            "sequence": seq,
        }
    )


@pytest.fixture
def service_fixture(
    test_registry: SignalRegistry,
) -> SignalIngestionService:
    canonicalizer = Canonicalizer(test_registry)
    latest_state_manager = LatestStateManager(test_registry)
    rolling_window_manager = RollingWindowManager(test_registry)
    return SignalIngestionService(
        registry=test_registry,
        canonicalizer=canonicalizer,
        latest_state_manager=latest_state_manager,
        rolling_window_manager=rolling_window_manager,
    )


@pytest.mark.asyncio
async def test_cross_hub_commit_ordering_counterexample(
    service_fixture: SignalIngestionService,
) -> None:
    v_id, t_id = "v-ordering", "t-ordering"

    # Step 1: Ingest seq=10 -> Accepted
    sig1 = make_speed_input("sig-seq-10", v_id, t_id, 50.0, seq=10)
    req1 = SignalBatchRequest(signals=[sig1])
    res1 = await service_fixture.process_batch(req1, idempotency_key="idem-order-1")
    assert res1.accepted == 1
    assert res1.state_version == 1

    # Step 2: Ingest seq=5 (out of order) -> Quarantined
    # State ordering rejects seq=5. Dedup cache must NOT record sig-seq-5 as accepted.
    sig2 = make_speed_input("sig-seq-5", v_id, t_id, 40.0, seq=5)
    req2 = SignalBatchRequest(signals=[sig2])
    res2 = await service_fixture.process_batch(req2, idempotency_key="idem-order-2")
    assert res2.quarantined == 1
    assert res2.accepted == 0
    assert res2.duplicate == 0
    assert res2.state_version == 1  # Unchanged!

    # Step 3: Re-send sig-seq-5 with fixed sequence=15
    # If dedup cache was prematurely updated in step 2, this would be reported as conflict!
    # With two-phase commit, step 2 left dedup cache clean, so fixed retry succeeds!
    sig3 = make_speed_input("sig-seq-5", v_id, t_id, 45.0, seq=15)
    req3 = SignalBatchRequest(signals=[sig3])
    res3 = await service_fixture.process_batch(req3, idempotency_key="idem-order-3")
    assert res3.accepted == 1
    assert res3.state_version == 2


@pytest.mark.asyncio
async def test_signal_id_conflict_rejection(
    service_fixture: SignalIngestionService,
) -> None:
    v_id, t_id = "v-conflict", "t-conflict"

    # Ingest sig-A with speed=50.0
    sigA = make_speed_input("sig-A", v_id, t_id, 50.0, seq=1)
    reqA = SignalBatchRequest(signals=[sigA])
    resA = await service_fixture.process_batch(reqA, idempotency_key="idem-conflict-1")
    assert resA.accepted == 1

    # Attempt to ingest same sig-A ID with DIFFERENT speed=90.0 -> 409 SIGNAL_ID_CONFLICT
    sigA_diff = make_speed_input("sig-A", v_id, t_id, 90.0, seq=2)
    reqB = SignalBatchRequest(signals=[sigA_diff])
    with pytest.raises(IngestionError) as exc_info:
        await service_fixture.process_batch(reqB, idempotency_key="idem-conflict-2")
    assert exc_info.value.code == "SIGNAL_ID_CONFLICT"


@pytest.mark.asyncio
async def test_concurrency_isolation_under_high_load(
    service_fixture: SignalIngestionService,
) -> None:
    v_id, t_id = "v-concurrent", "t-concurrent"
    sig = make_speed_input("sig-shared-concurrent", v_id, t_id, 75.0, seq=1)

    # 25 simultaneous requests using the same signal and DIFFERENT idempotency keys
    async def send_request(idx: int) -> Any:
        req = SignalBatchRequest(signals=[sig])
        return await service_fixture.process_batch(
            req, idempotency_key=f"idem-concurrent-{idx:03d}"
        )

    tasks = [send_request(i) for i in range(25)]
    results = await asyncio.gather(*tasks)

    # Exactly one request accepts the signal; remaining 24 see duplicate=1
    accepted_count = sum(r.accepted for r in results)
    duplicate_count = sum(r.duplicate for r in results)
    assert accepted_count == 1
    assert duplicate_count == 24
    # State version incremented exactly once
    state = service_fixture.latest_state_manager.get_state(v_id, t_id)
    assert state is not None
    assert state.state_version == 1
