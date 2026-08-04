# Deployment

## Backend — local (no Docker)

```bash
uv sync --extra dev            # or: pip install -e ".[dev]"
ollama pull qwen2.5:7b-instruct-q4_K_M
ENVIRONMENT=development ACTIVE_PROFILE=PRODUCTION_NO_DMS SAFEDRIVE_API_KEY=local-android-debug-key \
LLM_PROVIDER=ollama LLM_MODEL=qwen2.5:7b-instruct-q4_K_M LLM_BASE_URL=http://127.0.0.1:11434 \
uvicorn app.main:app --host 0.0.0.0 --port 8000
```
Verify: `curl http://127.0.0.1:8000/health`.

## Backend — Docker

```bash
docker compose build
docker compose up
```
- `Dockerfile` builds the backend only; Ollama is **not** bundled — it runs as its own process on
  the host (or its own container you point `LLM_BASE_URL` at).
- `docker-compose.yml` sets `LLM_BASE_URL=http://host.docker.internal:11434`, which is the Docker
  Desktop (Windows/Mac) route to a service listening on the host's 11434; `extra_hosts:
  host.docker.internal:host-gateway` makes the same hostname work on native Linux Docker too.
- Verified this pass: `docker compose build` succeeded, the container's healthcheck
  (`GET /health` from inside the container) passed, and `POST /api/v1/assistant/query` through the
  container reached the real host Ollama and returned a grounded, non-fallback reply. See
  `docs/TEST_EVIDENCE.md` for the exact numbers.
- `.env.example` documents every variable; never commit a real `.env`.

## Android

```bash
cd "safedrive-ai (1)/android"
./gradlew.bat assembleDebug
```
APK: `app/build/outputs/apk/debug/app-debug.apk`.

Connect to the backend without touching source:
- **USB device**: `adb reverse tcp:8000 tcp:8000`, then Settings → BASE_URL →
  `http://127.0.0.1:8000/`.
- **Emulator**: Settings → BASE_URL → `http://10.0.2.2:8000/` (no `adb reverse` needed).
- **LAN**: Settings → BASE_URL → `http://<dev-machine-LAN-IP>:8000/`. No LAN IP is hard-coded in
  source — the Settings presets (`USB Local`/`Emulator`/`LAN Wi-Fi`) are editable text, not fixed
  values.

A debug build defaults to **Remote Mode** against `http://127.0.0.1:8000/` on first install so the
real pipeline is demonstrated without a manual setup step; Demo Mode remains one tap away in
Settings as the offline fallback.

## CarSky / VDP platform

**Not attempted.** No platform account/credentials were available in this environment. Nothing
about a CarSky deployment is claimed anywhere in this documentation set.
