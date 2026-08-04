# Phase Checklist

## P0 - Folder structure

- [ ] Copy `C:\Users\Admin\Downloads\backend AI` vao `C:\Users\Admin\Downloads\SafeDrive\backend AI`
- [ ] Copy `C:\Users\Admin\Downloads\safedrive-ai (1)` vao `C:\Users\Admin\Downloads\SafeDrive\safedrive-ai (1)`
- [ ] Claude doc file `00_CLAUDE_READ_ME_FIRST.md`

## P1 - Contract audit

- [ ] Confirm backend current routes.
- [ ] Confirm app expected routes.
- [ ] Confirm DTO names and enum names.
- [ ] Write contract delta note if needed.

## P2 - Backend compatibility API

- [ ] Add mobile schemas.
- [ ] Add session store.
- [ ] Add state update bridge.
- [ ] Add assistant query route.
- [ ] Add event/action route.
- [ ] Add emergency route.
- [ ] Include router.
- [ ] Backend tests pass.

## P3 - App remote smoke

- [ ] Start backend at port 8000.
- [ ] App health check succeeds.
- [ ] Session starts.
- [ ] State update accepted.
- [ ] Assistant query returns message.
- [ ] Emergency simulation endpoint returns snapshot.

## P4 - Safety/Risk

- [ ] Fatigue scenario.
- [ ] Hot cabin + low energy scenario.
- [ ] DTC concern scenario.
- [ ] Crash/no-response scenario.
- [ ] Passenger abnormal/no-response scenario if supported.

## P5 - Rescue bridge

- [ ] Rescue brief has location summary if available.
- [ ] Rescue brief has vehicle status summary.
- [ ] Rescue brief has risk level and evidence.
- [ ] `realEmergencyDispatchEnabled=false`.
- [ ] API/SOS simulation only.

## P6 - Final demo

- [ ] Cockpit normal assistant demo.
- [ ] Context-aware comfort demo.
- [ ] Driver fatigue demo.
- [ ] DTC explanation demo.
- [ ] Crash/no-response SOS simulation demo.

