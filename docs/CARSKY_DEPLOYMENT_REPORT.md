# SAFEDRIVE — RECONCILED RELEASE & CLOUD AI INTEGRATION REPORT

---

## 1. Required Final Verdict

```text
GCP SAFETY BACKEND VERIFIED — CLOUD AI OR ANDROID E2E PENDING
```

---

## 2. Source Identity & Deployment Provenance

```text
RUNTIME_SOURCE_SHA:                    84ffdb908cd53086eb02ce5fa4fdd1f016f4ef82
EVIDENCE_REPORT_SHA:                  50d2cf34d98ca8a067a99f7d2e7aa2dfd7fb6b88
RECONCILED BACKEND TEST SUITE:        288 / 288 PASSED (100% in 35.33s)
RECONCILED ANDROID TEST SUITE:        325 / 325 PASSED (100%)
RUFF LINTING STATUS:                  ALL CHECKS PASSED
GCP CLOUD RUN REVISION:               safedrive-backend-00005-s9q
DEPLOYED SOURCE SHA:                  84ffdb908cd53086eb02ce5fa4fdd1f016f4ef82
GCP DEPLOYMENT ENVIRONMENT:           ENVIRONMENT=development, RELEASE_GIT_SHA=84ffdb90..., LLM_PROVIDER=gemini
BUILT APK SHA-256 HASH:               A7187909B2E4ED7C6A48FC78D72A6A2B264559898EF807C164EA9C394E062470
INSTALLED XIAOMI APK SHA-256 HASH:     A7187909B2E4ED7C6A48FC78D72A6A2B264559898EF807C164EA9C394E062470
BINARY APK EQUALITY MATCH:            YES (100% Exact SHA-256 Match via ADB pull)
ADB REVERSE MAPPING STATUS:           EMPTY (0 reverse port rules present on Xiaomi b07e7713)
```

---

## 3. End-to-End WSS Scenarios Evidence on Revision 00005-s9q

* **HTTPS BASE_URL:** `https://safedrive-backend-165374511912.asia-southeast1.run.app/`
* **WSS Assistant URL:** `wss://safedrive-backend-165374511912.asia-southeast1.run.app/api/v1/ws/assistant`

### Detailed Scenario Results:
1. **Session Start (`POST /api/v1/sessions/start`):** `200 OK` (`sessionId: session_c910d1ea2445469f882f50bbbbe02ee1`)
2. **WSS Handshake & Stream:** `100% SUCCESSFUL`
3. **UC1 — Contextual Vehicle Status:**
   - Input State: V=50 km/h, Cabin=24°C, Battery=80%
   - Output Text: `"Dữ liệu hiện tại cho thấy xe đang chạy 50 km/h, nhiệt độ cabin 24 độ C và mức năng lượng còn 80%..."` (Leading with real vehicle context!)
   - `llmUsed`: `false`, `fallback`: `true`, `fallbackReason`: `"provider_unavailable"` (Clean deterministic fallback when API key is unconfigured)
4. **UC3 — Engine Overheat CRITICAL Override (Corrected Action Mapping):**
   - Input State: Engine Temp = 116°C
   - Output: Risk `CRITICAL`, Reason `engine_overheat_critical`
   - Safety Message: `"Nhiệt độ động cơ đã đạt 116 độ C, vượt ngưỡng nguy hiểm. Hãy tấp vào lề và tắt máy ngay lập tức để tránh hỏng động cơ."`
   - Allowed Actions: `[{"type": "SHOW_WARNING", "title": "Hiển thị cảnh báo an toàn"}]` (Fatigue action `SUGGEST_REST_STOP` correctly removed!)

---

## 4. Additive Mode Operational Verification

```text
Mode 1: GCP Cloud Mode
  - adb reverse --list: EMPTY
  - Endpoint: https://safedrive-backend-165374511912.asia-southeast1.run.app/
  - Result: Cloud Run HTTP/WSS communication verified

Mode 2: USB Local Mode
  - Command: adb reverse tcp:8000 tcp:8000
  - adb reverse --list: UsbFfs tcp:8000 tcp:8000
  - Endpoint: http://127.0.0.1:8000/
  - Result: Laptop backend & local Ollama communication verified

Mode 3: Demo Mode
  - Selected Profile: Demo
  - Result: Local Mock Gateway in APK verified

Mode 4: Return to GCP Cloud Mode
  - Command: adb reverse --remove-all
  - Result: Saved GCP Cloud profile restored cleanly without app reinstall or clearing app data
```

---

## 5. Security & Development Mode Audit

* **Deployment Configuration:** `ENVIRONMENT=development`, `allow-unauthenticated`
* **Public Endpoints:** `/health`, `/ready`
* **State & Emergency Endpoints:** Protected by validated schema rules & deterministic policy evaluation
* **Emergency Safety:** `realEmergencyDispatchEnabled: false` (Simulation-only SOS). Max instances set to 1.
