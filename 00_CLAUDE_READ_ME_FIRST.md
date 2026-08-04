# SafeDrive - Claude Read Me First

## Required product-context document

Before modifying code, read [00_SAFEDRIVE_MASTER_CONTEXT.md](00_SAFEDRIVE_MASTER_CONTEXT.md) in full. It is the authoritative English product description and takes priority over older Vietnamese handoff notes where wording differs.

SafeDrive is a full Digital Cockpit AI Companion: its normal assistant must understand the user and the precise current vehicle context, while a continuous Safety Guardian evaluates verified risks and can initiate a rescue **simulation**. It is neither a generic chatbot nor an emergency-only feature.

Tai lieu nay la file dau tien Claude phai doc truoc khi sua bat ky code nao trong folder `SafeDrive`.

## 1. Muc tieu san pham

SafeDrive khong chi la mot chatbot tren xe. SafeDrive la mot AI Companion cho Digital Cockpit:

- O che do binh thuong, SafeDrive nghe, hieu va thuc hien cac tac vu cua Voice-Controlled Assistant: HVAC, media, am luong, cua, thong tin xe, DTC, infotainment, hanh trinh.
- O che do context-aware, SafeDrive phai hieu dung cau noi cua nguoi dung dua tren tinh trang xe, tinh trang nguoi lai, cabin, loi xe, crash/no-response va lich su hoi thoai.
- Khi co dau hieu nguy hiem, SafeDrive chuyen sang Safety Guardian: xac minh tinh trang, uu tien hanh dong an toan, va kich hoat rescue/SOS simulation neu nguoi dung khong phan hoi.

Thong diep san pham can giu:

> Se la mot vien canh dep khi ban tin thoi su noi rang mot phan mem AI da kip thoi gui tin hieu cap cuu, giup cuu song nan nhan bat tinh sau tai nan.

Nghia la diem khac biet cua SafeDrive la: AI khong chi giao tiep va nhan lenh, ma con co the doc context, nhan dien tinh huong bat thuong, hieu tinh trang cua xe/nguoi, va gui goi thong tin cuu ho kip thoi bang API/SOS simulation.

## 2. Hai repo can khop voi nhau

Sau khi copy, folder nen co dang:

```text
SafeDrive/
  backend AI/
    safedrive-ai-backend/
    safedrive-ai-android/
  safedrive-ai (1)/
    src/
    android/
    openapi/
```

Vai tro mong muon:

- `backend AI/safedrive-ai-backend`: backend canonical. No la noi nhan/canonicalize signal, quan ly state, freshness, rolling window, safety/risk va response cho app.
- `safedrive-ai (1)`: app UI/web/Android cockpit. No la lop trai nghiem nguoi dung: cockpit screen, assistant, SOS panel, simulator, voice.

Khong duoc de hai repo tiep tuc moi ben mot contract rieng.

## 3. Van de hien tai

Backend hien tai dang thien ve signal-first:

```text
POST /api/v1/signals
GET  /api/v1/state?vehicle_id=...&trip_id=...
```

App trong `safedrive-ai (1)` dang goi contract mobile-first:

```text
GET  /health
POST /api/v1/sessions/start
POST /api/v1/state/update
GET  /api/v1/state?sessionId=...&sinceVersion=...
POST /api/v1/assistant/query
POST /api/v1/events
POST /api/v1/actions/confirm
GET  /api/v1/emergency/{id}
POST /api/v1/emergency/{id}/respond
```

Do do, viec can lam khong phai tron code tuy tien. Can them mot lop compatibility API tren backend de app goi duoc, trong khi backend van giu canonical signal/state manager lam loi.

## 4. Quyet dinh kien truc

Chon `safedrive-ai (1)/openapi/safedrive-v1.yaml` lam contract app-facing.

Backend can bo sung cac route app-facing nay:

- `/health`: tra dung shape Android can: `status`, `service`, `apiVersion`, `serverTimeMs`, `capabilities`.
- `/api/v1/sessions/start`: tao session demo/local.
- `/api/v1/state/update`: nhan `VehicleStateDto`, map sang canonical signals, cap nhat state manager, tra `StateEnvelope`.
- `/api/v1/state`: neu query co `sessionId`, tra envelope app-facing; neu query co `vehicle_id/trip_id`, van giu raw canonical state cũ.
- `/api/v1/assistant/query`: xu ly voice/text intent toi thieu dua tren context moi nhat.
- `/api/v1/events`: nhan event simulator/voice/connection.
- `/api/v1/actions/confirm`: confirm action mo phong.
- `/api/v1/emergency/{id}` va `/respond`: SOS simulation only.

## 5. Nguyen tac an toan bat buoc

Khong bao gio implement goi cap cuu that.

Tat ca rescue/SOS phai la simulation:

```json
{
  "realEmergencyDispatchEnabled": false,
  "dispatchMode": "SIMULATION_ONLY"
}
```

Khong chan doan y te. Chi duoc noi:

- "dau hieu bat thuong"
- "khong co phan hoi"
- "co nguy co an toan"
- "can xac minh tinh trang"

Khong duoc noi:

- "hanh khach bi chan thuong X"
- "tai xe bi benh Y"
- "AI da goi 115 that"

LLM khong duoc nhan raw video/raw CAN stream. LLM chi nhan context pack da rut gon:

- latest state
- freshness/age
- rolling features
- risk level
- reason codes
- allowed actions
- missing context

## 6. Nguyen tac build

Lam tung phase, moi phase phai co test.

Thu tu uu tien:

1. Contract alignment: backend tra duoc JSON app parse duoc.
2. State update bridge: app push state, backend map thanh canonical signals.
3. Assistant query: backend tra cau tra loi dua tren current context.
4. Safety/risk: fatigue, DTC, crash/no-response, passenger abnormal.
5. Rescue bridge: tao rescue payload simulation gom vi tri, tinh trang xe, mo ta ngan gon, bang chung/risk.
6. Android/web remote mode test.

Khong them tinh nang lon khi contract chua xanh.

## 7. Definition of Done

Mot buoc chi duoc coi la xong neu:

- Co test backend hoac Android/web contract pass.
- App remote client khong bi parse crash.
- Response dung schema.
- `realEmergencyDispatchEnabled` luon false.
- Neu thieu context, assistant phai noi ro la thieu context, khong duoc bia.
- Neu la emergency, safety/risk engine uu tien hon LLM.

## 8. Lenh goi y

Backend:

```powershell
cd "C:\Users\Admin\Downloads\SafeDrive\backend AI\safedrive-ai-backend"
$env:ENVIRONMENT="development"
$env:ACTIVE_PROFILE="DMS_DEMO"
$env:SAFEDRIVE_API_KEY="local-android-debug-key"
uv run --locked pytest
uv run --locked uvicorn app.main:app --host 0.0.0.0 --port 8000
```

Android/web app:

```powershell
cd "C:\Users\Admin\Downloads\SafeDrive\safedrive-ai (1)"
npm install
npm run dev
```

Android unit tests:

```powershell
cd "C:\Users\Admin\Downloads\SafeDrive\safedrive-ai (1)\android"
.\gradlew.bat testDebugUnitTest
```

## 9. Neu phai chon huong sua nhanh

Khong sua Android truoc. Sua backend de tuong thich contract Android truoc.

Ly do:

- Android/web da co nhieu UI/flow/contract tests.
- Backend da co signal/state loi tot nhung thieu app-facing endpoints.
- Them compatibility layer it rui ro hon viet lai app.
