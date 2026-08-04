# Latency Budget — SafeDrive AI v1

**Candidate** from `docs/android-mvp-plan/12` W7 — not "frozen" until Gate E's device-QA and
human-review criteria also pass. This is the **target/release-stop contract**; see
`docs/mobile-latency-baseline.md` for the actual before/after measurements taken against this budget
in this stabilization pass, and `docs/android-stabilization-progress.md` (W4/W5) for how each number
was implemented.

## Network timeouts (client-enforced, `core/network/NetworkModule.kt`)

| Layer | Value |
|---|---:|
| Connect timeout | 3 s |
| Read timeout | 8 s |
| Write timeout | 5 s |
| Health check (separate, tighter cap) | 5 s |
| **Assistant turn total deadline** (session + query combined) | **10 s** |

These are deliberately fixed and should only change after measuring real LAN/weak-network/staging
conditions — never raised simply to mask a slow server (`docs/android-mvp-plan/12` W5.11).

## End-to-end flow budget

| Flow | Type | Target (p95) | Release-stop |
|---|---|---:|---:|
| Final transcript → submit to `AssistantTurnCoordinator` | app-controlled | ≤ 50 ms | > 150 ms |
| Demo Mode text send → reply available | app-controlled | ≤ 100 ms | > 300 ms |
| Warm reply → `TtsController.speak()` call | app-controlled | ≤ 50 ms | > 150 ms |
| Tap mic → recognizer ready (`onReadyForSpeech`) | provider/device | ≤ 800 ms on reference device | > 1500 ms sustained |
| TTS request → audio start, warm engine | provider/device | ≤ 500 ms | > 1000 ms |
| TTS request → audio start, cold init | provider/device | ≤ 1500 ms (recorded, not release-gated) | > 3000 ms sustained |
| Remote request overhead (MockWebServer profile) | app-controlled | ≤ 300 ms | > 700 ms |
| Remote unreachable → actionable typed error | network policy | ≤ 5 s | > 10 s |

"App-controlled" rows are things this codebase can fix directly. "Provider/device" rows depend on the
OEM's `SpeechRecognizer`/`TextToSpeech` implementation and cannot be forced lower by Android app code
alone — they are recorded and reported, not silently absorbed into a single blended number.

## Demo Mode simulated latency (Developer Mode only)

Demo Mode has **no artificial delay by default** (`SimulatedLatencyProfile.NONE`,
`docs/android-mvp-plan/12` W4.3 — this fixed a real regression where every Mock reply used to sleep
240–440ms unconditionally). Settings → Developer Mode → "Độ trễ giả lập" can opt into:

| Profile | Delay | Purpose |
|---|---:|---|
| `NONE` (default) | 0 ms | Real Demo Mode behavior — as fast as the device can render. |
| `MS_100` / `MS_500` / `MS_2000` | 100 / 500 / 2000 ms | Deliberately reproduce a slow-backend UX for demoing/testing loading states. |
| `TIMEOUT` | 20 s (exceeds the 10s turn deadline) | Deliberately exercises the client's own timeout path end-to-end, producing a genuine `GatewayError.Timeout` rather than a fabricated one. |

## Instrumentation (what actually gets measured)

`core/observability/AssistantTurnMetrics.kt` records, per turn, and only when the relevant capture
happened (never a fabricated `0`):

```
turnStartedAtMs → micRequestedAtMs → recognizerReadyAtMs → firstPartialAtMs → finalTranscriptAtMs
                → sessionStartedAtMs → requestSentAtMs → responseReceivedAtMs
                → ttsRequestedAtMs → turnCompletedAtMs
```

Derived: `micStartToReadyMs`, `speechToFirstPartialMs`, `finalTranscriptToRequestMs`, `sessionMs`,
`networkMs`, `responseToTtsStartMs`, `totalTurnMs`. Surfaced in Settings → Developer Mode only — never
shown to a normal user, never logged with transcript/request/response content
(`AssistantTurnMetricsRecorder`, numbers-only redacted log line).

`serverProcessingMs` in the assistant response (`openapi/safedrive-v1.yaml`) is backend-reported and
additive/optional — it lets a future dashboard separate "our network + client overhead" from
"backend's own processing time" without the backend needing to adopt Android's specific timestamp
model.

## What is still `DEVICE_PENDING`

Real wall-clock values for every "provider/device" row above, and any real (non-`MockWebServer`)
network round trip, require a physical device and (for the network row) a reachable backend —
neither existed in the sandbox this stabilization pass ran in. See
`android/KNOWN_LIMITATIONS.md` and `docs/mobile-latency-baseline.md` for exactly what is pending and
the commands to run once hardware is available.
