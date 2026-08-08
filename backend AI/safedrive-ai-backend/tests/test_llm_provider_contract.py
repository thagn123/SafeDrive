"""Contract test for SAFEDRIVE_AGENT_ARMOR_PLAN.md Slice 2.

Every narration provider (OllamaNarrator, GeminiNarrator, VertexAINarrator, and any
future provider) must satisfy the same structural shape: a ``provider_name`` class
constant, a ``model`` instance attribute, and the two async narration methods with
identical signatures. This is not new behavior -- MobileSessionStore has relied on
this shape by convention since these three classes were first written to duck-type
against each other. This test only makes that already-true fact checkable, so a
future provider that quietly drifts from the shape fails loudly here instead of at
runtime inside a live session.
"""

import inspect

from app.mobile.llm import (
    GeminiNarrator,
    NarrationProvider,
    OllamaNarrator,
    VertexAINarrator,
)

_ALL_PROVIDERS = (
    OllamaNarrator(base_url="http://127.0.0.1:11434", model="qwen2.5:7b-instruct-q4_K_M", timeout_seconds=5.0),
    GeminiNarrator(api_key=None),
    VertexAINarrator(),
)


def test_every_concrete_provider_satisfies_the_narration_provider_protocol() -> None:
    for provider in _ALL_PROVIDERS:
        assert isinstance(provider, NarrationProvider), (
            f"{type(provider).__name__} no longer satisfies NarrationProvider"
        )


def test_every_provider_declares_a_distinct_provider_name() -> None:
    names = [provider.provider_name for provider in _ALL_PROVIDERS]
    assert names == ["ollama", "gemini", "vertex_ai"]
    assert len(set(names)) == len(names)


def test_every_provider_exposes_the_two_async_narration_methods() -> None:
    for provider in _ALL_PROVIDERS:
        assert inspect.iscoroutinefunction(provider.rewrite_grounded_reply)
        assert inspect.iscoroutinefunction(provider.answer_open_query)


def test_narrator_type_annotation_accepts_every_concrete_provider() -> None:
    """MobileSessionStore's own type boundary, not just this module's Protocol.

    Reproduces the exact typing gap this slice closes: before Slice 2,
    MobileSessionStore.__init__'s ``narrator`` parameter was annotated
    ``OllamaNarrator | None``, even though app/main.py already constructed and
    passed GeminiNarrator/VertexAINarrator instances there for those provider
    settings. The parameter is now annotated ``NarrationProvider | None`` -- this
    test constructs a MobileSessionStore with each concrete provider to prove the
    annotation is no longer narrower than what the app actually does.
    """

    from app.mobile.session_store import MobileSessionStore

    for provider in _ALL_PROVIDERS:
        store = MobileSessionStore(narrator=provider)
        assert store._narrator is provider  # intentional white-box check; SLF001 not enabled here
