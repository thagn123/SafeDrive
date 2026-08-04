import re
import time
from collections import OrderedDict
from collections.abc import Callable
from dataclasses import dataclass
from typing import Generic, TypeVar

VALID_IDEMPOTENCY_KEY_PATTERN = re.compile(r"^[A-Za-z0-9._:-]{1,128}$")

ResultT = TypeVar("ResultT")
IdempotencyStoreKey = tuple[str, str, str]


def is_valid_idempotency_key(key: str) -> bool:
    """Validate a bounded ASCII idempotency key without normalizing it."""
    return bool(VALID_IDEMPOTENCY_KEY_PATTERN.fullmatch(key))


@dataclass(frozen=True, slots=True)
class IdempotencyNode(Generic[ResultT]):
    payload_hash: str
    result: ResultT
    expires_at: float


class IdempotencyStore(Generic[ResultT]):
    """Bounded in-memory LRU storing immutable business outcomes."""

    def __init__(
        self,
        maxsize: int = 10000,
        ttl_seconds: float = 86400.0,
        clock: Callable[[], float] | None = None,
    ) -> None:
        self.store: OrderedDict[
            IdempotencyStoreKey,
            IdempotencyNode[ResultT],
        ] = OrderedDict()
        self.maxsize = maxsize
        self.ttl_seconds = ttl_seconds
        self.clock = clock

    def _now_monotonic(self) -> float:
        if self.clock:
            return float(self.clock())
        return time.monotonic()

    def peek(self, key: IdempotencyStoreKey) -> tuple[ResultT, str] | None:
        """Read a live result without mutating LRU state."""
        node = self.store.get(key)
        if node is None or node.expires_at <= self._now_monotonic():
            return None
        return node.result, node.payload_hash

    def get(self, key: IdempotencyStoreKey) -> tuple[ResultT, str] | None:
        """Compatibility read that refreshes LRU order."""
        result = self.peek(key)
        if result is not None:
            self.store.move_to_end(key)
        elif key in self.store:
            del self.store[key]
        return result

    def stage_put(
        self,
        key: IdempotencyStoreKey,
        payload_hash: str,
        result: ResultT,
    ) -> OrderedDict[IdempotencyStoreKey, IdempotencyNode[ResultT]]:
        now = self._now_monotonic()
        staged = OrderedDict(
            (stored_key, node) for stored_key, node in self.store.items() if node.expires_at > now
        )
        if key in staged:
            staged.move_to_end(key)
        staged[key] = IdempotencyNode(
            payload_hash=payload_hash,
            result=result,
            expires_at=now + self.ttl_seconds,
        )
        if len(staged) > self.maxsize:
            staged.popitem(last=False)
        return staged

    def commit_staged(
        self,
        staged: OrderedDict[IdempotencyStoreKey, IdempotencyNode[ResultT]],
    ) -> None:
        self.store = staged

    def put(
        self,
        key: IdempotencyStoreKey,
        payload_hash: str,
        result: ResultT,
    ) -> None:
        self.commit_staged(self.stage_put(key, payload_hash, result))
