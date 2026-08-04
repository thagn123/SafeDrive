import React from 'react';
import { Gauge, Flame, BatteryCharging, Clock, Thermometer } from 'lucide-react';

interface VehicleOverviewPanelProps {
  speedKmh: number;
  engineTemperatureC: number;
  energyPercent?: number;
  continuousDrivingMinutes: number | null;
  cabinTemperatureC?: number;
  onMetricClick?: () => void;
}

export const VehicleOverviewPanel: React.FC<VehicleOverviewPanelProps> = ({
  speedKmh,
  engineTemperatureC,
  energyPercent = 74,
  continuousDrivingMinutes,
  cabinTemperatureC = 25,
  onMetricClick
}) => {
  const isEngineHot = engineTemperatureC >= 105;
  const isDrivingLong = (continuousDrivingMinutes ?? 0) >= 120;

  const formatDrivingTime = (mins: number | null) => {
    if (mins === null) return '--';
    const h = Math.floor(mins / 60);
    const m = mins % 60;
    if (h === 0) return `${m}m`;
    if (m === 0) return `${h}h`;
    return `${h}h ${m}m`;
  };

  return (
    <div 
      onClick={onMetricClick}
      className="p-3 sm:p-3.5 rounded-2xl bg-[#132437] border border-slate-800 shadow-md flex flex-col justify-between cursor-pointer hover:border-cyan-500/40 transition shrink-0 min-h-[120px]"
    >
      {/* Header Label */}
      <div className="flex items-center justify-between gap-2">
        <span className="text-[10px] font-extrabold uppercase tracking-widest text-slate-400">
          TỔNG QUAN XE
        </span>
        <div className="flex items-center gap-1.5 px-2 py-0.5 rounded-md bg-slate-900 border border-slate-800 text-[10px] text-slate-300 font-semibold">
          <Thermometer size={12} className="text-cyan-400 shrink-0" />
          <span>Cabin {cabinTemperatureC}°C</span>
        </div>
      </div>

      {/* Grid 2x2 for 4 Metrics */}
      <div className="grid grid-cols-2 gap-2 my-1">
        {/* Speed */}
        <div className="p-2.5 rounded-xl bg-[#0B1724] border border-slate-800/80 flex items-center gap-2.5">
          <div className="p-1.5 rounded-lg bg-cyan-500/15 text-cyan-400 shrink-0">
            <Gauge size={16} />
          </div>
          <div>
            <div className="text-[9px] font-bold uppercase tracking-wider text-slate-400">Tốc độ</div>
            <div className="text-sm sm:text-base font-black text-white font-mono leading-none mt-0.5">
              {speedKmh} <span className="text-[10px] text-slate-400 font-normal">km/h</span>
            </div>
          </div>
        </div>

        {/* Engine Temp */}
        <div className={`p-2.5 rounded-xl bg-[#0B1724] border flex items-center gap-2.5 ${
          isEngineHot ? 'border-amber-500/60 bg-amber-950/20' : 'border-slate-800/80'
        }`}>
          <div className={`p-1.5 rounded-lg shrink-0 ${
            isEngineHot ? 'bg-amber-500/20 text-amber-400 animate-pulse' : 'bg-emerald-500/15 text-emerald-400'
          }`}>
            <Flame size={16} />
          </div>
          <div>
            <div className="text-[9px] font-bold uppercase tracking-wider text-slate-400">Động cơ</div>
            <div className={`text-sm sm:text-base font-black font-mono leading-none mt-0.5 ${
              isEngineHot ? 'text-amber-400' : 'text-white'
            }`}>
              {engineTemperatureC}<span className="text-[10px] text-slate-400 font-normal">°C</span>
            </div>
          </div>
        </div>

        {/* Energy / Fuel */}
        <div className="p-2.5 rounded-xl bg-[#0B1724] border border-slate-800/80 flex items-center gap-2.5">
          <div className="p-1.5 rounded-lg bg-cyan-500/15 text-cyan-400 shrink-0">
            <BatteryCharging size={16} />
          </div>
          <div>
            <div className="text-[9px] font-bold uppercase tracking-wider text-slate-400">Năng lượng</div>
            <div className="text-sm sm:text-base font-black text-white font-mono leading-none mt-0.5">
              {energyPercent}<span className="text-[10px] text-slate-400 font-normal">%</span>
            </div>
          </div>
        </div>

        {/* Driving Time */}
        <div className={`p-2.5 rounded-xl bg-[#0B1724] border flex items-center gap-2.5 ${
          isDrivingLong ? 'border-amber-500/50 bg-amber-950/10' : 'border-slate-800/80'
        }`}>
          <div className={`p-1.5 rounded-lg shrink-0 ${
            isDrivingLong ? 'bg-amber-500/20 text-amber-400' : 'bg-cyan-500/15 text-cyan-400'
          }`}>
            <Clock size={16} />
          </div>
          <div>
            <div className="text-[9px] font-bold uppercase tracking-wider text-slate-400">Thời gian</div>
            <div className={`text-sm sm:text-base font-black font-mono leading-none mt-0.5 ${
              isDrivingLong ? 'text-amber-400' : 'text-white'
            }`}>
              {formatDrivingTime(continuousDrivingMinutes)}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};
