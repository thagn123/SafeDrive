import React from 'react';
import { LucideIcon } from 'lucide-react';

interface VehicleMetricCardProps {
  label: string;
  value: string | number;
  unit?: string;
  icon: LucideIcon;
  subtext?: string;
  isWarning?: boolean;
  colorTheme?: 'cyan' | 'amber' | 'emerald' | 'red';
}

export const VehicleMetricCard: React.FC<VehicleMetricCardProps> = ({
  label,
  value,
  unit,
  icon: Icon,
  subtext,
  isWarning = false,
  colorTheme = 'cyan'
}) => {
  const themeStyles = {
    cyan: 'border-cyan-500/20 hover:border-cyan-500/40 text-cyan-400 bg-cyan-500/10',
    amber: 'border-amber-500/30 hover:border-amber-500/50 text-amber-400 bg-amber-500/10',
    emerald: 'border-emerald-500/20 hover:border-emerald-500/40 text-emerald-400 bg-emerald-500/10',
    red: 'border-red-500/40 hover:border-red-500/60 text-red-400 bg-red-500/15 animate-pulse'
  }[isWarning ? 'red' : colorTheme];

  return (
    <div className={`p-4 sm:p-5 rounded-2xl bg-[#132437] border transition-all shadow-md flex flex-col justify-between ${themeStyles}`}>
      <div className="flex items-center justify-between gap-2 mb-2">
        <span className="text-xs uppercase tracking-wider font-semibold text-slate-400">{label}</span>
        <div className={`p-2 rounded-xl border ${themeStyles}`}>
          <Icon size={20} />
        </div>
      </div>

      <div className="flex items-baseline gap-1 my-1">
        <span className="text-2xl sm:text-3xl font-extrabold text-white tracking-tight">{value}</span>
        {unit && <span className="text-sm font-semibold text-slate-400">{unit}</span>}
      </div>

      {subtext && (
        <p className={`text-xs mt-1 font-medium ${isWarning ? 'text-red-400 font-semibold' : 'text-slate-400'}`}>
          {subtext}
        </p>
      )}
    </div>
  );
};
