# SAFEDRIVE — GOOGLE CLOUD DEPLOYMENT DOCUMENTATION

---

## 1. Cloud Infrastructure Summary

* **GCP Project ID:** `gen-lang-client-0307536353`
* **GCP Region:** `asia-southeast1` (Singapore)
* **Cloud Run Service Name:** `safedrive-backend`
* **Cloud Run Revision:** `safedrive-backend-00001-mxt`
* **Artifact Registry Repository:** `asia-southeast1-docker.pkg.dev/gen-lang-client-0307536353/cloud-run-source-deploy/safedrive-backend`
* **HTTPS BASE_URL:** `https://safedrive-backend-165374511912.asia-southeast1.run.app/`
* **WSS Assistant URL:** `wss://safedrive-backend-165374511912.asia-southeast1.run.app/api/v1/ws/assistant`
* **Service Configuration:**
  - **Memory:** 1 GiB
  - **CPU:** 1 vCPU
  - **Min Instances:** 0
  - **Max Instances:** 1 (Ensures in-memory session consistency)
  - **Authentication:** `--allow-unauthenticated` (Public HTTPS / WSS endpoint)

---

## 2. API Health Verification

* **Endpoint:** `GET https://safedrive-backend-165374511912.asia-southeast1.run.app/health`
* **Status Code:** `200 OK`
* **Verified Response Payload:**
  ```json
  {
    "status": "ok",
    "request_id": "req_f956381df8474de19c47d1f96808b838",
    "timestamp": "2026-08-05T13:21:38.220966Z",
    "schema_version": "1.0",
    "service": "SafeDrive AI Backend",
    "apiVersion": "1.0.0",
    "serverTimeMs": 1785936098220,
    "capabilities": {
      "assistant": true,
      "emergencySimulation": true,
      "cockpitStream": false
    }
  }
  ```

---

## 3. Additive Android Profile Integration

Android app settings supports additive profiles without overwriting existing local or emulator endpoints:
- `USB Local`: `http://127.0.0.1:8000/`
- `Emulator`: `http://10.0.2.2:8000/`
- `LAN Wi-Fi`: `http://192.168.1.15:8000/`
- `GCP Cloud`: `https://safedrive-backend-165374511912.asia-southeast1.run.app/`
- `Custom URL`: any HTTPS endpoint the user enters manually

(Verified against `app/src/main/.../feature/settings/SettingsUiState.kt`'s `baseUrlPresets`: there
is no shipped "Cloud Staging" preset or `api.example.com` placeholder in the app itself -- an
earlier draft of this document incorrectly listed one.)

---

## 4. Grounded Contextual Fallback

When running in Cloud Run without a local Ollama sidecar, the deterministic Safety Engine remains authoritative.
- High/Critical risk conditions (Engine temp $\ge 105^\circ\text{C}$ HIGH, $\ge 115^\circ\text{C}$ CRITICAL, active DTCs, SOS countdown) instantly return deterministic safety advice.
- Low/Medium risk queries return grounded contextual responses with `fallback=true` and `fallbackReason="provider_unavailable"`.
