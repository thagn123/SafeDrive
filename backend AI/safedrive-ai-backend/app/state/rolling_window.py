import asyncio
import bisect
import datetime
from collections.abc import Callable, Iterable

from app.domain.models.signal import CanonicalSignal
from app.ingestion.registry import SignalRegistry


class RollingWindow:
    def __init__(self, max_length: int = 1000, ttl_seconds: float = 60.0) -> None:
        if max_length <= 0:
            raise ValueError("max_length must be positive")
        if ttl_seconds <= 0:
            raise ValueError("ttl_seconds must be positive")

        self.max_length = max_length
        self.ttl_seconds = ttl_seconds
        self._items: list[CanonicalSignal] = []

    @staticmethod
    def _sort_key(s: CanonicalSignal) -> tuple[datetime.datetime, int, str, str]:
        return (s.occurred_at, s.sequence, s.source.value, s.signal_id)

    def append(self, signal: CanonicalSignal, now: datetime.datetime) -> None:
        # Insert chronologically by (occurred_at, sequence, source, signal_id)
        bisect.insort(self._items, signal, key=self._sort_key)

        # Enforce max length by event-time (drop oldest)
        while len(self._items) > self.max_length:
            self._items.pop(0)

        self.prune(now)

    def prune(self, now: datetime.datetime) -> int:
        """Remove signals older than ttl_seconds from the left. Return remaining count."""
        if not self._items:
            return 0

        ttl_delta = datetime.timedelta(seconds=self.ttl_seconds)
        cutoff = now - ttl_delta

        # occurred_at must be timezone-aware UTC per CanonicalSignal validation
        while self._items and self._items[0].occurred_at < cutoff:
            self._items.pop(0)

        return len(self._items)

    def is_empty(self) -> bool:
        return len(self._items) == 0

    def get_window(self, duration_seconds: float, now: datetime.datetime) -> list[CanonicalSignal]:
        """Return signals that occurred within the last duration_seconds."""
        if duration_seconds <= 0:
            raise ValueError("duration_seconds must be positive")

        self.prune(now)
        duration_delta = datetime.timedelta(seconds=duration_seconds)
        start_cutoff = now - duration_delta

        result = []
        for sig in self._items:
            if start_cutoff <= sig.occurred_at <= now:
                # Return deep copies so window state cannot be mutated by caller
                result.append(sig.model_copy(deep=True))
        return result

    def clone(self) -> "RollingWindow":
        cloned = RollingWindow(max_length=self.max_length, ttl_seconds=self.ttl_seconds)
        cloned._items = [signal.model_copy(deep=True) for signal in self._items]
        return cloned


class WindowEntry:
    def __init__(self, max_length: int, ttl_seconds: float) -> None:
        self.lock = asyncio.Lock()
        self.active_users = 0
        self.window = RollingWindow(max_length=max_length, ttl_seconds=ttl_seconds)

    def clone(self) -> "WindowEntry":
        cloned = WindowEntry(
            max_length=self.window.max_length,
            ttl_seconds=self.window.ttl_seconds,
        )
        cloned.window = self.window.clone()
        return cloned


