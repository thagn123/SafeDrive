FROM python:3.11-slim

WORKDIR /app

# Only copy dependency-relevant files first so dependency install is cached across code-only edits.
COPY pyproject.toml ./
COPY app ./app
COPY configs ./configs

RUN pip install --no-cache-dir .

# Ollama is NOT bundled in this image -- it runs as its own process/container, reached over
# LLM_BASE_URL (see docker-compose.yml and .env.example). This image is the SafeDrive backend only.
EXPOSE 8000

HEALTHCHECK --interval=15s --timeout=5s --start-period=10s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/health', timeout=3)" || exit 1

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
