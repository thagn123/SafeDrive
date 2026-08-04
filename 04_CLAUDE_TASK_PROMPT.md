# Claude Task Prompt

Copy prompt nay vao Claude khi can tiep tuc build.

```text
Ban dang lam trong folder C:\Users\Admin\Downloads\SafeDrive.

Hay doc cac file sau truoc khi sua code:
- 00_CLAUDE_READ_ME_FIRST.md
- 01_PROJECT_INTENT.md
- 02_INTEGRATION_PLAN.md
- 03_TARGET_CONTRACT_SUMMARY.md
- 04_CLAUDE_TASK_PROMPT.md

Muc tieu: lam cho hai repo SafeDrive khop nhau ma van giu dung intent san pham:
SafeDrive la AI Companion cho Digital Cockpit. O che do thuong no lam Voice-Controlled Assistant tot; khi context cho thay met moi, loi xe, tai nan/no-response, no chuyen sang Safety Guardian va co rescue/SOS simulation. Khong goi cap cuu that. Khong chan doan y te. LLM khong doc raw video/raw CAN.

Hai repo:
- backend AI/safedrive-ai-backend: backend canonical signal/state/risk/assistant/rescue.
- safedrive-ai (1): web/Android cockpit UI/voice/SOS/simulator.

Van de hien tai:
- Backend dang co /api/v1/signals va /api/v1/state?vehicle_id/trip_id.
- App dang goi /sessions/start, /state/update, /assistant/query, /events, /actions/confirm, /emergency.

Hay sua theo chien luoc:
1. Khong viet lai app truoc.
2. Them compatibility API vao backend de match safedrive-ai (1)/openapi/safedrive-v1.yaml.
3. Giu raw signal endpoints cu.
4. Them test backend cho cac endpoint app-facing.
5. Chay pytest backend.
6. Sau khi backend pass, chay Android unit tests neu can.

Endpoint backend can co:
- GET /health
- POST /api/v1/sessions/start
- POST /api/v1/state/update
- GET /api/v1/state?sessionId=...
- POST /api/v1/assistant/query
- POST /api/v1/events
- POST /api/v1/actions/confirm
- GET /api/v1/emergency/{id}
- POST /api/v1/emergency/{id}/respond

Acceptance:
- Android DTO parse duoc response.
- realEmergencyDispatchEnabled luon false.
- state/update map duoc app state thanh canonical state hoac compatibility session state.
- assistant/query tra cau tra loi dua tren context, khong tra chung chung.
- crash/no-response tao emergency snapshot va rescue brief simulation.

Khong duoc:
- commit key/secret.
- doi contract Android tuy tien neu co the fix backend.
- tra realEmergencyDispatchEnabled=true.
- noi AI chan doan chan thuong/benh.
- gui raw video/audio/CAN vao LLM.
```