class RollingWindowManager:
    def __init__(
        self,
        registry: SignalRegistry,
        clock: Callable[[], datetime.datetime] | None = None,
    ) -> None:
        self.registry = registry
        self.clock = clock

        # Serializes transactional staging/commit with reads and background pruning.
        self._transaction_lock = asyncio.Lock()
        # Short-lived index lock protecting entry dictionary lookups and ref counts
        self._index_lock = asyncio.Lock()
        # keys are (vehicle_id, trip_id, signal_type)
        self.entries: dict[tuple[str, str, str], WindowEntry] = {}

    def _now(self) -> datetime.datetime:
        if self.clock:
            return self.clock()
        return datetime.datetime.now(datetime.UTC)

    def is_configured(self, signal_type: str) -> bool:
        policy = self._get_policy(signal_type)
        return policy is not None

    def _get_policy(self, signal_type: str) -> tuple[int, float] | None:
        sig_def = self.registry.signals.get(signal_type)
        if sig_def is None:
            raise ValueError("Unknown signal type")
        max_length = sig_def.window_max_length
        ttl_seconds = sig_def.window_ttl_seconds

        if max_length is None or ttl_seconds is None:
            return None

        return max_length, ttl_seconds

    async def acquire_transaction(self) -> None:
        """Acquire the rolling-store transaction boundary before any live mutation."""
        await self._transaction_lock.acquire()

    def release_transaction(self) -> None:
        self._transaction_lock.release()

    def stage_appends(
        self,
        signals: Iterable[CanonicalSignal],
    ) -> dict[tuple[str, str, str], WindowEntry]:
        """Build changed entries while the transaction lock is held."""
        if not self._transaction_lock.locked():
            raise RuntimeError("Rolling window transaction lock is required")

        staged: dict[tuple[str, str, str], WindowEntry] = {}
        now = self._now()
        for signal in signals:
            policy = self._get_policy(signal.signal_type)
            if policy is None:
                continue
            max_length, ttl_seconds = policy
            key = (signal.vehicle_id, signal.trip_id, signal.signal_type)
            entry = staged.get(key)
            if entry is None:
                live_entry = self.entries.get(key)
                entry = (
                    live_entry.clone()
                    if live_entry is not None
                    else WindowEntry(max_length=max_length, ttl_seconds=ttl_seconds)
                )
                staged[key] = entry
            entry.window.append(signal.model_copy(deep=True), now)
        return staged

    def swap_staged(
        self,
        staged: dict[tuple[str, str, str], WindowEntry],
    ) -> dict[tuple[str, str, str], WindowEntry | None]:
        previous: dict[tuple[str, str, str], WindowEntry | None] = {}
        for key, entry in staged.items():
            previous[key] = self.entries.get(key)
            self.entries[key] = entry
        return previous

    def restore_entries(
        self,
        previous: dict[tuple[str, str, str], WindowEntry | None],
    ) -> None:
        for key, entry in previous.items():
            if entry is None:
                self.entries.pop(key, None)
            else:
                self.entries[key] = entry

    async def append_if_configured(self, signal: CanonicalSignal) -> bool:
        """Append signal if feature rolling window policy is configured.

        Return True if stored in rolling window, False if unconfigured (SKIPPED).
        """
        await self.acquire_transaction()
        try:
            policy = self._get_policy(signal.signal_type)
            if policy is None:
                return False
            staged = self.stage_appends([signal])
            self.swap_staged(staged)
            return True
        finally:
            self.release_transaction()

    async def append(self, signal: CanonicalSignal) -> None:
        """Legacy append wrapper for backwards compatibility."""
        await self.append_if_configured(signal)

    async def get_window(
        self, vehicle_id: str, trip_id: str, signal_type: str, duration_seconds: float
    ) -> list[CanonicalSignal]:
        if duration_seconds <= 0:
            raise ValueError("duration_seconds must be positive")

        key = (vehicle_id, trip_id, signal_type)
        await self.acquire_transaction()
        try:
            entry = self.entries.get(key)
            if entry is None:
                return []
            now = self._now()
            result = entry.window.get_window(duration_seconds, now)
            if entry.window.is_empty():
                self.entries.pop(key, None)
            return result
        finally:
            self.release_transaction()

    async def prune_all(self) -> None:
        await self.acquire_transaction()
        try:
            now = self._now()
            empty_keys: list[tuple[str, str, str]] = []
            for key, entry in self.entries.items():
                if entry.active_users > 0:
                    continue
                entry.window.prune(now)
                if entry.window.is_empty():
                    empty_keys.append(key)
            for key in empty_keys:
                self.entries.pop(key, None)
        finally:
            self.release_transaction()
