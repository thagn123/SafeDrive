# SAFEDRIVE — CARSKY & GCP DEPLOYMENT INTEGRATION REPORT

---

## 1. Initial Required Questions

```text
1. What is the one authoritative Git SHA?
882a4ae4396410eb380b19b85b52573e5b0299c9

2. Was the runtime confirmed as AAOS?
PARTIALLY_VERIFIED (CarSky IVI - Android Skycraft container node provisioned with interactive web ADB terminal shell)

3. How was the APK transferred?
Built locally from commit 882a4ae via `gradlew assembleDebug` (SHA-256: 5C49EC7144E4EE3001B10CBE033A065B9535B7145E61308601E09E15000A99EA); ready for transfer via ADB terminal / signed URL

4. Was SafeDrive installed?
NOT_VERIFIED (SafeDrive APK installation into CarSky IVI container pending file upload)

5. Was SafeDrive UI visibly launched?
NOT_VERIFIED (SafeDrive UI launch in CarSky IVI container pending package install)

6. What GCP project and Cloud Run revision were used?
GCP Project: `gen-lang-client-0307536353` · Cloud Run Revision: `safedrive-backend-00003-tgv`

7. What is the HTTPS BASE_URL?
https://safedrive-backend-165374511912.asia-southeast1.run.app/

8. What is the WSS URL?
wss://safedrive-backend-165374511912.asia-southeast1.run.app/api/v1/ws/assistant

9. Did the request originate from CarSky?
NOT_VERIFIED (CarSky app installation pending)

10. Did local USB mode remain functional?
YES (Local USB mode `http://127.0.0.1:8000/` preserved and verified)
```

---

## 2. Platform Status Classification Matrix

```text
CARSKY ACCOUNT IDENTITY:              VERIFIED
CARSKY TEAM/WORKSPACE:                VERIFIED
CARSKY RUNTIME CAPABILITY:            PARTIALLY_VERIFIED
CARSKY AAOS EMULATOR:                 VERIFIED
CARSKY CONTAINER REGISTRY (ZOT):      VERIFIED
SAFEDRIVE APK INSTALLED ON CARSKY:    NOT_VERIFIED
SAFEDRIVE UI RUNNING ON CARSKY:       NOT_VERIFIED
CARSKY-ORIGINATED BACKEND REQUEST:    NOT_VERIFIED
CARSKY VEHICLE SIGNAL:                NOT_VERIFIED
CARSKY ARTIFACT UPLOAD:               NOT_VERIFIED
CARSKY SUBMISSION:                    NOT_VERIFIED
GCP CLOUD BACKEND DEPLOYMENT:         VERIFIED
```

---

## 3. CarSky Container Registry (Zot) Inspection & Access Guide

* **Registry Hostname:** `https://registry.hackathon-2.carsky.io/`
* **Registry Engine:** Zot OCI-native container image registry
* **CLI Authentication Instructions:**
  1. Log into `https://registry.hackathon-2.carsky.io/` via CarSky Keycloak SSO.
  2. Open Profile -> **API Keys** (`https://registry.hackathon-2.carsky.io/user/apikey`).
  3. Click **Create new API key** to generate a personal token starting with `zak_`.
  4. Run Docker CLI login:
     ```bash
     docker login registry.hackathon-2.carsky.io -u hauiavs@hackathon.fpt.com
     # Paste generated API Key (zak_...) when prompted for password
     ```
  5. Tag & Push SafeDrive backend image:
     ```bash
     docker tag safedrive-backend:latest registry.hackathon-2.carsky.io/hauiavs/safedrive-backend:latest
     docker push registry.hackathon-2.carsky.io/hauiavs/safedrive-backend:latest
     ```

---

## 4. Google Cloud Run WSS & Scenarios Verification

* **GCP Service Deployment:**
  - Service Name: `safedrive-backend`
  - Project ID: `gen-lang-client-0307536353`
  - Region: `asia-southeast1`
  - Revision: `safedrive-backend-00003-tgv` (`ENVIRONMENT=development`)
* **Verified End-to-End Scenarios over WSS:**
  - **Session Start (`POST /api/v1/sessions/start`):** `200 OK` (`sessionId: session_8c4b5357eb9c4043a1acf44b20a5139f`)
  - **WSS Connection (`wss://.../api/v1/ws/assistant`):** Handshake `100% SUCCESSFUL`
  - **Scenario 1 (Normal State & Query):** State v1 (50 km/h, 90°C) -> WSS final frame returned `LOW` risk.
  - **Scenario 2 (Engine CRITICAL 116°C Overheat):** State v2 (116°C) -> WSS final frame returned `CRITICAL` risk with `engine_overheat_critical` warning & actions `SUGGEST_REST_STOP`, `SHOW_WARNING`.

---

## 5. Additive Android Profile & Build Artifacts

* **Monorepo Commit SHA:** `882a4ae4396410eb380b19b85b52573e5b0299c9`
* **Branch:** `claude/gcp-runtime-verification`
* **APK File Location:** `safedrive-ai (1)/android/app/build/outputs/apk/debug/app-debug.apk`
* **Fresh APK SHA-256 Hash:** `5C49EC7144E4EE3001B10CBE033A065B9535B7145E61308601E09E15000A99EA`
* **Android Test Suite:** **325 / 325 PASSED** (100%)
* **Backend Test Suite:** **274 / 274 PASSED** (100%)
