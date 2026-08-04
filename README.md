# SafeDrive (Phase 10: MVP Submission)

SafeDrive is an Android-based intelligent driving assistant powered by a local Large Language Model (Ollama) and FastAPI backend, designed for offline-capable, privacy-first in-car assistance.

This repository contains the complete solution for the hackathon MVP, integrating both the Android client and the Python backend.

## Architecture

* **Android App (`safedrive-ai (1)/android`)**: Built with Jetpack Compose, Kotlin Coroutines, and DataStore. Connects to the backend via Retrofit and manages local offline fallbacks (Demo Mode).
* **Backend AI (`backend AI/safedrive-ai-backend`)**: Built with FastAPI, Pydantic, and LangChain. Integrates locally with Ollama for zero-latency, private LLM execution. Uses a strict safety gating engine that bypasses the LLM for high-risk alerts (e.g., Engine Overheat, DTC faults).

## Getting Started

### 1. Backend Setup

The backend acts as the bridge to the local LLM and evaluates real-time vehicle telemetry.

#### Prerequisites
- **Python 3.12+**
- **Ollama**: Installed locally ([Download here](https://ollama.com/download))

#### Installation

1. Start Ollama and pull the required model:
   ```bash
   ollama run qwen2.5:7b-instruct-q4_K_M
   ```
2. Navigate to the backend directory and set up a virtual environment:
   ```bash
   cd "backend AI/safedrive-ai-backend"
   python -m venv .venv
   .venv\Scripts\activate
   pip install -r requirements.txt
   ```
3. Start the FastAPI server:
   ```bash
   # Make sure Ollama is running in the background!
   uvicorn app.main:app --host 0.0.0.0 --port 8000
   ```

*Alternative (Docker)*: You can run the backend via Docker Compose:
```bash
cd "backend AI/safedrive-ai-backend"
docker-compose up --build -d
```

### 2. Android App Setup

#### Prerequisites
- **Android Studio**
- **Android Device / Emulator** running Android 9.0+

#### Installation

1. Open the project folder `safedrive-ai (1)/android` in Android Studio.
2. Build and run the `app` module on your connected device.
   *Or build the APK manually:*
   ```bash
   cd "safedrive-ai (1)/android"
   ./gradlew.bat assembleDebug
   ```
   The APK will be generated at `app/build/outputs/apk/debug/app-debug.apk`.

#### Connecting Android to the Local Backend

By default, the debug build attempts to connect to `http://127.0.0.1:8000/`.

- **If using a physical Android device via USB:** You must reverse the port using ADB so the phone can reach your PC's localhost:
  ```bash
  adb reverse tcp:8000 tcp:8000
  ```
- **If using an Emulator:** Change the `BASE_URL` in the app's Settings to `http://10.0.2.2:8000/`.
- **If connecting over Wi-Fi (LAN):** Change the `BASE_URL` in the app's Settings to your PC's local IP address (e.g., `http://192.168.1.5:8000/`).

### 3. Usage & Testing (Demo Simulator)

1. Open the SafeDrive app and navigate to **Settings**. Ensure **Backend Mode** is set to `Remote`.
2. Navigate to the **Trợ lý (Assistant)** tab to converse with the AI.
3. Navigate to the **Chẩn đoán (Simulator)** tab to simulate vehicle telemetry events:
   - *Engine Overheat*: Set the engine temperature above 115°C to trigger the critical, deterministic safety override.
   - *Driver Fatigue*: Set driving time to > 240 mins to test the rest recommendation.

---

**Note:** In `Remote` mode, the app requires the FastAPI backend to be running. If the backend is unavailable or the connection drops, you can seamlessly switch to `Demo` mode in Settings for a completely offline, simulated experience.
