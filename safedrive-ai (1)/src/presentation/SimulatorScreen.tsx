import React, { useState } from 'react';
import { useSafeDrive } from '../context/SafeDriveContext';
import { SCENARIO_PRESETS, DTC_P0301, DTC_OVERHEAT } from '../data/mock/mockRepository';
import { ScenarioPresetCard } from '../components/simulator/ScenarioPresetCard';
import { JsonPreviewModal } from '../components/simulator/JsonPreviewModal';
import { 
  SlidersHorizontal, 
  RotateCcw, 
  Code, 
  Gauge, 
  Thermometer, 
  Flame, 
  Eye, 
  EyeOff, 
  Siren, 
  Wrench,
  Check
} from 'lucide-react';

export const SimulatorScreen: React.FC = () => {
  const { 
    vehicleState, 
    updateVehicleState, 
    applyPresetScenario, 
    resetToDefault,
    riskAssessment 
  } = useSafeDrive();

  const [selectedPresetId, setSelectedPresetId] = useState<string>('new_trip');
  const [isJsonModalOpen, setIsJsonModalOpen] = useState(false);

  // Local form state for telemetry
  const [speed, setSpeed] = useState(vehicleState.speedKmh);
  const [cabinTemp, setCabinTemp] = useState(vehicleState.cabinTemperatureC);
  const [engineTemp, setEngineTemp] = useState(vehicleState.engineTemperatureC);

  // Driver support signals state
  const [drivingMins, setDrivingMins] = useState<number>(vehicleState.driverSupportSignals.continuousDrivingMinutes ?? 0);
  const [hasDrivingMins, setHasDrivingMins] = useState<boolean>(vehicleState.driverSupportSignals.continuousDrivingMinutes !== null);
  const [steeringAvailable, setSteeringAvailable] = useState<boolean>(vehicleState.driverSupportSignals.steeringSignalAvailable);
  const [seatAvailable, setSeatAvailable] = useState<boolean>(vehicleState.driverSupportSignals.seatSensorAvailable);
  const [seatOccupied, setSeatOccupied] = useState<boolean>(vehicleState.driverSupportSignals.driverSeatOccupied ?? true);
  const [wearableConnected, setWearableConnected] = useState<boolean>(vehicleState.driverSupportSignals.wearableConnected);
  const [wearableHr, setWearableHr] = useState<number>(vehicleState.driverSupportSignals.wearableHeartRateBpm ?? 72);
  const [userFatigue, setUserFatigue] = useState<boolean>(vehicleState.driverSupportSignals.userReportedFatigue);

  const [hasCrash, setHasCrash] = useState(vehicleState.crashDetected);
  const [isUnresponsive, setIsUnresponsive] = useState(vehicleState.passengerResponse === 'NO_RESPONSE');
  const [dtcType, setDtcType] = useState<'none' | 'P0301' | 'OVERHEAT'>(
    vehicleState.activeDtcs.some(d => d.code === 'P0301') ? 'P0301' :
    vehicleState.activeDtcs.some(d => d.code === 'ENGINE_OVERHEAT') ? 'OVERHEAT' : 'none'
  );

  const handleSelectPreset = (presetId: string) => {
    setSelectedPresetId(presetId);
    applyPresetScenario(presetId);

    const preset = SCENARIO_PRESETS.find(p => p.id === presetId);
    if (preset) {
      setSpeed(preset.vehicleState.speedKmh);
      setCabinTemp(preset.vehicleState.cabinTemperatureC);
      setEngineTemp(preset.vehicleState.engineTemperatureC);
      
      const sigs = preset.vehicleState.driverSupportSignals;
      setHasDrivingMins(sigs.continuousDrivingMinutes !== null);
      setDrivingMins(sigs.continuousDrivingMinutes ?? 0);
      setSteeringAvailable(sigs.steeringSignalAvailable);
      setSeatAvailable(sigs.seatSensorAvailable);
      setSeatOccupied(sigs.driverSeatOccupied ?? true);
      setWearableConnected(sigs.wearableConnected);
      setWearableHr(sigs.wearableHeartRateBpm ?? 72);
      setUserFatigue(sigs.userReportedFatigue);

      setHasCrash(preset.vehicleState.crashDetected);
      setIsUnresponsive(preset.vehicleState.passengerResponse === 'NO_RESPONSE');
      
      if (preset.vehicleState.activeDtcs.some(d => d.code === 'P0301')) setDtcType('P0301');
      else if (preset.vehicleState.activeDtcs.some(d => d.code === 'ENGINE_OVERHEAT')) setDtcType('OVERHEAT');
      else setDtcType('none');
    }
  };

  const handleApplyManual = () => {
    let dtcs = [];
    if (dtcType === 'P0301') dtcs = [DTC_P0301];
    else if (dtcType === 'OVERHEAT') dtcs = [DTC_OVERHEAT];

    updateVehicleState({
      speedKmh: Number(speed),
      cabinTemperatureC: Number(cabinTemp),
      engineTemperatureC: Number(engineTemp),
      driverSupportSignals: {
        continuousDrivingMinutes: hasDrivingMins ? Number(drivingMins) : null,
        lastSteeringInteractionMs: steeringAvailable ? Date.now() - 10000 : null,
        steeringSignalAvailable: steeringAvailable,
        driverSeatOccupied: seatOccupied,
        seatSensorAvailable: seatAvailable,
        wearableConnected: wearableConnected,
        wearableLastUpdateMs: wearableConnected ? Date.now() - 5000 : null,
        wearableHeartRateBpm: wearableConnected ? Number(wearableHr) : null,
        wearablePermissionGranted: wearableConnected,
        userReportedFatigue: userFatigue
      },
      crashDetected: hasCrash,
      passengerResponse: isUnresponsive ? 'NO_RESPONSE' : 'RESPONSIVE',
      activeDtcs: dtcs
    });
  };

  const handleReset = () => {
    resetToDefault();
    setSelectedPresetId('new_trip');
    setSpeed(62);
    setCabinTemp(25);
    setEngineTemp(92);
    setHasDrivingMins(true);
    setDrivingMins(135);
    setSteeringAvailable(true);
    setSeatAvailable(true);
    setSeatOccupied(true);
    setWearableConnected(false);
    setWearableHr(72);
    setUserFatigue(false);
    setHasCrash(false);
    setIsUnresponsive(false);
    setDtcType('none');
  };

  return (
    <div className="space-y-6 pb-24 animate-fadeIn">
      {/* Header */}
      <header className="p-4 rounded-2xl bg-[#132437] border border-slate-800 shadow-md">
        <div className="flex items-center gap-3">
          <div className="p-2.5 rounded-xl bg-cyan-500/10 border border-cyan-500/20 text-cyan-400">
            <SlidersHorizontal size={22} />
          </div>
          <div>
            <h2 className="text-lg font-bold text-white">Mô phỏng xe (Vehicle Simulator)</h2>
            <p className="text-xs text-slate-400 mt-0.5">
              Dữ liệu tại đây được dùng để kiểm thử toàn bộ giao diện SafeDrive AI theo thời gian thực.
            </p>
          </div>
        </div>
      </header>

      {/* Preset Scenarios */}
      <section className="space-y-3">
        <h3 className="text-sm font-bold uppercase tracking-wider text-slate-400">Kịch bản thử nghiệm mẫu (Preset Scenarios)</h3>
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3">
          {SCENARIO_PRESETS.map(preset => (
            <ScenarioPresetCard 
              key={preset.id}
              preset={preset}
              isSelected={selectedPresetId === preset.id}
              onSelect={() => handleSelectPreset(preset.id)}
            />
          ))}
        </div>
      </section>

      {/* Manual Controls Grid */}
      <section className="p-5 rounded-2xl bg-[#132437] border border-slate-800 space-y-6">
        <div className="flex items-center justify-between border-b border-slate-800 pb-3">
          <h3 className="text-base font-bold text-white">Tùy chỉnh thông số thủ công</h3>
          <span className="text-xs text-cyan-400 font-mono">Manual Telemetry Override</span>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
          {/* Speed Slider */}
          <div className="space-y-2">
            <div className="flex justify-between text-xs">
              <span className="text-slate-300 font-medium flex items-center gap-1.5">
                <Gauge size={16} className="text-cyan-400" /> Tốc độ (0 - 160 km/h)
              </span>
              <strong className="text-cyan-400 font-mono text-sm">{speed} km/h</strong>
            </div>
            <input 
              type="range" 
              min="0" 
              max="160" 
              value={speed}
              onChange={(e) => setSpeed(Number(e.target.value))}
              className="w-full h-2 bg-slate-800 rounded-lg appearance-none cursor-pointer accent-cyan-400"
            />
          </div>

          {/* Cabin Temperature Slider */}
          <div className="space-y-2">
            <div className="flex justify-between text-xs">
              <span className="text-slate-300 font-medium flex items-center gap-1.5">
                <Thermometer size={16} className="text-emerald-400" /> Nhiệt độ cabin (16 - 35°C)
              </span>
              <strong className="text-emerald-400 font-mono text-sm">{cabinTemp}°C</strong>
            </div>
            <input 
              type="range" 
              min="16" 
              max="35" 
              value={cabinTemp}
              onChange={(e) => setCabinTemp(Number(e.target.value))}
              className="w-full h-2 bg-slate-800 rounded-lg appearance-none cursor-pointer accent-emerald-400"
            />
          </div>

          {/* Engine Temperature Slider */}
          <div className="space-y-2">
            <div className="flex justify-between text-xs">
              <span className="text-slate-300 font-medium flex items-center gap-1.5">
                <Flame size={16} className="text-amber-400" /> Nhiệt độ động cơ (70 - 125°C)
              </span>
              <strong className={`font-mono text-sm ${engineTemp >= 105 ? 'text-red-400 font-bold' : 'text-amber-400'}`}>
                {engineTemp}°C
              </strong>
            </div>
            <input 
              type="range" 
              min="70" 
              max="125" 
              value={engineTemp}
              onChange={(e) => setEngineTemp(Number(e.target.value))}
              className="w-full h-2 bg-slate-800 rounded-lg appearance-none cursor-pointer accent-amber-400"
            />
          </div>

          {/* Continuous Driving Minutes */}
          <div className="space-y-2 col-span-1 md:col-span-2 p-3.5 rounded-xl bg-[#08131F] border border-slate-800">
            <div className="flex items-center justify-between text-xs">
              <label className="text-slate-200 font-bold flex items-center gap-2">
                <span>Thời gian lái xe liên tục ({hasDrivingMins ? `${drivingMins} phút` : 'Chưa có dữ liệu'})</span>
              </label>
              <label className="flex items-center gap-2 text-xs text-slate-400 cursor-pointer">
                <input 
                  type="checkbox" 
                  checked={hasDrivingMins} 
                  onChange={(e) => setHasDrivingMins(e.target.checked)}
                  className="w-4 h-4 accent-cyan-400 rounded" 
                />
                <span>Có dữ liệu thời gian</span>
              </label>
            </div>
            {hasDrivingMins && (
              <input 
                type="range" 
                min="0" 
                max="300" 
                step="5"
                value={drivingMins}
                onChange={(e) => setDrivingMins(Number(e.target.value))}
                className="w-full h-2 bg-slate-800 rounded-lg appearance-none cursor-pointer accent-cyan-400"
              />
            )}
          </div>

          {/* Signal Toggles: Steering, Seat, Wearable, User Fatigue */}
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-3 col-span-1 md:col-span-2">
            <label className="flex items-center justify-between p-3 rounded-xl bg-[#08131F] border border-slate-800 cursor-pointer text-xs">
              <span className="font-semibold text-slate-200">Cảm biến vô lăng khả dụng</span>
              <input 
                type="checkbox" 
                checked={steeringAvailable} 
                onChange={(e) => setSteeringAvailable(e.target.checked)}
                className="w-4 h-4 accent-cyan-400 rounded" 
              />
            </label>

            <label className="flex items-center justify-between p-3 rounded-xl bg-[#08131F] border border-slate-800 cursor-pointer text-xs">
              <span className="font-semibold text-slate-200">Cảm biến ghế lái khả dụng</span>
              <input 
                type="checkbox" 
                checked={seatAvailable} 
                onChange={(e) => setSeatAvailable(e.target.checked)}
                className="w-4 h-4 accent-cyan-400 rounded" 
              />
            </label>

            <label className="flex items-center justify-between p-3 rounded-xl bg-[#08131F] border border-slate-800 cursor-pointer text-xs">
              <span className="font-semibold text-slate-200">Phát hiện người ngồi ghế lái</span>
              <input 
                type="checkbox" 
                checked={seatOccupied} 
                onChange={(e) => setSeatOccupied(e.target.checked)}
                disabled={!seatAvailable}
                className="w-4 h-4 accent-cyan-400 rounded" 
              />
            </label>

            <label className="flex items-center justify-between p-3 rounded-xl bg-[#08131F] border border-slate-800 cursor-pointer text-xs">
              <span className="font-semibold text-slate-200">Kết nối thiết bị Wearable</span>
              <input 
                type="checkbox" 
                checked={wearableConnected} 
                onChange={(e) => setWearableConnected(e.target.checked)}
                className="w-4 h-4 accent-cyan-400 rounded" 
              />
            </label>

            <label className="flex items-center justify-between p-3 rounded-xl bg-[#08131F] border border-slate-800 cursor-pointer text-xs sm:col-span-2">
              <span className="font-semibold text-amber-300">Người dùng báo đang cảm thấy mệt</span>
              <input 
                type="checkbox" 
                checked={userFatigue} 
                onChange={(e) => setUserFatigue(e.target.checked)}
                className="w-4 h-4 accent-amber-400 rounded" 
              />
            </label>
          </div>

          {/* DTC Selector */}
          <div className="space-y-2">
            <label className="text-slate-300 text-xs font-medium flex items-center gap-1.5">
              <Wrench size={16} className="text-indigo-400" /> Mã lỗi chẩn đoán DTC
            </label>
            <select
              value={dtcType}
              onChange={(e) => setDtcType(e.target.value as any)}
              className="w-full min-h-[44px] px-3 rounded-xl bg-[#08131F] border border-slate-700 text-white text-xs focus:outline-none focus:border-cyan-500"
            >
              <option value="none">Không có lỗi (DTC Nominal)</option>
              <option value="P0301">P0301 - Misfire xi-lanh số 1 (MEDIUM)</option>
              <option value="OVERHEAT">ENGINE_OVERHEAT - Quá nhiệt động cơ (HIGH)</option>
            </select>
          </div>
        </div>

        {/* Switches */}
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-4 pt-2 border-t border-slate-800">
          <label className="flex items-center justify-between p-3.5 rounded-xl bg-[#08131F] border border-slate-800 cursor-pointer">
            <div className="flex items-center gap-2">
              <Siren size={18} className={hasCrash ? 'text-red-400 animate-pulse' : 'text-slate-400'} />
              <span className="text-xs font-bold text-white">Phát hiện va chạm xe</span>
            </div>
            <input 
              type="checkbox" 
              checked={hasCrash}
              onChange={(e) => setHasCrash(e.target.checked)}
              className="w-5 h-5 accent-red-500 rounded cursor-pointer"
            />
          </label>

          <label className="flex items-center justify-between p-3.5 rounded-xl bg-[#08131F] border border-slate-800 cursor-pointer">
            <div className="flex items-center gap-2">
              <EyeOff size={18} className={isUnresponsive ? 'text-red-400' : 'text-slate-400'} />
              <span className="text-xs font-bold text-white">Hành khách KHÔNG PHẢN HỒI</span>
            </div>
            <input 
              type="checkbox" 
              checked={isUnresponsive}
              onChange={(e) => setIsUnresponsive(e.target.checked)}
              className="w-5 h-5 accent-red-500 rounded cursor-pointer"
            />
          </label>
        </div>

        {/* Bottom Actions */}
        <div className="flex flex-col sm:flex-row gap-3 pt-4 border-t border-slate-800">
          <button
            onClick={handleApplyManual}
            type="button"
            className="flex-1 min-h-[48px] px-5 rounded-xl bg-cyan-500 hover:bg-cyan-400 text-slate-950 font-bold text-sm shadow-lg shadow-cyan-500/20 transition flex items-center justify-center gap-2"
          >
            <Check size={18} />
            <span>Áp dụng trạng thái</span>
          </button>

          <button
            onClick={handleReset}
            type="button"
            className="min-h-[48px] px-5 rounded-xl bg-slate-800 hover:bg-slate-700 text-slate-200 font-medium text-sm transition flex items-center justify-center gap-2"
          >
            <RotateCcw size={18} />
            <span>Khôi phục bình thường</span>
          </button>

          <button
            onClick={() => setIsJsonModalOpen(true)}
            type="button"
            className="min-h-[48px] px-4 rounded-xl bg-[#08131F] hover:bg-slate-800 text-cyan-300 border border-slate-700 text-xs font-bold transition flex items-center justify-center gap-2"
          >
            <Code size={18} />
            <span>Xem JSON demo</span>
          </button>
        </div>
      </section>

      {/* JSON Modal */}
      <JsonPreviewModal 
        vehicleState={vehicleState}
        riskAssessment={riskAssessment}
        isOpen={isJsonModalOpen}
        onClose={() => setIsJsonModalOpen(false)}
      />
    </div>
  );
};
