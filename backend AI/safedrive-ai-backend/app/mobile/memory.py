"""Bounded, provenance-aware episodic context memory for SafeDrive."""

from __future__ import annotations

import hashlib
import uuid
from dataclasses import dataclass
from typing import Protocol
from urllib.parse import quote

import httpx


@dataclass(frozen=True, slots=True)
class MemoryFact:
    kind: str
    summary: str
    source: str
    created_at_ms: int
    expires_at_ms: int
    fact_id: str = ""


class ContextMemory(Protocol):
    async def append(self, device_id: str, fact: MemoryFact) -> None: ...

    async def recent(
        self, device_id: str, *, now_ms: int, limit: int = 8
    ) -> tuple[MemoryFact, ...]: ...


class InMemoryContextMemory:
    def __init__(self) -> None:
        self._facts: dict[str, list[MemoryFact]] = {}

    async def append(self, device_id: str, fact: MemoryFact) -> None:
        facts = self._facts.setdefault(device_id, [])
        facts.append(fact)
        self._facts[device_id] = facts[-100:]

    async def recent(
        self, device_id: str, *, now_ms: int, limit: int = 8
    ) -> tuple[MemoryFact, ...]:
        alive = [fact for fact in self._facts.get(device_id, []) if fact.expires_at_ms > now_ms]
        alive.sort(key=lambda fact: fact.created_at_ms, reverse=True)
        return tuple(alive[:limit])


class FirestoreContextMemory:
    """Firestore REST adapter using the Cloud Run service account metadata token.

    Only bounded summaries are persisted. Raw transcripts and complete telemetry payloads are
    deliberately excluded from this boundary.
    """

    _METADATA_TOKEN_URL = (
        "http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/token"
    )

    def __init__(
        self,
        *,
        project_id: str,
        database_id: str,
        timeout_seconds: float = 3.0,
        collection: str = "safedrive_context_memory",
    ) -> None:
        self.project_id = project_id
        self.database_id = database_id
        self.timeout_seconds = timeout_seconds
        self.collection = collection

    async def append(self, device_id: str, fact: MemoryFact) -> None:
        token = await self._access_token()
        if token is None:
            return
        device_key = self._device_key(device_id)
        fact_id = fact.fact_id or f"fact_{fact.created_at_ms}_{uuid.uuid4().hex[:12]}"
        url = (
            f"https://firestore.googleapis.com/v1/projects/{quote(self.project_id)}/"
            f"databases/{quote(self.database_id)}/documents/{self.collection}/{device_key}/events"
        )
        params = {"documentId": fact_id}
        payload = {
            "fields": {
                "kind": {"stringValue": fact.kind[:64]},
                "summary": {"stringValue": fact.summary[:500]},
                "source": {"stringValue": fact.source[:128]},
                "createdAtMs": {"integerValue": str(fact.created_at_ms)},
                "expiresAtMs": {"integerValue": str(fact.expires_at_ms)},
            }
        }
        async with httpx.AsyncClient(timeout=self.timeout_seconds) as client:
            response = await client.post(
                url, params=params, json=payload, headers={"Authorization": f"Bearer {token}"}
            )
            if response.status_code not in {200, 409}:
                response.raise_for_status()

    async def recent(
        self, device_id: str, *, now_ms: int, limit: int = 8
    ) -> tuple[MemoryFact, ...]:
        token = await self._access_token()
        if token is None:
            return ()
        device_key = self._device_key(device_id)
        url = (
            f"https://firestore.googleapis.com/v1/projects/{quote(self.project_id)}/"
            f"databases/{quote(self.database_id)}/documents/{self.collection}/{device_key}/events"
        )
        async with httpx.AsyncClient(timeout=self.timeout_seconds) as client:
            response = await client.get(
                url,
                params={"pageSize": "100"},
                headers={"Authorization": f"Bearer {token}"},
            )
            if response.status_code == 404:
                return ()
            response.raise_for_status()
        facts: list[MemoryFact] = []
        for document in response.json().get("documents", []):
            fields = document.get("fields", {})
            fact = MemoryFact(
                kind=fields.get("kind", {}).get("stringValue", "unknown"),
                summary=fields.get("summary", {}).get("stringValue", ""),
                source=fields.get("source", {}).get("stringValue", "firestore"),
                created_at_ms=int(fields.get("createdAtMs", {}).get("integerValue", "0")),
                expires_at_ms=int(fields.get("expiresAtMs", {}).get("integerValue", "0")),
                fact_id=document.get("name", "").rsplit("/", 1)[-1],
            )
            if fact.summary and fact.expires_at_ms > now_ms:
                facts.append(fact)
        facts.sort(key=lambda item: item.created_at_ms, reverse=True)
        return tuple(facts[:limit])

    async def _access_token(self) -> str | None:
        try:
            async with httpx.AsyncClient(timeout=self.timeout_seconds) as client:
                response = await client.get(
                    self._METADATA_TOKEN_URL,
                    headers={"Metadata-Flavor": "Google"},
                )
                response.raise_for_status()
            token = response.json().get("access_token")
            return token if isinstance(token, str) and token else None
        except (httpx.HTTPError, ValueError, TypeError):
            return None

    @staticmethod
    def _device_key(device_id: str) -> str:
        return hashlib.sha256(device_id.encode("utf-8")).hexdigest()[:40]


def memory_fact(
    *, kind: str, summary: str, source: str, now_ms: int, ttl_ms: int
) -> MemoryFact:
    return MemoryFact(
        kind=kind,
        summary=summary,
        source=source,
        created_at_ms=now_ms,
        expires_at_ms=now_ms + ttl_ms,
    )
