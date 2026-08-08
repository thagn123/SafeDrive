from pathlib import Path
from typing import Any, Literal

from pydantic import Field, SecretStr, field_validator, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

PROJECT_ROOT = Path(__file__).resolve().parents[2]

def _find_default_signal_registry() -> Path:
    candidates = [
        PROJECT_ROOT / "configs" / "signal_registry.yaml",
        Path.cwd() / "configs" / "signal_registry.yaml",
        Path("/app/configs/signal_registry.yaml"),
    ]
    for candidate in candidates:
        if candidate.is_file():
            return candidate
    return candidates[0]

DEFAULT_SIGNAL_REGISTRY_PATH = _find_default_signal_registry()

FORBIDDEN_SECRET_PLACEHOLDERS = {
    "safedrive-default-api-key-placeholder",
    "change_this_to_a_secure_api_key_in_production",
    "your_api_key_here",
    "placeholder",
    "secret",
}


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
        case_sensitive=False,
    )

    # Runtime Environment & Profile Boundaries
    environment: Literal["production", "development", "test"] = Field(
        default="production",
        description="Runtime environment (production, development, test)",
    )
    active_profile: Literal["PRODUCTION_NO_DMS", "DMS_DEMO"] = Field(
        default="PRODUCTION_NO_DMS",
        description="Active SafeDrive capability profile",
    )

    # Security & API Key Boundaries
    safedrive_api_key: SecretStr | None = Field(
        default=None,
        description="SafeDrive API Key boundary for authentication",
    )

    # Path & File Boundaries
    signal_registry_path: Path = Field(
        default_factory=lambda: DEFAULT_SIGNAL_REGISTRY_PATH,
        description="Path to signal_registry.yaml",
    )

    # Host & Network Boundaries
    host: str = Field(
        default="0.0.0.0",
        description="Application bind host",
    )
    port: int = Field(
        default=8000,
        description="Application bind port",
    )

    # Logging & Provider Boundaries. Ollama may only narrate a deterministic
    # companion reply; it never owns safety assessment or vehicle actions.
    log_level: str = Field(
        default="INFO",
        description="Logging level",
    )
    llm_provider: Literal["mock", "ollama", "gemini", "vertex_ai"] = Field(
        default="mock",
        description="Optional constrained companion narration provider",
    )
    gcp_project_id: str | None = Field(
        default="gen-lang-client-0307536353",
        description="GCP Project ID for Vertex AI / Gemini API",
    )
    gcp_region: str = Field(
        default="asia-southeast1",
        description="GCP Region for Vertex AI",
    )
    llm_model: str = Field(
        default="qwen2.5:7b-instruct-q4_K_M",
        min_length=1,
        max_length=128,
        description="Local Ollama model used only for companion narration",
    )
    llm_base_url: str = Field(
        default="http://127.0.0.1:11434",
        min_length=1,
        max_length=512,
        description="Local Ollama base URL",
    )
    llm_timeout_seconds: float = Field(
        default=20.0,
        ge=1.0,
        le=60.0,
        description="Bounded local narration request timeout",
    )
    llm_api_key: SecretStr | None = Field(
        default=None,
        description="Future cloud LLM API key boundary",
    )
    memory_backend: Literal["in_memory", "firestore"] = Field(
        default="in_memory",
        description="Bounded episodic context-memory provider",
    )
    firestore_database_id: str = Field(
        default="ai-studio-73271eac-0871-4a76-ba4c-c385c60e0ac6",
        min_length=1,
        max_length=128,
        description="Firestore Native database used for durable context memory",
    )
    rolling_window_prune_interval_seconds: float = Field(
        default=60.0,
        ge=1.0,
        le=3600.0,
        description="Interval in seconds for periodic rolling window pruning",
    )

    @field_validator("port")
    @classmethod
    def validate_port_range(cls, v: int) -> int:
        if not (1 <= v <= 65535):
            raise ValueError("Port must be between 1 and 65535")
        return v

    @field_validator("signal_registry_path")
    @classmethod
    def validate_registry_path_exists(cls, v: Path) -> Path:
        resolved = v.resolve() if v.is_absolute() else (PROJECT_ROOT / v).resolve()
        if not resolved.is_file():
            raise ValueError("Signal registry configuration is unavailable")
        return resolved

    @model_validator(mode="after")
    def validate_environment_and_secrets(self) -> "Settings":
        # Check for placeholder or empty secrets if safedrive_api_key is provided
        if self.safedrive_api_key is not None:
            raw_key = self.safedrive_api_key.get_secret_value().strip()
            if not raw_key:
                raise ValueError("safedrive_api_key must not be empty or whitespace-only")
            if raw_key.lower() in FORBIDDEN_SECRET_PLACEHOLDERS:
                raise ValueError("safedrive_api_key contains a forbidden placeholder")

        # In production environment, a valid safedrive_api_key is strictly required
        if self.environment == "production" and self.safedrive_api_key is None:
            raise ValueError("safedrive_api_key is required when environment is 'production'")

        # DMS_DEMO profile requires explicit development or test environment selection
        if self.active_profile == "DMS_DEMO" and self.environment == "production":
            raise ValueError("DMS_DEMO profile cannot be used in production environment")

        return self


def get_settings(**kwargs: Any) -> Settings:
    """Return a Settings instance without triggering module import side-effects."""
    return Settings(**kwargs)
