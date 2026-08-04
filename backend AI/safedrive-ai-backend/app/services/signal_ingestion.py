from __future__ import annotations

import asyncio
import datetime
import hashlib
import json
from collections.abc import Callable, Sequence
from dataclasses import dataclass
from typing import Protocol, cast

from app.domain.models.signal import BaseCanonicalSignalInput, CanonicalSignal
from app.ingestion.canonicalizer import Canonicalizer
from app.ingestion.registry import SignalRegistry
from app.services.idempotency import IdempotencyStore, is_valid_idempotency_key
from app.state.manager import (
    LatestStateManager,
    OutOfOrderError,
    ReplayError,
    VehicleStateSnapshot,
)
from app.state.rolling_window import RollingWindowManager


class SignalBatchLike(Protocol):
    @property
    def signals(self) -> Sequence[BaseCanonicalSignalInput]: ...


@dataclass(frozen=True, slots=True)
class SignalBatchBusinessResult:
    """Idempotent business outcome; transport metadata is added per response."""

    accepted: int
    duplicate: int
    quarantined: int
    state_version: int


class IngestionError(ValueError):
    def __init__(self, code: str, safe_message: str) -> None:
        super().__init__(safe_message)
        self.code = code
        self.safe_message = safe_message


class SignalIngestionService:
    """Validate and stage a batch before one non-awaiting in-memory commit."""

    def __init__(
        self,
        registry: SignalRegistry,
        canonicalizer: Canonicalizer,
        latest_state_manager: LatestStateManager,
        rolling_window_manager: RollingWindowManager,
        idempotency_store: IdempotencyStore[SignalBatchBusinessResult] | None = None,
        clock: Callable[[], datetime.datetime] | None = None,
    ) -> None:
        self.registry = registry
        self.canonicalizer = canonicalizer
        self.latest_state_manager = latest_state_manager
        self.rolling_window_manager = rolling_window_manager
        self.idempotency_store = (
            idempotency_store
            if idempotency_store is not None
            else IdempotencyStore[SignalBatchBusinessResult](
                maxsize=10000,
                ttl_seconds=86400.0,
            )
        )
        self.clock = clock
        self.lock = asyncio.Lock()

    def current_time(self) -> datetime.datetime:
        if self.clock:
            return self.clock()
        return datetime.datetime.now(datetime.UTC)

    @staticmethod
    def _extract_signals(
        request_or_signals: SignalBatchLike | Sequence[BaseCanonicalSignalInput],
    ) -> Sequence[BaseCanonicalSignalInput]:
        if hasattr(request_or_signals, "signals"):
            return cast(SignalBatchLike, request_or_signals).signals
        return request_or_signals

    @staticmethod
    def compute_payload_hash(signals: Sequence[BaseCanonicalSignalInput]) -> str:
        """Canonical JSON hash that recursively sorts objects and preserves arrays."""
        payload = {"signals": [signal.model_dump(mode="json") for signal in signals]}
        canonical_json = json.dumps(
            payload,
            ensure_ascii=False,
            separators=(",", ":"),
            sort_keys=True,
        )
        return hashlib.sha256(canonical_json.encode("utf-8")).hexdigest()

    @staticmethod
    def _raise_idempotency_conflict() -> None:
        raise IngestionError(
            code="IDEMPOTENCY_KEY_REUSED",
            safe_message="Idempotency key was reused with a different payload.",
        )

    async def process_batch(
        self,
        request: SignalBatchLike | Sequence[BaseCanonicalSignalInput],
        idempotency_key: str | None,
        active_profile: str = "PRODUCTION_NO_DMS",
    ) -> SignalBatchBusinessResult:
        signals = self._extract_signals(request)
        if not idempotency_key:
            raise IngestionError(
                code="IDEMPOTENCY_KEY_REQUIRED",
                safe_message="Idempotency-Key header is required.",
            )
        if not is_valid_idempotency_key(idempotency_key):
            raise IngestionError(
                code="INVALID_IDEMPOTENCY_KEY",
                safe_message="Invalid Idempotency-Key header format.",
            )
        if not signals:
            raise IngestionError(
                code="EMPTY_BATCH",
                safe_message="Signal batch must not be empty.",
            )

        vehicle_id = signals[0].vehicle_id
        trip_id = signals[0].trip_id
        if any(signal.vehicle_id != vehicle_id or signal.trip_id != trip_id for signal in signals):
            raise IngestionError(
                code="MIXED_SIGNAL_PARTITIONS",
                safe_message="All signals in a batch must use the same vehicle and trip.",
            )

        store_key = (vehicle_id, trip_id, idempotency_key)
        payload_hash = self.compute_payload_hash(signals)

        cached = self.idempotency_store.peek(store_key)
        if cached is not None:
            cached_result, cached_hash = cached
            if cached_hash == payload_hash:
                return cached_result
            self._raise_idempotency_conflict()

        async with self.lock:
            cached = self.idempotency_store.peek(store_key)
            if cached is not None:
                cached_result, cached_hash = cached
                if cached_hash == payload_hash:
                    return cached_result
                self._raise_idempotency_conflict()

            canonical_signals: list[CanonicalSignal] = []
            classifications = []
            batch_hashes: dict[tuple[str, str, str, str], str] = {}
            for input_signal in signals:
                canonical_signal = input_signal.to_canonical(received_at=self.current_time())
                classification = self.canonicalizer.classify(
                    canonical_signal,
                    active_profile,
                )
                if classification.status_code == 409:
                    raise IngestionError(
                        code="SIGNAL_ID_CONFLICT",
                        safe_message=("Signal ID conflict detected: same ID with different body."),
                    )

                dedup_key = self.canonicalizer.dedup_key(canonical_signal)
                signal_hash = self.canonicalizer.compute_hash(canonical_signal)
                previous_hash = batch_hashes.get(dedup_key)
                if previous_hash is not None and previous_hash != signal_hash:
                    raise IngestionError(
                        code="SIGNAL_ID_CONFLICT",
                        safe_message=(
                            "Within-batch signal ID conflict detected: same ID with different body."
                        ),
                    )
                batch_hashes[dedup_key] = signal_hash
                canonical_signals.append(canonical_signal)
                classifications.append(classification)

            accepted_signals: list[CanonicalSignal] = []
            accepted_hashes: dict[tuple[str, str, str, str], str] = {}
            accepted = 0
            duplicate = 0
            quarantined = 0
            staged_state = self.latest_state_manager.get_state(vehicle_id, trip_id)

            for canonical_signal, classification in zip(
                canonical_signals,
                classifications,
                strict=True,
            ):
                if classification.status_code == 422:
                    quarantined += 1
                    continue
                dedup_key = self.canonicalizer.dedup_key(canonical_signal)
                signal_hash = self.canonicalizer.compute_hash(canonical_signal)
                if (
                    classification.message == "Duplicate ignored"
                    or accepted_hashes.get(dedup_key) == signal_hash
                ):
                    duplicate += 1
                    continue

                try:
                    staged_state = self.latest_state_manager.stage_signal(
                        canonical_signal,
                        staged_state,
                    )
                except (OutOfOrderError, ReplayError):
                    quarantined += 1
                    continue

                accepted_signals.append(canonical_signal)
                accepted_hashes[dedup_key] = signal_hash
                accepted += 1

            state_version = staged_state.state_version if staged_state is not None else 0
            business_result = SignalBatchBusinessResult(
                accepted=accepted,
                duplicate=duplicate,
                quarantined=quarantined,
                state_version=state_version,
            )

            staged_dedup = self.canonicalizer.stage_commits(accepted_signals)
            staged_idempotency = self.idempotency_store.stage_put(
                store_key,
                payload_hash,
                business_result,
            )

            # Cancellation can only be delivered at this await. No live state has
            # changed yet. Once acquired, staging and commit contain no await points.
            await self.rolling_window_manager.acquire_transaction()
            previous_state: VehicleStateSnapshot | None = None
            state_swapped = False
            previous_windows = {}
            windows_swapped = False
            old_dedup = self.canonicalizer.dedup_cache.cache
            old_idempotency = self.idempotency_store.store
            try:
                staged_windows = self.rolling_window_manager.stage_appends(accepted_signals)

                if accepted_signals and staged_state is not None:
                    previous_state = self.latest_state_manager.swap_state(staged_state)
                    state_swapped = True
                self.canonicalizer.commit_staged(staged_dedup)
                previous_windows = self.rolling_window_manager.swap_staged(staged_windows)
                windows_swapped = True
                self.idempotency_store.commit_staged(staged_idempotency)
            except BaseException:
                # Rollback is synchronous and cannot itself be interrupted by task
                # cancellation. It restores the exact prior object references.
                self.canonicalizer.dedup_cache.cache = old_dedup
                self.idempotency_store.store = old_idempotency
                if windows_swapped:
                    self.rolling_window_manager.restore_entries(previous_windows)
                if state_swapped:
                    self.latest_state_manager.restore_state(
                        (vehicle_id, trip_id),
                        previous_state,
                    )
                raise
            finally:
                self.rolling_window_manager.release_transaction()

            return business_result
