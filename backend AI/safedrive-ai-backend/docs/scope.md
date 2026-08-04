# SafeDrive AI Backend - Scope and Success Metrics

## 1. Scope Definition

### P0 (In Scope for Demo)
- FastAPI foundation, configuration, and structured logging.
- Canonical signal ingestion, deduplication, ordering, and quarantine.
- Latest state manager and event-time bounded rolling windows.
- In-memory state and SQLite audit storage.
- 4 Risk evaluators (Driver, Passenger, DTC, Post-crash).
- Deterministic intent fast path and bounded context engine.
- Guardrail pipeline and mock tool execution.
- SOS state machine (crash -> confirm/timeout -> simulated dispatch).
- Mock/Cloud LLM adapter with deterministic fallback.
- Local Docker deployment and 10 E2E automated scenarios.

### P1 (Post-Demo)
- Redis state adapter.
- PostgreSQL audit repository.
- Richer DTC catalog.
- JWT/OIDC authentication.
- Rate limiter.
- Conversation summary.
- Metrics exporter.
- Basic cloud staging.

### Out of Scope
- Real ECU control or autonomous driving.
- Medical diagnosis.
- Real emergency dispatch.
- Raw video/audio retention.
- Model training.
- Kafka, Kubernetes, microservices for the MVP.

## 2. 10 Mandatory E2E Scenarios (Section 28.1)
| ID | Scenario | Assertion |
| --- | --- | --- |
| E2E-01 | Tăng HVAC 23->24°C | Fast path; zero LLM; tool success/state event |
| E2E-02 | Hỏi trạng thái xe | Chỉ fact fresh; missing được nói rõ |
| E2E-03 | Đọc DTC P0301 | Severity backend; explanation bounded |
| E2E-04 | DMS demo Medium | MEDIUM + evidence; no medical claim |
| E2E-05 | DMS demo High | HIGH warning; no automatic SOS |
| E2E-06 | Rear passenger no-motion | Risk/warning/ask driver; evidence |
| E2E-07 | Crash, user cancels | CANCELLED; no payload; audit |
| E2E-08 | Crash Critical, no response | Countdown -> one simulated payload |
| E2E-09 | LLM timeout/malformed | Deterministic fallback; no unsafe tool |
| E2E-10 | WS disconnect/reconnect | Backoff + state/SOS resync; no duplicate action |

## 3. Success Metrics (KPIs)
- **End-to-End Demo**: 10 out of 10 E2E scenarios pass successfully.
- **Safety**: 0 sensitive tool bypasses; all decisions have evidence and are audited.
- **Latency**: Deterministic fast path < 500ms; overall system P95 < 1.5s.
- **Availability**: Fallback mechanisms function successfully if the LLM provider is down.
- **Code Quality**: 100% type-checked with MyPy, formatted with Ruff, and passing test suite.

## 4. Approval/Sign-off
- **Architect/Tech Lead**: ____________________ (Date: ________)
- **Product Owner**: ____________________ (Date: ________)
