# SAFEDRIVE — CARSKY DEPLOYMENT, IDENTITY AND RUNTIME REPORT

Reconciled on `claude/competition-integration`. Every status below was checked directly against the
four screenshots in `evidence/carsky/` by an independent viewer of the images — not copied forward
from an earlier draft of this document. Where the earlier draft (still visible in `git log` on
`claude/carsky-deployment`) implied something the images don't actually show, it is corrected here
and the correction is called out explicitly.

---

## 1. What the four screenshots actually show, one at a time

- **`01_authenticated_haui_avs.png`** — a logged-in profile popover: team **Haui AVS Team**,
  email `hauiavs@hackathon.fpt.com`, role `editor`, with a working "Sign Out" control, and a
  blueprint list showing `Haui AVS-SafeDrive` and `Haui AVS`. The main canvas is the empty "Welcome
  to Rework" state ("Nothing selected"). Footer: **"No device connected."**
  → Supports: authenticated as Haui AVS, workspace visible. Does not show any device or node.
- **`02_dashboard.png`** — the Dashboard tab: **"No running deployments"**, "Select a running
  deployment", **"No deployments."** Footer: "No device connected."
  → Supports: nothing. Confirms nothing was running at the moment this was captured.
- **`04_workspace.png`** — the `Haui AVS-SafeDrive` blueprint open in the editor. Node palette shown
  (Abstract, Skycraft, Script Node, KUKSA Broker, CAN Bus, LIN Bus, GPIO Panel, Ethernet Bridge,
  Container, Proxy Node, Device Proxy) — but the canvas itself has **0/30 nodes placed** and
  **"DEPLOYMENTS (0)" / "None."** Owner: `hauiavs@hackatho...`. Footer: "No device connected."
  → Supports: the SafeDrive-specific blueprint exists and is owned by this account. It is empty —
  no nodes configured, nothing deployed.
- **`05_aaos_device.png`** — a *different* blueprint, **`Haui AVS`** (not `Haui AVS-SafeDrive`;
  its own description reads "This blueprint to demo FACE, vTCU, v..."), with a deployment
  "Haui AVS-deploydeployment-1" **Running, 20/20 nodes ready**. The topology shows zonal ECU nodes
  (BCM/TCU/PWT/CDC zones, a Central Broker (VSS), CAN/LIN buses) including one node explicitly
  labeled **"IVI - Android"**. A bottom panel shows a live, connected ADB shell:
  `ADB SHELL [connected]` → `trout_arm64:/ $ which screencap` → `/system/bin/screencap`.
  → Supports: a genuine, running AAOS/IVI simulation with real ADB shell access to an Android node
  exists on this CarSky account. **This is on the generic `Haui AVS` demo blueprint, not
  `Haui AVS-SafeDrive`.** No SafeDrive package, screen, or process appears anywhere in this
  screenshot — the only command shown is a generic environment check (`which screencap`).

**Correction to the previous draft of this document:** the previous version's Section 5 stated
"Blueprint `Haui AVS-SafeDrive` deployed on CarSky Nydus platform... `IVI - Android` node running
with ADB shell access" in the same breath, which reads as if the running node with ADB access
belongs to the SafeDrive-specific blueprint. Per the images themselves, it does not: the running
node is on the separate `Haui AVS` blueprint, and `Haui AVS-SafeDrive` (image 3 above) has zero
nodes and zero deployments. The platform-level capability (AAOS emulator + ADB access) is real and
demonstrated; SafeDrive-specific use of it is not yet demonstrated.

---

## 2. Platform Status Classification Matrix

```text
CARSKY_PORTAL_ACCESS:                 VERIFIED (image 1: authenticated session, team Haui AVS)
CARSKY_TEAM_WORKSPACE:                VERIFIED (images 1, 4: Haui AVS-SafeDrive blueprint exists, owned by this account)
CARSKY_RUNTIME (AAOS/IVI emulator):   PARTIALLY_VERIFIED (image 5: a running AAOS node with live ADB
                                       shell access is demonstrated, but on the generic "Haui AVS"
                                       blueprint, not the SafeDrive-specific one)
SAFEDRIVE_APK_INSTALLED_ON_CARSKY:    NOT_VERIFIED — no evidence in any image
SAFEDRIVE_UI_RUNNING_ON_CARSKY:       NOT_VERIFIED — no evidence in any image
CARSKY_ORIGINATED_BACKEND_REQUEST:    NOT_VERIFIED — no evidence in any image
CARSKY_VEHICLE_SIGNAL_INTO_SAFEDRIVE: NOT_VERIFIED — no evidence in any image
CARSKY_ARTIFACT_UPLOAD:               NOT_VERIFIED
CARSKY_SUBMISSION:                    NOT_VERIFIED
```

---

## 3. Disambiguation of local execution vs. CarSky platform

- **Local workstation (this project's actual verified backend/Android work):** Android debug APK
  built and unit-tested locally; FastAPI backend verified locally (Docker, real Ollama). See
  `TEST_EVIDENCE.md` for exact commands and results. This is where all functional correctness
  evidence in this repository comes from.
- **CarSky cloud platform:** account access and a generic running AAOS/IVI node with ADB access are
  demonstrated (image 5). SafeDrive has not yet been installed, run, or exercised on CarSky
  specifically. These are two separate claims and must not be conflated.

---

## 4. GitHub synchronization

- **Repository:** `https://github.com/thagn123/SafeDrive.git`
- **Evidence source branch:** `claude/carsky-deployment` (commit `666b5c4`) — screenshots and this
  document's factual content originate there.
- **Reconciled onto:** `claude/competition-integration`, which carries the actual verified backend
  code (branched from `claude/deployment-stabilization`); `claude/carsky-deployment` itself has no
  backend/Android code changes beyond `claude/final-dtc-grounding` — confirmed by
  `git diff --name-status origin/main...origin/claude/carsky-deployment` showing an identical file
  list to `origin/claude/final-dtc-grounding` plus only the evidence files.
- **Evidence directory:** `evidence/carsky/` — 4 PNG screenshots only. The two files present in an
  earlier commit on `claude/carsky-deployment` (`carsky_app_bundle.js`, `carsky_dashboard.html`)
  were a raw, minified copy of CarSky's own frontend JavaScript bundle, not evidence of anything —
  they were already removed upstream and are not carried into this branch.
- **Secret scan:** full history of `evidence/*` and `docs/CARSKY*` on `claude/carsky-deployment`
  was searched for JWTs, bearer tokens, cookies, and PEM blocks. The only matches were an empty
  `Authorization: Bearer ` field and a literal `Authorization: Bearer <key>` placeholder string
  inside CarSky's own UI copy (documentation text, not a real credential). No genuine secret found.

---

## 5. Exact next user action

1. Install the SafeDrive APK onto the CarSky `IVI - Android` node (on a blueprint actually wired
   for SafeDrive, not the generic `Haui AVS` demo blueprint) and capture a screenshot/video of it
   actually running there, before any status above is upgraded past `NOT_VERIFIED`.
2. Submit team artifacts on `https://hackathon-2.carsky.io/` once ready, and capture the
   confirmation receipt as evidence.
