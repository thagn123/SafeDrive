import React from 'react';
import { Gauge, Flame, Clock } from 'lucide-react';

interface QuickMetricRowProps {
  speedKmh: number;
  engineTemperatureC: number;
  continuousDrivingMinutes: number | null;
  onMetricClick?: () => void;
}

export const QuickMetricRow: React.FC<QuickMetricRowProps> = ({
  speedKmh,
  engineTemperatureC,
  continuousDrivingMinutes,
  onMetricClick
}) => {
  const isEngineHot = engineTemperatureC >= 105;

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
      className="grid grid-cols-3 gap-2.5 sm:gap-3 h-[88px] shrink-0"
    >
      {/* Metric 1: Speed */}
      <div className="p-3 rounded-2xl bg-[#132437] border border-slate-800 flex flex-col justify-between shadow-sm">
        <div className="flex items-center gap-1.5 text-slate-400">
          <Gauge size={15} className="text-cyan-400 shrink-0" />
          <span className="text-[10px] font-bold uppercase tracking-wider">TỐC ĐỘ</span>
        </div>
        <div className="flex items-baseline gap-1">
          <span className="text-lg sm:text-xl font-black text-white font-mono">{speedKmh}</span>
          <span className="text-[11px] text-slate-400 font-semibold">km/h</span>
        </div>
      </div>

      {/* Metric 2: Engine Temperature */}
      <div className={`p-3 rounded-2xl bg-[#132437] border flex flex-col justify-between shadow-sm ${
        isEngineHot ? 'border-amber-500/60 bg-amber-950/20' : 'border-slate-800'
      }`}>
        <div className="flex items-center gap-1.5 text-slate-400">
          <Flame size={15} className={isEngineHot ? 'text-amber-400 animate-pulse shrink-0' : 'text-amber-400 shrink-0'} />
          <span className="text-[10px] font-bold uppercase tracking-wider">ĐỘNG CƠ</span>
        </div>
        <div className="flex items-baseline gap-1">
          <span className={`text-lg sm:text-xl font-black font-mono ${isEngineHot ? 'text-amber-400' : 'text-white'}`}>
            {engineTemperatureC}
          </span>
          <span className="text-[11px] text-slate-400 font-semibold">°C</span>
        </div>
      </div>

      {/* Metric 3: Continuous Driving Time */}
      <div className="p-3 rounded-2xl bg-[#132437] border border-slate-800 flex flex-col justify-between shadow-sm">
        <div className="flex items-center gap-1.5 text-slate-400">
          <Clock size={15} className="text-cyan-400 shrink-0" />
          <span className="text-[10px] font-bold uppercase tracking-wider">THỜI GIAN</span>
        </div>
        <div className="flex items-baseline gap-1">
          <span className="text-lg sm:text-xl font-black text-white font-mono">
            {formatDrivingTime(continuousDrivingMinutes)}
          </span>
        </div>
      </div>
    </div>
  );
};
