# DECISIONS.md

Append-only. Each entry: the decision, then why. Do not edit or delete past entries when
circumstances change — add a new dated entry that supersedes the old one instead, so the
history of *why* stays intact for whoever reads this next.

---

**2026-08-07 — Safety authority stays deterministic, never LLM.**
`app/mobile/safety.py` (`SafetyRiskEngine`) is the sole source of risk level, DTC severity
interpretation, and emergency-candidate detection. No LLM output is ever allowed to set or
override these. Why: this is the one invariant the entire Agent Armor migration depends on —
every other slice assumes it holds.

**2026-08-07 — LLM never executes a vehicle action directly.**
Only `MobileSessionStore._apply_hvac_action`, reached exclusively through `confirm_action`'s
freshness/version/fingerprint validation, ever changes vehicle state. An LLM may *propose*
wording or (via the deterministic `IntentResolver`) select a route that produces an action
candidate, but never calls an executor itself. Why: action execution must be independently
re-validated against current state, not trusted from a generation step that may be stale by
the time the user confirms.

**2026-08-07 — Local Ollama must remain a supported `llm_provider` value alongside cloud
providers.**
Why: it is the only provider that can run fully offline/on-device, which matters for demo
reliability and for users without cloud connectivity or API keys provisioned.

**2026-08-07 — Vehicle truth never comes from conversation memory.**
Every reply is grounded in the latest `ContextSnapshot`/`ContextPack` for the current turn, never
in what a previous turn's reply said. Why: no backend conversation memory exists today (see
`SAFEDRIVE_AGENT_ARMOR_PLAN.md` §8), and even if one is added later, a stale remembered fact must
never outrank a fresh state reading.

**2026-08-07 — `OllamaIntentClassifier` and `EmergencyLLMReasoner` remain Ollama-only for now;
not built for `gemini`/`vertex_ai` in this pass (SAFEDRIVE_AGENT_ARMOR_PLAN.md Slice 3).**
Both are advisory-only (see [[NarrationProvider]]/Slice 2 for the narration contract these are
adjacent to, not part of): the classifier can only relabel `assistant.clarify` to a different
already-existing deterministic template, and the reasoner can only cancel a false-positive
emergency candidate or annotate an escalation that has already happened — neither can invent a
fact, an action, or an emergency state. Why not built now:
1. Both require live-model prompt/output-format iteration to get right — every existing
   Ollama-side prompt in this codebase (see `docs/TEST_EVIDENCE.md`) was tuned against real model
   responses, not written blind. This environment has no live Gemini/Vertex AI credentials or
   network access to iterate the same way, so a from-scratch cloud-provider prompt would be
   unverified guesswork for a component that talks about live emergency situations.
2. The gap is UX-only, not a safety gap: swapping `llm_provider` to `gemini`/`vertex_ai` already
   leaves every safety-authoritative path (risk level, DTC severity, emergency timers, action
   validation) fully deterministic and unaffected — see the Brain vs Armor matrix in
   `SAFEDRIVE_AGENT_ARMOR_PLAN.md` §6. The only user-visible effect of the gap is that a genuinely
   ambiguous `assistant.clarify` turn cannot be advisory-reclassified on the cloud-provider path,
   and a crash candidate's `reasoningSummary` field may stay `null` instead of holding an LLM
   explanation — both cosmetic.
**Revisit when:** live Gemini/Vertex AI access becomes available in a development or staging
environment where the same prompt could be iterated against real responses (mirroring how the
Ollama-side prompts were built), or when product evidence shows cloud-provider users hit
`assistant.clarify` often enough that the missing reclassification is a real, measured UX cost
rather than a theoretical one.

**2026-08-08 — Defer Level-2 recent conversational memory (3–6 turns).**
Evidence: multi-turn probing against the real `MobileSessionStore` found no use case valuable
enough to require 2+ turns of generic conversation history. Nine of ten broken follow-ups were
attributable to one of four other causes:
1. routing vocabulary gaps (e.g. "còn bao nhiêu pin" failed even as a standalone utterance with
   no conversation at all),
2. one-turn referent resolution ("tại sao", "cái đó"),
3. pending-action refinement ("27 độ đi", "cao hơn nữa"),
4. missing capabilities such as undo ("thôi hoàn tác"), which is Capability + Action Authority,
   not chat memory.
Only explicit topic re-entry ("quay lại chuyện điều hòa") required deeper history, and that one
contrived case does not justify the complexity of a general recent-turn memory subsystem. Full
evidence in `SLICE7_SCOPING_CONVERSATIONAL_CONTEXT.md`.
**Revisit when:** real driver/demo transcripts show recurring failures where the user refers to
context older than the immediately previous turn, the information cannot be represented as a
structured referent / pending action / capability state, and the failure materially harms driving
UX. Until then, prefer structured state over raw chat history.

**2026-08-08 — Energy figure in `assistant.vehicle_status` is conditional on the question.**
The route states `energyPercent` only when `IntentResolution.asked_about_energy` is set, not on
every status reply. Why: the deterministic reply text is the narrator's approved-number set
(`app/mobile/llm.py`, `require_approved_numbers` — every number in the deterministic reply must
reappear in the LLM rewrite). Stating energy unconditionally would oblige every narrated
vehicle-status reply to repeat it, raising the narration rejection rate and silently changing
LLM behavior repo-wide for what was scoped as a routing fix. This was caught by
`test_genuinely_ambiguous_clarify_can_be_reclassified_by_advisory_llm` failing on the first
implementation attempt.
