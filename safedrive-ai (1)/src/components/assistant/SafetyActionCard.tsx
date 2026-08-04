import React from 'react';
import { SafeDriveAction } from '../../types/safedrive';
import { ShieldAlert, ArrowRight, AlertTriangle } from 'lucide-react';

interface SafetyActionCardProps {
  action: SafeDriveAction;
  onExecute: (action: SafeDriveAction) => void;
}

export const SafetyActionCard: React.FC<SafetyActionCardProps> = ({
  action,
  onExecute
}) => {
  return (
    <div className="mt-3 p-3.5 rounded-xl bg-[#08131F] border border-cyan-500/30 flex items-center justify-between gap-3 shadow-md">
      <div className="flex items-center gap-2.5">
        <div className="p-2 rounded-lg bg-cyan-500/10 text-cyan-400 border border-cyan-500/20 shrink-0">
          <ShieldAlert size={18} />
        </div>
        <div>
          <span className="text-[10px] font-bold uppercase tracking-wider text-cyan-400 block">Đề xuất hành động</span>
          <p className="text-xs sm:text-sm font-bold text-white mt-0.5">{action.title}</p>
        </div>
      </div>

      <button
        onClick={() => onExecute(action)}
        type="button"
        className="px-3.5 py-2 rounded-lg bg-cyan-500 hover:bg-cyan-400 text-slate-950 font-bold text-xs shadow-md transition flex items-center gap-1.5 shrink-0"
      >
        <span>Thực thi</span>
        {action.requiresConfirmation ? <AlertTriangle size={14} className="text-slate-900" /> : <ArrowRight size={14} />}
      </button>
    </div>
  );
};
