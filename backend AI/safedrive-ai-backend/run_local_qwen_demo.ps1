$ErrorActionPreference = "Stop"

# Qwen only narrates a non-critical companion reply. Safety, HVAC, SOS, and risk
# assessment remain deterministic in the SafeDrive backend.
$env:ENVIRONMENT = "development"
$env:ACTIVE_PROFILE = "PRODUCTION_NO_DMS"
$env:SAFEDRIVE_API_KEY = "local-android-debug-key"
$env:LLM_PROVIDER = "ollama"
# The installed 7B model follows grounded vehicle facts more reliably for the
# judge demo. Set LLM_MODEL to the 3B model before invoking this script on a
# lower-resource machine.
if (-not $env:LLM_MODEL) {
    $env:LLM_MODEL = "qwen2.5:7b-instruct-q4_K_M"
}
$env:LLM_BASE_URL = "http://127.0.0.1:11434"
$env:LLM_TIMEOUT_SECONDS = "20"

if (-not (Get-Command ollama -ErrorAction SilentlyContinue)) {
    throw "Ollama is not installed or not available on PATH."
}

& .\.venv\Scripts\python.exe -m uvicorn app.main:app --host 0.0.0.0 --port 8003 --log-level info
