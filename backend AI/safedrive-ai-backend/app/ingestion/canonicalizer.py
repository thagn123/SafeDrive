import datetime
import hashlib
import json
import time
from collections import OrderedDict
from collections.abc import Callable, Iterable

from app.domain.models.signal import CanonicalSignal, SignalQuality
from app.ingestion.registry import SignalRegistry

DedupKey = tuple[str, str, str, str]


class IngestionResult:
    def __init__(
        self, status_code: int, message: str, signal: CanonicalSignal | None = None
    ) -> None:
        self.status_code = status_code
        self.message = message
        self.signal = signal


class LRUCacheNode:
    def __init__(self, value: str, expires_at: float) -> None:
        self.value = value
        self.expires_at = expires_at


class LRUCache:
    def __init__(
        self,
        maxsize: int = 10000,
        ttl_seconds: float = 86400.0,
        clock: Callable[[], float] | None = None,
    ) -> None:
        self.cache: OrderedDict[DedupKey, LRUCacheNode] = OrderedDict()
        self.maxsize = maxsize
        self.ttl_seconds = ttl_seconds
        self.clock = clock

    def _now_monotonic(self) -> float:
        if self.clock:
            return float(self.clock())
        return time.monotonic()

    def get(self, key: DedupKey) -> str | None:
        now = self._now_monotonic()
        if key in self.cache:
            node = self.cache[key]
            if node.expires_at > now:
                self.cache.move_to_end(key)
                return node.value
            else:
                # Expired
                del self.cache[key]
        return None

    def peek(self, key: DedupKey) -> str | None:
        """Read a live entry without changing LRU order or deleting expired data."""
        node = self.cache.get(key)
        if node is None or node.expires_at <= self._now_monotonic():
            return None
        return node.value

    def put(self, key: DedupKey, value: str) -> None:
        now = self._now_monotonic()
        if key in self.cache:
            self.cache.move_to_end(key)
        expires_at = now + self.ttl_seconds
        self.cache[key] = LRUCacheNode(value, expires_at)
        if len(self.cache) > self.maxsize:
            self.cache.popitem(last=False)

    def stage_put_many(
        self,
        items: Iterable[tuple[DedupKey, str]],
    ) -> OrderedDict[DedupKey, LRUCacheNode]:
        """Build the post-commit cache without mutating the live cache."""
        now = self._now_monotonic()
        staged = OrderedDict(
            (key, node) for key, node in self.cache.items() if node.expires_at > now
        )
        for key, value in items:
            if key in staged:
                staged.move_to_end(key)
            staged[key] = LRUCacheNode(value=value, expires_at=now + self.ttl_seconds)
            if len(staged) > self.maxsize:
                staged.popitem(last=False)
        return staged

    def commit_staged(self, staged: OrderedDict[DedupKey, LRUCacheNode]) -> None:
        self.cache = staged


class Canonicalizer:
    def __init__(
        self,
        registry: SignalRegistry,
        clock: Callable[[], datetime.datetime] | None = None,
        monotonic_clock: Callable[[], float] | None = None,
    ) -> None:
        self.registry = registry
        self.dedup_cache = LRUCache(maxsize=10000, ttl_seconds=86400.0, clock=monotonic_clock)
        self.clock = clock

    def _now(self) -> datetime.datetime:
        if self.clock:
            return self.clock()
        return datetime.datetime.now(datetime.UTC)

    def compute_hash(self, signal: CanonicalSignal) -> str:
        """Hash client-owned fields using deterministic canonical JSON."""
        signal_data = signal.model_dump(mode="json", exclude={"received_at"})
        canonical_json = json.dumps(
            signal_data,
            ensure_ascii=False,
            separators=(",", ":"),
            sort_keys=True,
        )
        return hashlib.sha256(canonical_json.encode("utf-8")).hexdigest()

    @staticmethod
    def dedup_key(signal: CanonicalSignal) -> DedupKey:
        return (
            signal.vehicle_id,
            signal.trip_id,
            signal.source.value,
            signal.signal_id,
        )

    def classify(
        self, signal: CanonicalSignal, active_profile: str = "PRODUCTION_NO_DMS"
    ) -> IngestionResult:
        """Classify signal without mutating dedup cache (side-effect free)."""
        # 1. Basic Registry validation
        try:
            self.registry.validate_signal(signal, active_profile)
        except ValueError as e:
            return IngestionResult(422, f"Quarantined: {e!s}")

        # 2. Quality validation
        if signal.quality == SignalQuality.INVALID:
            return IngestionResult(422, "Quarantined: INVALID quality")

        # 3. Time validation
        now = self._now()
        age = (now - signal.occurred_at).total_seconds()

        # Future > 30s
        if age < -30:
            return IngestionResult(422, "Quarantined: Timestamp too far in future")

        # Lateness check
        sig_def = self.registry.signals[signal.signal_type]
        lateness = sig_def.allowed_lateness_seconds
        ttl = sig_def.ttl_seconds
        if age > (ttl + lateness):
            return IngestionResult(
                422,
                f"Quarantined: Signal too old (age {age}s > allowed {ttl + lateness}s)",
            )

        # 4. Deduplication classification via tuple partition key
        dedup_key = self.dedup_key(signal)
        sig_hash = self.compute_hash(signal)

        existing_hash = self.dedup_cache.peek(dedup_key)
        if existing_hash is not None:
            if existing_hash == sig_hash:
                return IngestionResult(202, "Duplicate ignored")
            else:
                return IngestionResult(409, "Conflict: Same ID but different body")

        return IngestionResult(202, "Accepted candidate", signal)

    def commit(self, signal: CanonicalSignal) -> None:
        """Record accepted signal in dedup cache."""
        dedup_key = self.dedup_key(signal)
        sig_hash = self.compute_hash(signal)
        self.dedup_cache.put(dedup_key, sig_hash)

    def stage_commits(
        self,
        signals: Iterable[CanonicalSignal],
    ) -> OrderedDict[DedupKey, LRUCacheNode]:
        commits = ((self.dedup_key(signal), self.compute_hash(signal)) for signal in signals)
        return self.dedup_cache.stage_put_many(commits)

    def commit_staged(
        self,
        staged: OrderedDict[DedupKey, LRUCacheNode],
    ) -> None:
        self.dedup_cache.commit_staged(staged)

    def process(
        self, signal: CanonicalSignal, active_profile: str = "PRODUCTION_NO_DMS"
    ) -> IngestionResult:
        """Backward-compatible single-step classify and commit."""
        res = self.classify(signal, active_profile)
        if res.status_code == 202 and res.message != "Duplicate ignored":
            self.commit(signal)
            res.message = "Accepted"
        return res
