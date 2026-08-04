# SafeDrive Project Intent

## Vision

SafeDrive AI Companion la nguoi ban dong hanh tren Digital Cockpit. He thong vua lam tot vai tro Voice-Controlled Assistant binh thuong, vua co kha nang hieu context va bao ve nguoi dung trong tinh huong nguy hiem.

## Pain point

Xe hien dai co rat nhieu du lieu: toc do, nhiet do, pin, HVAC, DTC, seatbelt, crash signal, driver fatigue, passenger state. Nhung nguoi lai thuong van phai tu hieu cac tin hieu roi rac va tu quyet dinh phai lam gi.

Trong tinh huong rui ro, van de khong phai thieu du lieu. Van de la:

- Du lieu qua nhieu va tach roi.
- Voice assistant chi nghe lenh don gian.
- Nguoi dung noi mo ho: "toi khong on", "xe co gi do la", "toi hoi buon ngu".
- He thong can biet context xe va nguoi truoc khi phan hoi.
- Khi tai nan/no-response xay ra, tin hieu cuu ho can duoc gui kip thoi.

## Core value

SafeDrive bien Digital Cockpit tu man hinh hien thi thanh mot lop companion/safety:

1. Hieu nguoi dung tot hon: intent + context + history + risk.
2. Hieu xe tot hon: state chinh xac, freshness, DTC, cabin, crash.
3. Hanh dong an toan hon: tool/action chi duoc goi neu nam trong allowed actions.
4. Cuu ho kip thoi hon: khi risk critical/no-response, he thong tao rescue brief gom tinh trang xe, vi tri, bang chung va gui qua API/SOS simulation.

## Normal mode

O che do thuong, SafeDrive van phai lam tot cac tac vu:

- Dieu khien HVAC.
- Dieu khien media/am luong.
- Hoi tinh trang xe.
- Hoi loi DTC.
- Hoi thong tin hanh trinh.
- Tro chuyen dong hanh.
- Giai thich tinh trang xe bang ngon ngu de hieu.

Nhung khong chi map keyword. Moi cau tra loi nen dua tren context hien tai neu lien quan.

Vi du:

User: "Trong xe nong qua."

SafeDrive can doc:

- cabinTemperatureC
- HVAC state
- energyPercent
- speedKmh

Roi moi de xuat hanh dong.

## Safety Guardian mode

Khi context cho thay nguy co:

- tai xe met/buon ngu
- xe co DTC nghiem trong
- crashDetected
- passengerResponse = NO_RESPONSE
- passenger/posture/motion bat thuong
- xe dung bat thuong

SafeDrive chuyen sang Safety Guardian.

Safety Guardian can:

- xac minh tinh trang
- hoi nguoi dung neu co the
- dem nguoc SOS neu critical
- tao rescue brief ngan gon
- gui SOS/API simulation

## Rescue payload intent

Khi can gui thong tin cuu ho, payload can gom:

```json
{
  "event": "SOS_SIMULATION",
  "vehicleId": "veh_demo_01",
  "sessionId": "session_demo",
  "location": {
    "lat": 21.0285,
    "lon": 105.8542,
    "source": "GPS_OR_SIMULATOR",
    "freshnessMs": 1200
  },
  "vehicleStatusSummary": "Crash detected, vehicle stopped, driver no response.",
  "riskLevel": "CRITICAL",
  "evidence": [
    "crash_detected",
    "driver_no_response",
    "vehicle_stopped"
  ],
  "realEmergencyDispatchEnabled": false
}
```

No la API/SOS simulation, khong phai cap cuu that.

