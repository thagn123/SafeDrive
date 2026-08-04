# SafeDrive AI Backend - Capability Matrix

## Profiles

### 1. PRODUCTION_NO_DMS (Default)
- **Description:** Profile used in production environments where DMS or cabin cameras are not available.
- **Rules:** Cannot evaluate drowsiness based on PERCLOS, eye closure, etc. Cannot automatically trigger SOS based on occupant unresponsiveness without crash.

### 2. DMS_DEMO
- **Description:** Profile used for demonstration and simulation, with simulated camera signals.
- **Rules:** All camera/DMS features must carry `metadata.simulated=true` in their signal.

## Signal Registry Matrix

| Signal Group | Signal Type | Owner | Source | TTL (s) | Lateness (s) | Simulated Demo |
|---|---|---|---|---|---|---|
| Speed | vehicle.speed_kmh | VHAL | VHAL, SIMULATOR | 2.0 | 1.0 | Optional |
| Crash | vehicle.crash | VHAL | VHAL, SIMULATOR | 0.5 | 1.0 | Optional |
| Seatbelt | vehicle.seatbelt | VHAL | VHAL, SIMULATOR | 5.0 | 2.0 | Optional |
| Parking Brake | vehicle.parking_brake | VHAL | VHAL, SIMULATOR | 5.0 | 2.0 | Optional |
| Door Open | vehicle.door_open | VHAL | VHAL, SIMULATOR | 5.0 | 2.0 | Optional |
| Window Open | vehicle.window_open | VHAL | VHAL, SIMULATOR | 5.0 | 2.0 | Optional |
| Gear | vehicle.gear | VHAL | VHAL, SIMULATOR | 5.0 | 2.0 | Optional |
| Steering Angle| vehicle.steering_angle| VHAL | VHAL, SIMULATOR | 2.0 | 1.0 | Optional |
| Tire Pressure | vehicle.tire_pressure | VHAL | VHAL, SIMULATOR | 60.0 | 5.0 | Optional |
| Brake Pedal | vehicle.brake_pedal | VHAL | VHAL, SIMULATOR | 2.0 | 1.0 | Optional |
| Accel Pedal | vehicle.accelerator_pedal| VHAL | VHAL, SIMULATOR | 2.0 | 1.0 | Optional |
| GPS | vehicle.gps | GPS | GPS, SIMULATOR | 30.0 | 5.0 | Optional |
| HVAC Temp | hvac.temperature | HVAC | VHAL, SIMULATOR | 10.0 | 2.0 | Optional |
| HVAC Fan | hvac.fan_speed | HVAC | VHAL, SIMULATOR | 10.0 | 2.0 | Optional |
| HVAC AC | hvac.ac_status | HVAC | VHAL, SIMULATOR | 10.0 | 2.0 | Optional |
| DTC | dtc.code | DTC | DTC, SIMULATOR | 60.0 | 10.0 | Optional |
| Driver DMS | driver.perclos | DMS | SIMULATOR | 2.0 | 2.0 | Required |
| Driver DMS | driver.eye_closure | DMS | SIMULATOR | 2.0 | 2.0 | Required |
| Driver DMS | driver.yawning | DMS | SIMULATOR | 2.0 | 2.0 | Required |
| Driver DMS | driver.head_pose | DMS | SIMULATOR | 2.0 | 2.0 | Required |
| Driver DMS | driver.gaze | DMS | SIMULATOR | 2.0 | 2.0 | Required |
| Passenger | passenger.occupancy | CABIN_CAMERA | SIMULATOR | 5.0 | 2.0 | Required |
| Passenger | passenger.motion | CABIN_CAMERA | SIMULATOR | 5.0 | 2.0 | Required |
| Passenger | passenger.posture | CABIN_CAMERA | SIMULATOR | 5.0 | 2.0 | Required |
| Passenger | passenger.head_position | CABIN_CAMERA | SIMULATOR | 5.0 | 2.0 | Required |
