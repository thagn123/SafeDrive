# Vertex AI cloud narration — verification record

Evidence type: `CLOUD_BACKEND_API` (Python HTTPS client against the live Cloud Run URL; no
Android/CarSky involvement in this record).

## What was found broken before this fix

The previously deployed revision (`safedrive-backend-00006-znc`) had `LLM_PROVIDER=vertex_ai`
and `VertexAINarrator` code, but three independent defects meant it silently produced
`fallback=true, fallbackReason=provider_unavailable` for every request:

1. `aiplatform.googleapis.com` was not enabled on project `gen-lang-client-0307536353` at all.
2. Cloud Run had no `LLM_MODEL` env var, so `VertexAINarrator` received the local Ollama model
   name (`qwen2.5:7b-instruct-q4_K_M`) as its Vertex AI model argument -- confirmed 404 against
   the real endpoint, in any project.
3. The Cloud Run service ran under the default Compute Engine service account (`roles/editor`,
   over-broad) rather than a dedicated least-privilege identity.

## Fixes applied and verified live

- Enabled `aiplatform.googleapis.com` on `gen-lang-client-0307536353`.
- Created `safedrive-cloud-ai@gen-lang-client-0307536353.iam.gserviceaccount.com` with only
  `roles/aiplatform.user`, attached it to the Cloud Run service (replacing the default SA).
- Probed real Vertex AI publisher-model availability directly (not assumed from training data):
  `gemini-1.5-flash`, `gemini-2.0-flash`, `gemini-2.0-flash-001` all returned 404 in this
  project. `gemini-2.5-flash` returned 200 in `global`, `us-central1`, and `asia-southeast1`.
  Deployed with `LLM_MODEL=gemini-2.5-flash`, region `asia-southeast1` (matches Cloud Run's own
  region).
- Found and fixed a live truncation bug: `gemini-2.5-flash`'s extended "thinking" tokens consumed
  the entire `maxOutputTokens=256` budget before producing visible text (confirmed via direct API
  call: 246/256 tokens spent thinking, `finishReason=MAX_TOKENS`, visible text truncated to "Xe
  chay 50 km"). Added `generationConfig.thinkingConfig.thinkingBudget=0` to both
  `VertexAINarrator` and `GeminiNarrator` -- confirmed live this returns `finishReason=STOP` with
  the complete reply, and cut latency from ~1.1-1.6s to ~0.35-0.8s per request.
- Found and fixed a response-metadata bug: `MobileSessionStore` hardcoded
  `f"ollama/{narrator.model}"` as the response `model` field regardless of which narrator
  actually ran. Added a `provider_name` `ClassVar` to each narrator class and used it instead, so
  the field now genuinely reads e.g. `vertex_ai/gemini-2.5-flash`.

## Live results (this record)

Runtime source SHA deployed: `0a4ac9d63f6f6d5c1636c43c225a0f073be4d86e`
Cloud Run revision: `safedrive-backend-00008-rb8`
Image digest: `sha256:95a972ff6b3fd8357a33303f6dec2002a4264d4645e9191df8dd5591918e524c`
Service account: `safedrive-cloud-ai@gen-lang-client-0307536353.iam.gserviceaccount.com` (`roles/aiplatform.user` only)
Model: `gemini-2.5-flash`, region `asia-southeast1`

**10 warm requests** (`vertex_ai_10_warm_requests.json`), text "Xe của tôi hiện tại thế nào?":
`llmUsed=true, fallback=false, model="vertex_ai/gemini-2.5-flash"` in **10/10**, all complete
grounded Vietnamese sentences (no truncation), `serverProcessingMs` 346-786ms.

**Outage test** (`vertex_ai_outage_fallback.txt`): removed `roles/aiplatform.user` from the
service account via `gcloud projects remove-iam-policy-binding`, no redeployment. IAM propagation
took ~60-75s (4 polling attempts at 15s intervals); the 5th request cleanly returned
`llmUsed=false, fallback=true, fallbackReason=provider_unavailable,
model="deterministic-context-router"`. Backend stayed healthy throughout -- no crash, no 5xx.

**Recovery test** (`vertex_ai_recovery.txt`): restored the same IAM binding, no redeployment. IAM
propagation again took ~60-75s; the 5th request returned to
`llmUsed=true, model="vertex_ai/gemini-2.5-flash"` with no restart or redeploy in between.

## What this record does NOT prove

No Android or CarSky device was involved in producing this evidence -- these are direct HTTPS
calls from a Python client. Android-to-Cloud-Run and CarSky-to-Cloud-Run evidence must come from
the physical Xiaomi device and CarSky runtime respectively, captured separately.
