import pytest

from app.ingestion.registry import SignalRegistry


@pytest.fixture(autouse=True)
def set_default_test_environment(monkeypatch: pytest.MonkeyPatch) -> None:
    """Ensure default test environment profile so tests don't require external production secrets."""
    monkeypatch.setenv("ENVIRONMENT", "test")
    monkeypatch.setenv("ACTIVE_PROFILE", "PRODUCTION_NO_DMS")


@pytest.fixture
def test_registry() -> SignalRegistry:
    """Shared SignalRegistry loaded from the canonical config for unit tests."""
    return SignalRegistry(config_path="configs/signal_registry.yaml")
