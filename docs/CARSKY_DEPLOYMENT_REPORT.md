# SAFEDRIVE — CARSKY & GCP DEPLOYMENT INTEGRATION REPORT

---

## 1. Initial Required Questions

```text
1. What is the one authoritative Git SHA?
22b0480fe74ed1108f903ffc1930b39d8335824b

2. Was the runtime confirmed as AAOS?
PARTIALLY_VERIFIED (CarSky IVI - Android Skycraft container node provisioned with web ADB terminal access)

3. How was the APK transferred?
Built locally via `gradlew assembleDebug` (SHA-256: 5C49EC7144E4EE3001B10CBE033A065B9535B7145E61308601E09E15000A99EA); ready for transfer via ADB terminal / signed URL

4. Was SafeDrive installed?
NOT_VERIFIED (SafeDrive APK installation into CarSky IVI container pending file transfer)

5. Was SafeDrive UI visibly launched?
NOT_VERIFIED (SafeDrive UI launch in CarSky IVI container pending package install)

6. What GCP project and Cloud Run revision were used?
GCP Project: `gen-lang-client-0307536353` · Cloud Run Revision: `safedrive-backend-00001-mxt`

7. What is the HTTPS BASE_URL?
https://safedrive-backend-165374511912.asia-southeast1.run.app/

8. What is the WSS URL?
wss://safedrive-backend-165374511912.asia-southeast1.run.app/api/v1/assistant/stream_ws

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
SAFEDRIVE APK INSTALLED ON CARSKY:    NOT_VERIFIED
SAFEDRIVE UI RUNNING ON CARSKY:       NOT_VERIFIED
CARSKY-ORIGINATED BACKEND REQUEST:    NOT_VERIFIED
CARSKY VEHICLE SIGNAL:                NOT_VERIFIED
CARSKY ARTIFACT UPLOAD:               NOT_VERIFIED
CARSKY SUBMISSION:                    NOT_VERIFIED
GCP CLOUD BACKEND DEPLOYMENT:         VERIFIED
```

---

## 3. Required Final Status Header

```text
GCP BACKEND VERIFIED — CARSKY APP INSTALLATION PENDING
```

---

## 4. Google Cloud Deployment Verification Details

* **GCP Service Deployment:**
  - Service Name: `safedrive-backend`
  - Project ID: `gen-lang-client-0307536353`
  - Region: `asia-southeast1`
  - HTTPS Endpoint: `https://safedrive-backend-165374511912.asia-southeast1.run.app/`
  - Verification (`GET /health`): `200 OK` (`status: ok, capabilities: {assistant: true, emergencySimulation: true}`)

---

## 5. Additive Android Profile & Build Artifacts

* **Monorepo Commit SHA:** `22b0480fe74ed1108f903ffc1930b39d8335824b`
* **Branch:** `claude/gcp-carsky-integration`
* **APK File Location:** `safedrive-ai (1)/android/app/build/outputs/apk/debug/app-debug.apk`
* **Fresh APK SHA-256 Hash:** `5C49EC7144E4EE3001B10CBE033A065B9535B7145E61308601E09E15000A99EA`
* **Android Test Suite:** **325 / 325 PASSED** (100%)

---

## 6. Open Blockers

* **P0 Blockers:**
  1. Transfer fresh APK (`5C49EC71...`) to CarSky `IVI - Android` node.
  2. Install and launch `vn.edu.haui.hvs.safedrive` inside CarSky IVI container.
  3. Perform live CarSky-to-GCP request and capture Cloud Run access log.
* **P1 Blockers:**
  1. CarSky-native VHAL signal mapping.
  2. Submission form artifact upload.
