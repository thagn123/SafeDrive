import asyncio
import datetime

import pytest

from app.api.schemas.signals import SignalBatchRequest
from app.domain.models.signal import SpeedSignalInput
from app.ingestion.canonicalizer import Canonicalizer
from app.ingestion.registry import SignalRegistry
from app.services.signal_ingestion import SignalIngestionService
from app.state.manager import LatestStateManager
from app.state.rolling_window import RollingWindowManager


def speed_signal(signal_id: str, sequence: int, speed: float = 60.0) -> SpeedSignalInput:
    return SpeedSignalInput.model_validate(
        {
            "signal_id": signal_id,
            "source": "VHAL",
            "signal_type": "vehicle.speed_kmh",
            "occurred_at": datetime.datetime.now(datetime.UTC),
            "value": {"value": speed},
            "vehicle_id": "atomic-vehicle",
            "trip_id": "atomic-trip",
            "sequence": sequence,
        }
    )


def build_service() -> SignalIngestionService:
    registry = SignalRegistry()
    return SignalIngestionService(
        registry=registry,
        canonicalizer=Canonicalizer(registry),
        latest_state_manager=LatestStateManager(registry),
        rolling_window_manager=RollingWindowManager(registry),
    )


@pytest.mark.asyncio
async def test_rolling_stage_failure_leaves_all_stores_unchanged_and_retryable(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    service = build_service()
    request = SignalBatchRequest(signals=[speed_signal("atomic-1", 1)])
    canonical = request.signals[0].to_canonical()
    dedup_key = service.canonicalizer.dedup_key(canonical)
    store_key = ("atomic-vehicle", "atomic-trip", "atomic-operation")

    original_stage = service.rolling_window_manager.stage_appends

    def fail_stage(*_args: object, **_kwargs: object) -> object:
        raise RuntimeError("injected rolling append failure")

    monkeypatch.setattr(service.rolling_window_manager, "stage_appends", fail_stage)
    with pytest.raises(RuntimeError, match="injected rolling append failure"):
        await service.process_batch(request, "atomic-operation")

    assert service.latest_state_manager.get_state("atomic-vehicle", "atomic-trip") is None
    assert service.canonicalizer.dedup_cache.peek(dedup_key) is None
    assert service.idempotency_store.peek(store_key) is None
    assert service.rolling_window_manager.entries == {}

    monkeypatch.setattr(
        service.rolling_window_manager,
        "stage_appends",
        original_stage,
    )
    retry = await service.process_batch(request, "atomic-operation")
    assert retry.accepted == 1
    assert retry.state_version == 1


@pytest.mark.asyncio
async def test_cancellation_before_commit_leaves_no_partial_mutation() -> None:
    service = build_service()
    request = SignalBatchRequest(signals=[speed_signal("cancel-1", 1)])
    canonical = request.signals[0].to_canonical()
    dedup_key = service.canonicalizer.dedup_key(canonical)
    store_key = ("atomic-vehicle", "atomic-trip", "cancel-operation")

    await service.rolling_window_manager.acquire_transaction()
    task = asyncio.create_task(service.process_batch(request, "cancel-operation"))
    await asyncio.sleep(0)
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    service.rolling_window_manager.release_transaction()

    assert service.latest_state_manager.get_state("atomic-vehicle", "atomic-trip") is None
    assert service.canonicalizer.dedup_cache.peek(dedup_key) is None
    assert service.idempotency_store.peek(store_key) is None
    assert service.rolling_window_manager.entries == {}

    retry = await service.process_batch(request, "cancel-operation")
    assert retry.accepted == 1
    assert retry.state_version == 1


@pytest.mark.asyncio
async def test_multi_signal_batch_failure_is_not_half_committed(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    service = build_service()
    request = SignalBatchRequest(
        signals=[
            speed_signal("batch-1", 1, 40.0),
            speed_signal("batch-2", 2, 50.0),
        ]
    )
    original_stage = service.rolling_window_manager.stage_appends

    def fail_stage(*_args: object, **_kwargs: object) -> object:
        raise RuntimeError("batch rolling failure")

    monkeypatch.setattr(service.rolling_window_manager, "stage_appends", fail_stage)
    with pytest.raises(RuntimeError, match="batch rolling failure"):
        await service.process_batch(request, "batch-operation")
    assert service.latest_state_manager.get_state("atomic-vehicle", "atomic-trip") is None
    assert service.canonicalizer.dedup_cache.cache == {}
    assert service.idempotency_store.store == {}

    monkeypatch.setattr(
        service.rolling_window_manager,
        "stage_appends",
        original_stage,
    )
    retry = await service.process_batch(request, "batch-operation")
    assert retry.accepted == 2
    assert retry.state_version == 2
