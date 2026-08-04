# Task Status Matrix

Complete project task matrix based on the Authoritative Master Implementation Plan V2 (52 tasks).

| Task ID | Task Name | Status | Evidence / Notes |
| --- | --- | --- | --- |
| SD-0001 | Chốt capability profile | DONE | Every P0 signal has typed owner, source, simulated flag, and positive TTL; registry and fixture validation pass |
| SD-0002 | Khóa scope và success metrics | PARTIAL | Scope doc includes 10 E2E scenarios; required owner sign-off pending |
| SD-0003 | Khóa contract v1 | PARTIAL | REST Slice 1 OpenAPI/examples validate and Android parses pinned backend fixtures; WS and later-slice contracts remain |
| SD-0004 | Safety policy baseline | NOT_STARTED | `configs/risk_rules.yaml` and `configs/tool_policies.yaml` do not exist |
| SD-0101 | Khởi tạo repository | PARTIAL | Repository initialized, pyproject.toml and package layout exist; baseline git commit pending |
| SD-0102 | FastAPI app factory | DONE | App factory, lifespan, `/health`, `/ready` endpoints, HTTPX tests |
| SD-0103 | Config và secret boundary | DONE | Typed `Settings`, `.env.example`, `SecretStr` redaction, startup validation |
| SD-0104 | Logging/error/request context | DONE | Structured JSON logging, `X-Request-ID` correlation, unified `ErrorEnvelope` handlers |
| SD-0105 | Docker foundation | NOT_STARTED | Containerization setup |
| SD-0106 | CI quality gate | PARTIAL | Pinned CI workflow, retained artifacts, and installed-wheel startup smoke are implemented; hosted PR run is still pending |
| SD-0201 | Canonical models/registry | DONE | Discriminated inputs, correct value schemas (`PedalValue`, `StringValue`), full E2E 25-signal tests |
| SD-0202 | Dedup/order/quarantine | DONE | `Canonicalizer` with 4-tuple key `(vehicle_id, trip_id, source, signal_id)` LRU cache |
| SD-0203 | Latest State Manager | DONE | `LatestStateManager` with 2-tuple key `(vehicle_id, trip_id)`, sequence ordering & freshness |
| SD-0204 | Rolling windows | DONE | Event-time `RollingWindowManager` with bounded policies, transaction serialization, TTL pruning, and late/purge regression tests |
| SD-0205 | Signal/state REST slice | DONE | Authenticated POST signals and GET state flow is atomic/idempotent; live Android network test and 130 backend tests pass |
| SD-0206 | WS state broadcaster | NOT_STARTED | Realtime WebSocket state stream |
| SD-0301 | Risk engine core | NOT_STARTED | Safety evaluator interface & policy loader |
| SD-0302 | Driver evaluator | NOT_STARTED | DMS_DEMO vs NO_DMS driver risk rules |
| SD-0303 | Passenger evaluator | NOT_STARTED | Occupancy & posture risk rules |
| SD-0304 | DTC evaluator/catalog | NOT_STARTED | Diagnostic trouble code risk evaluator |
| SD-0305 | Post-crash evaluator | NOT_STARTED | Crash severity & evidence freeze |
| SD-0306 | Risk/WS integration | NOT_STARTED | Risk commit integration |
| SD-0401 | Deterministic intent parser | NOT_STARTED | Intent parsing & entity extraction |
| SD-0402 | Context builder | NOT_STARTED | Context pack builder |
| SD-0403 | Mock LLM provider | NOT_STARTED | Offline LLM provider implementation |
| SD-0404 | Cloud provider adapter | NOT_STARTED | Cloud LLM provider adapter |
| SD-0405 | Deterministic fallback | NOT_STARTED | Safety fallback messages |
| SD-0406 | Chat orchestration | NOT_STARTED | Assistant chat pipeline |
| SD-0501 | Tool registry/schemas | NOT_STARTED | Vehicle tool definitions |
| SD-0502 | Guardrail pipeline | NOT_STARTED | Tool safety guardrails |
| SD-0503 | Confirmation service | NOT_STARTED | Action confirmation TTL |
| SD-0504 | Mock vehicle tools | NOT_STARTED | Vehicle tool execution mocks |
| SD-0505 | Tool execution/audit | NOT_STARTED | Tool execution logging |
| SD-0601 | SOS state model/transitions | NOT_STARTED | Emergency state machine |
| SD-0602 | Frozen crash snapshot | NOT_STARTED | Incident snapshot persistence |
| SD-0603 | Async countdown/ticks | NOT_STARTED | SOS dispatch timer |
| SD-0604 | SOS API + simulator | NOT_STARTED | SOS REST endpoints |
| SD-0605 | SOS WS/audit integration | NOT_STARTED | SOS event broadcasting |
| SD-0701 | Android contract models | PARTIAL | REST Slice 1 DTO/error models parse copied backend fixtures and ignore additive fields; later contracts remain |
| SD-0702 | REST screens/actions | PARTIAL | Compose state screen covers health/readiness/speed/state/error and debug sample send; chat, DTC, and SOS are out of this slice |
| SD-0703 | WS/reconnect client | NOT_STARTED | Android WebSocket client |
| SD-0704 | Device connectivity setup | PARTIAL | Debug base URL/API-key config and adb reverse/LAN runbook exist; live JVM REST flow passes but no device was attached for two-mode smoke |
| SD-0801 | Audit repository | NOT_STARTED | SQLite audit log |
| SD-0802 | Contract suite | NOT_STARTED | API contract test suite |
| SD-0803 | Safety adversarial suite | NOT_STARTED | Safety policy robustness tests |
| SD-0804 | 10 E2E scenarios | NOT_STARTED | 10 end-to-end integration tests |
| SD-0805 | Performance test | NOT_STARTED | Ingestion benchmark tests |
| SD-0806 | Security hardening | NOT_STARTED | Security audit & hardening |
| SD-0901 | Local deployment runbook | NOT_STARTED | Local runbook documentation |
| SD-0902 | Cloud staging option | NOT_STARTED | GCP deployment guide |
| SD-0903 | Demo data/offline pack | NOT_STARTED | Offline demonstration dataset |
| SD-0904 | Final release/rehearsal | NOT_STARTED | Final release verification |
