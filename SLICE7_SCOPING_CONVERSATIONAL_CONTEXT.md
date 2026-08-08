# Scoping: 3–6 Turn Conversational Context (Level-2 Memory)

**Status: SCOPING ONLY — NO CODE WRITTEN. Recommendation is DO NOT BUILD (yet).**

Decision criterion set by the product owner:

> "Bước memory tiếp theo đáng làm nhất sẽ là recent conversational context khoảng 3–6 turns,
> nhưng **chỉ khi** chúng ta tìm được một use case thực tế mà `IssuedAction`/pending dialogue
> không giải quyết được."

This document tests that criterion empirically rather than by argument. Every route below was
produced by running real multi-turn conversations through `MobileSessionStore.answer_assistant()`
(sandbox, Python 3.10 + shim, current uncommitted Slice 6 code).

---

## 1. Evidence: where multi-turn conversation actually breaks today

Ten candidate conversations. Turn 1 establishes context; the follow-up is the interesting turn.

| # | Conversation | Follow-up route | Broken? |
|---|---|---|---|
| A | "lạnh quá" → **"27 độ đi"** | `assistant.general` | yes |
| B | "lạnh quá" → **"cao hơn nữa"** | `assistant.general` | yes |
| C | "lạnh quá" → "không" → **"thế 25 thì sao"** | `assistant.general` | yes |
| D | "tình trạng xe thế nào" → **"còn pin thì sao"** | `assistant.general` | yes |
| E | "mệt quá" → **"tại sao"** | `assistant.general` | yes |
| F | "xe báo lỗi P0128" → **"cái đó có nguy hiểm không"** | `assistant.general` | yes |
| G | "lạnh quá" → "có" → **"thôi hoàn tác"** | `assistant.general` | yes |
| H | "tôi không ổn" → **"mệt"** | `safety.driver_fatigue` | **no** |
| I | "lạnh quá" → **"làm lại đi"** | `assistant.general` | yes |
| J | "lạnh quá" → "tình trạng xe" → **"quay lại chuyện điều hòa"** | `assistant.general` | yes |

Nine of ten follow-ups fall through to `assistant.general`. That looks like a strong case for
conversational memory. It is not — see the next section.

---

## 2. The critical control: are these memory gaps or routing gaps?

Each follow-up was re-run **as a standalone first utterance, with no prior turn at all**. If an
utterance also fails standalone, no amount of conversational memory will fix it — the router
simply has no vocabulary for it.

| Utterance | Standalone route | Diagnosis |
|---|---|---|
| "27 độ đi" | `assistant.general` | needs referent |
| "đặt điều hòa 27 độ" | `climate.set_temperature` @27.0 | already works |
| "cao hơn nữa" | `assistant.general` | needs referent |
| "còn pin thì sao" | `assistant.general` | **routing gap** |
| "còn bao nhiêu pin" | `assistant.general` | **routing gap** |
| "mức năng lượng còn bao nhiêu" | `assistant.general` | **routing gap** |
| "tại sao" | `assistant.general` | needs referent |
| "cái đó có nguy hiểm không" | `assistant.general` | needs referent |
| "P0128 có nguy hiểm không" | `vehicle.fault_concern` | already works |
| "thôi hoàn tác" | `assistant.general` | **missing capability** |
| "làm lại đi" | `assistant.general` | needs referent |
| "quay lại chuyện điều hòa" | `assistant.general` | needs referent |

**Finding D is the important one.** "Còn bao nhiêu pin" — a completely explicit, self-contained
question about battery level — fails with no conversation involved. `assistant.vehicle_status`
has no vocabulary for `pin` / `năng lượng` / `battery` (see `IntentResolver.resolve()`; the
vehicle-status term list covers "tình trạng xe", "lái bao lâu", etc. but no energy terms).

Case D was originally filed as evidence for conversational memory. It is not. Building Level-2
memory would not have fixed it, and shipping memory would have hidden a cheap routing bug behind
an expensive subsystem.

---

## 3. How far back does each genuine case actually need to see?

| Case | History depth genuinely required |
|---|---|
| A, B, I | 1 turn — the pending HVAC proposal (already in `IssuedAction`) |
| C | 1 turn — the *declined* proposal, which `IssuedAction` clears |
| E | 1 turn — the previous reply's reason codes |
| F | 1 turn — the last mentioned DTC code |
| G | not history — an **undo capability** over executed actions |
| J | 2+ turns — genuine topic re-entry |

**Only case J requires more than one turn of history.** Everything else is a Level-1 extension of
the mechanism already shipped in Slice 6, or a different feature entirely.

Case J's utterance ("quay lại chuyện điều hòa") is also the most contrived of the set — it is how
someone talks to a chatbot they know is stateless, not how a driver talks. It should not be the
justification for a memory subsystem.

---

## 4. Recommendation

**Do not build 3–6 turn conversational context.** The stated criterion is not met: no use case was
found that genuinely requires more than one turn of history and that `IssuedAction` could not be
extended to cover.

Ranked by value-to-cost, based on the evidence above:

1. **Vehicle-status vocabulary gap (P1, tiny).** Add energy/battery terms to
   `assistant.vehicle_status`. Fixes three real standalone questions. No memory, no new state,
   likely a one-line change plus tests. This is the highest-value item found by this whole
   investigation, and it has nothing to do with memory.

2. **Counter-offer / refinement against a pending proposal (Level-1 extension).** Cases A, B, C.
   Reuses the exact `PendingDialogue` mechanism from Slice 6: when a pending HVAC proposal exists
   and the utterance carries a temperature or a relative direction, treat it as a counter-offer
   rather than a new request. Still resolves reference only — the re-issued action goes through
   unchanged confirmation authority. Case C additionally needs the topic to survive a decline,
   which is the one place `IssuedAction` genuinely does not reach.

3. **Last-referent for "tại sao" / "cái đó" (Level-1 extension).** Cases E, F. Requires storing one
   previous route + one previous DTC code. Small, but lower value than (1) and (2), and the payoff
   is conversational polish rather than a capability.

4. **Undo (case G).** Not a memory feature. It is a new vehicle capability with its own safety
   authority questions (can an undo bypass confirmation? what if state changed since?). Should be
   scoped separately and deliberately, never bundled into a memory slice.

5. **Level-2 memory (3–6 turns).** Deferred. Revisit only if real driver transcripts show topic
   re-entry (case J) actually occurring. Prediction to test against future logs: it will be rare
   compared to (1) and (2).

---

## 5. Constraint check

- No code written. No source file touched. No dependency added.
- The pending four-file dialogue-continuity commit is unaffected; this document is a new untracked
  file outside that scope.
- Slice 5A untouched and still `VERIFICATION PENDING`.

## 6. Incidental observation (not a defect)

During probing, `assistant.general` began emitting a `SHOW_WARNING` action partway through a long
session. This was traced to the state-freshness threshold: at ~12s of simulated silence, risk rises
`LOW → MEDIUM` and the stale-state warning attaches. Expected behavior, recorded here only so the
probe transcripts above are not misread as showing a routing defect.
