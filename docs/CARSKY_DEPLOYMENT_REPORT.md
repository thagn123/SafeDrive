# SAFEDRIVE — DEPLOYMENT, EVIDENCE AND CARSKY SUBMISSION REPORT

---

## 1. Classification of Deployment and Platform Status

| Category | Status | Evidence & Verification Notes |
|---|---|---|
| **LOCAL DEVICE DEPLOYMENT** | **VERIFIED** | Android application running on physical Xiaomi device (`b07e7713`), connected to local Docker FastAPI backend via ADB port reverse (`adb reverse tcp:8000 tcp:8000`). |
| **CARSKY PORTAL ACCESS** | **VERIFIED** | Authenticated team workspace access confirmed for team `Haui AVS` at `https://hackathon-2.carsky.io/` (Keycloak Realm `hackathon02`). |
| **CARSKY ARTIFACT UPLOAD** | **NOT VERIFIED** | Artifact submission portal fields inspected (`MAX_SKYCRAFT_PER_BLUEPRINT: 2`, `MAX_BLUEPRINTS_PER_ACCOUNT: 20`). Upload receipt pending final submission action. |
| **CARSKY SUBMISSION** | **NOT VERIFIED** | Official submission receipt pending final artifact upload step. |
| **CARSKY ANDROID/AAOS RUNTIME** | **NOT VERIFIED** | No CarSky hosted AAOS virtual instance was executed; Android client ran natively on physical hardware. |
| **CARSKY BACKEND RUNTIME** | **NOT VERIFIED** | FastAPI backend container ran in local Docker Desktop environment (`Up (healthy)`), not on a CarSky cloud VM. |
| **CARSKY VEHICLE SIGNAL INTEGRATION** | **NOT VERIFIED** | Signals originated from SafeDrive local Simulator / VHAL bridge, not live CarSky CAN bus hardware. |

---

## 2. CarSky Platform Operating Mode

```text
SELECTED MODE: MODE D — SUBMISSION-ONLY PORTAL
```

* **Platform Analysis:**
  - CarSky web portal (`https://hackathon-2.carsky.io/`) acts as the project workspace and blueprint submission platform (`MAX_SKYCRAFT_PER_BLUEPRINT: 2`, `MAX_NODES_PER_BLUEPRINT: 30`, `MAX_DEVICES: 5`).
  - SafeDrive backend and AI model execute locally on GPU workstation + physical Android test hardware.

---

## 3. Local Verification Evidence Matrix

| Scenario | Evidence Type | Timestamp | Commit SHA | APK SHA-256 | Docker Image / Port | Actual Result | Status |
|---|---|---|---|---|---|---|---|
| **1. Session & State** | `ANDROID_UI` + `BACKEND_API_ONLY` | 2026-08-05 19:03 | `15e8d23` | `1C4F3A56A09ECC...` | `safedrive-ai-backend` (8000) | Session created, state version updated | **PASS** |
| **2. Normal LLM** | `ANDROID_UI` + `LOGCAT_ONLY` | 2026-08-05 19:04 | `15e8d23` | `1C4F3A56A09ECC...` | `safedrive-ai-backend` (8000) | Grounded LLM reply (`llmUsed=true`, `fallback=false`) | **PASS** |
| **3. Rest Recommendation** | `ANDROID_UI` | 2026-08-05 19:04 | `15e8d23` | `1C4F3A56A09ECC...` | `safedrive-ai-backend` (8000) | Fatigue warning card displayed (>240 min) | **PASS** |
| **4. Engine HIGH (110°C)** | `ANDROID_UI` | 2026-08-05 19:04 | `15e8d23` | `1C4F3A56A09ECC...` | `safedrive-ai-backend` (8000) | Immediate deterministic warning | **PASS** |
| **5. Engine CRITICAL (116°C)**| `ANDROID_UI` | 2026-08-05 19:05 | `15e8d23` | `1C4F3A56A09ECC...` | `safedrive-ai-backend` (8000) | Deterministic stop warning (LLM bypassed) | **PASS** |
| **6. DTC Code `U0100`** | `ANDROID_UI` + `UNIT_TEST_ONLY` | 2026-08-05 19:05 | `15e8d23` | `1C4F3A56A09ECC...` | `safedrive-ai-backend` (8000) | Preserved exact code without hallucination | **PASS** |
| **7. Crash & SOS** | `ANDROID_UI` | 2026-08-05 19:05 | `15e8d23` | `1C4F3A56A09ECC...` | `safedrive-ai-backend` (8000) | 10s countdown -> `SOS_SIMULATED_SENT` | **PASS** |
| **8. Fallback (Unavailable)** | `BACKEND_API_ONLY` + `UNIT_TEST_ONLY` | 2026-08-05 19:06 | `15e8d23` | `1C4F3A56A09ECC...` | `safedrive-ai-backend` (8000) | `fallbackReason=provider_unavailable` | **PASS** |
| **9. Fallback (Timeout)** | `BACKEND_API_ONLY` + `UNIT_TEST_ONLY` | 2026-08-05 19:06 | `15e8d23` | `1C4F3A56A09ECC...` | `safedrive-ai-backend` (8000) | `fallbackReason=provider_timeout` | **PASS** |

---

## 4. Disambiguation of Provider Fallback Failure Modes

1. **`provider_unavailable`:**
   - **Condition:** Ollama server is completely stopped or port 11434 is unreachable (`ConnectionRefusedError`).
   - **Behavior:** Backend instantly returns deterministic response with `fallback=true` and `fallbackReason="provider_unavailable"`.

2. **`provider_timeout`:**
   - **Condition:** Ollama server is running but LLM inference latency exceeds the configured timeout threshold (or WebSocket timeout).
   - **Behavior:** Backend returns deterministic response with `fallback=true` and `fallbackReason="provider_timeout"`.

---

## 5. Artifact Consistency Checklist

* **Pushed Git Commit:** `15e8d234ff86394f514fe12f84c06d2c17a9029b`
* **APK File Location:** `safedrive-ai (1)/android/app/build/outputs/apk/debug/app-debug.apk`
* **APK SHA-256 Hash:** `1C4F3A56A09ECC61E4ED94D538B3203E0B1EF4B1FA8147822D54A575B17C16CD`
* **Docker Image ID:** `safedrive-ai-backend-backend:latest`
* **Test Suite State:**
  - Backend pytest: **274 / 274 PASSED** (100%)
  - Backend ruff check: **Clean** (0 errors)
  - Android unit tests: **325 / 325 PASSED** (100%)

---

## 6. GitHub Branch Status (Phase 13)

```bash
git fetch origin --prune
git status --short
# HEAD SHA: 15e8d234ff86394f514fe12f84c06d2c17a9029b
# Remote SHA: 15e8d234ff86394f514fe12f84c06d2c17a9029b (claude/carsky-deployment)
```

---

## 7. Exact Next User Action

1. **Upload submission artifacts** (APK `app-debug.apk`, repository URL, and presentation deck) to the CarSky portal (`https://hackathon-2.carsky.io/`).
2. **Execute final live demonstration** following the 5-scenario demo script using local GPU workstation + connected Android device.
