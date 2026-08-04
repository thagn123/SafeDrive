import React from 'react';
import { DtcItem } from '../../types/safedrive';
import { AlertCircle, WifiOff, ChevronRight, CheckCircle2 } from 'lucide-react';

interface PriorityAlertProps {
  dtcs: DtcItem[];
  isConnected: boolean;
  onOpenDiagnostics: () => void;
}

export const PriorityAlert: React.FC<PriorityAlertProps> = ({
  dtcs,
  isConnected,
  onOpenDiagnostics
}) => {
  if (!isConnected) {
    return (
      <div 
        className="h-[48px] px-3.5 rounded-xl bg-slate-900/90 border border-slate-700/80 flex items-center justify-between text-xs text-slate-300 shrink-0 shadow-sm"
      >
        <div className="flex items-center gap-2 truncate">
          <WifiOff size={16} className="text-amber-400 shrink-0" />
          <span className="font-semibold truncate">Ngoại tuyến: Dữ liệu gần nhất đang hiển thị</span>
        </div>
      </div>
    );
  }

  if (dtcs.length > 0) {
    const primaryDtc = dtcs[0];
    return (
      <div 
        onClick={onOpenDiagnostics}
        className="h-[48px] px-3.5 rounded-xl bg-red-950/40 border border-red-500/50 hover:border-red-400 flex items-center justify-between text-xs text-red-200 cursor-pointer shrink-0 shadow-sm transition group"
      >
        <div className="flex items-center gap-2 truncate">
          <AlertCircle size={16} className="text-red-400 shrink-0 animate-pulse" />
          <span className="font-bold tracking-wide truncate">
            {primaryDtc.code} đang hoạt động
          </span>
          <span className="text-slate-400 text-[11px] hidden sm:inline">— Chạm để xem chẩn đoán</span>
        </div>
        <div className="flex items-center gap-1 text-[11px] text-red-300 font-semibold shrink-0">
          <span>Chẩn đoán</span>
          <ChevronRight size={14} className="group-hover:translate-x-0.5 transition" />
        </div>
      </div>
    );
  }

  // Normal nominal status - show tiny non-intrusive status line or null
  return (
    <div className="h-[36px] px-3.5 rounded-xl bg-[#132437]/60 border border-slate-800/80 flex items-center justify-between text-[11px] text-slate-400 shrink-0">
      <div className="flex items-center gap-1.5">
        <CheckCircle2 size={14} className="text-emerald-400 shrink-0" />
        <span>Không có lỗi kỹ thuật (DTC)</span>
      </div>
      <span className="font-mono text-[10px] text-slate-500">Hệ thống an toàn OK</span>
    </div>
  );
};
