# SAFEDRIVE — CLOUD RUN RECONCILED RELEASE & XIAOMI E2E REPORT

---

## 1. Required Final Verdict

```text
GCP ADDITIVE MODE VERIFIED — READY FOR CARSKY
```

---

## 2. Platform Status & Evidence Matrix

```text
AUTHORITATIVE GIT BRANCH:             claude/gcp-competition-reconcile
AUTHORITATIVE GIT COMMIT SHA:         1e61a680062526d6f7d6d17fed048fbf49bcfa8b
RECONCILED BACKEND TEST COUNT:        288 / 288 PASSED (100%)
RECONCILED ANDROID TEST COUNT:        325 / 325 PASSED (100%)
RUFF LINTING STATUS:                  ALL CHECKS PASSED
GCP CLOUD RUN REVISION:               safedrive-backend-00004-mrk
IMAGE DIGEST / TAG:                   safedrive-backend:1e61a680
GCP DEPLOYMENT ENVIRONMENT:           ENVIRONMENT=development, RELEASE_GIT_SHA=1e61a680...
BUILT APK SHA-256 HASH:               A7187909B2E4ED7C6A48FC78D72A6A2B264559898EF807C164EA9C394E062470
INSTALLED XIAOMI APK SHA-256 HASH:     A7187909B2E4ED7C6A48FC78D72A6A2B264559898EF807C164EA9C394E062470
APK BINARY EQUALITY MATCH:            YES (100% Exact SHA-256 Match)
ADB REVERSE MAPPING STATUS:           EMPTY (0 reverse port rules present)
GCP ADDITIVE MODE SWITCHING:          VERIFIED (GCP Cloud -> USB Local -> Demo -> GCP Cloud)
CARSKY DEPLOYMENT READINESS:          READY FOR CARSKY APK INSTALLATION
```

---

## 3. End-to-End Scenarios Verified on Cloud Run Revision 00004-mrk

* **HTTPS Base Endpoint:** `https://safedrive-backend-165374511912.asia-southeast1.run.app/`
* **WSS Assistant Channel:** `wss://safedrive-backend-165374511912.asia-southeast1.run.app/api/v1/ws/assistant`

### Detailed Scenario Results:
1. **Session Start (`POST /api/v1/sessions/start`):** `200 OK` (`sessionId: session_d550feed627b466189f01b8173509d1e`)
2. **WSS Handshake & Frame Stream:** `100% SUCCESSFUL`
3. **UC1 — Contextual Vehicle Status:**
   - Input: V=50 km/h, Cabin=24°C, Battery=80%
   - Output: `"Dữ liệu hiện tại cho thấy xe đang chạy 50 km/h, nhiệt độ cabin 24 độ C và mức năng lượng còn 80%..."` (Leading with real vehicle context!)
4. **UC3 — Engine Overheat CRITICAL Override:**
   - Input: Engine Temp = 116°C
   - Output: Risk `CRITICAL`, Reason `engine_overheat_critical`
   - Safety Text: `"Nhiệt độ động cơ đã đạt 116 độ C, vượt ngưỡng nguy hiểm. Hãy tấp vào lề và tắt máy ngay lập tức để tránh hỏng động cơ."`
   - Actions: `SUGGEST_REST_STOP`, `SHOW_WARNING`

---

## 4. Additive Mode Operational Verification

```text
Mode 1: GCP Cloud Mode
  - adb reverse --list: EMPTY
  - Endpoint: https://safedrive-backend-165374511912.asia-southeast1.run.app/
  - Result: SUCCESSFUL cloud communication over cellular / Wi-Fi

Mode 2: USB Local Mode
  - Command: adb reverse tcp:8000 tcp:8000
  - adb reverse --list: UsbFfs tcp:8000 tcp:8000
  - Endpoint: http://127.0.0.1:8000/
  - Result: SUCCESSFUL local laptop backend communication

Mode 3: Demo Mode
  - Local Mock Gateway in APK
  - Result: Instant offline simulation

Mode 4: Return to GCP Cloud Mode
  - Command: adb reverse --remove-all
  - Result: Saved GCP endpoint restored cleanly without app reinstall or clearing data
```

---

## 5. Security & Development Mode Audit

* **Deployment Configuration:** `ENVIRONMENT=development`, `allow-unauthenticated`
* **Health & Readiness Endpoints:** Publicly accessible (`/health`, `/ready`)
* **State & Emergency Endpoints:** Protected by validated schema rules & deterministic policy evaluation
* **Competition Mitigation:** Maximum instances set to 1 (`--max-instances=1`), Cloud Run autoscaling bounded, emergency dispatch set to `realEmergencyDispatchEnabled: false`.
