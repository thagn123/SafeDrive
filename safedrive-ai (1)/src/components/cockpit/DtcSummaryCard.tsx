import React from 'react';
import { DtcItem } from '../../types/safedrive';
import { CheckCircle2, AlertCircle, ChevronRight, Wrench } from 'lucide-react';

interface DtcSummaryCardProps {
  dtcs: DtcItem[];
  onOpenDiagnostics: () => void;
}

export const DtcSummaryCard: React.FC<DtcSummaryCardProps> = ({
  dtcs,
  onOpenDiagnostics
}) => {
  const hasDtcs = dtcs.length > 0;
  const primaryDtc = dtcs[0];

  return (
    <div 
      onClick={onOpenDiagnostics}
      className={`p-3 rounded-2xl border transition shadow-sm cursor-pointer hover:border-cyan-500/40 shrink-0 group flex items-center justify-between gap-3 h-[52px] sm:h-[56px] ${
        hasDtcs 
          ? 'bg-amber-950/20 border-amber-500/50 hover:bg-amber-950/30' 
          : 'bg-[#132437] border-slate-800'
      }`}
    >
      <div className="flex items-center gap-3 truncate">
        <div className={`p-2 rounded-xl shrink-0 ${
          hasDtcs ? 'bg-amber-500/20 text-amber-400 border border-amber-500/30' : 'bg-emerald-500/15 text-emerald-400 border border-emerald-500/20'
        }`}>
          {hasDtcs ? (
            <AlertCircle size={18} className="animate-pulse" />
          ) : (
            <CheckCircle2 size={18} />
          )}
        </div>

        <div className="truncate">
          <div className="flex items-center gap-2">
            <span className={`text-xs font-bold truncate ${hasDtcs ? 'text-amber-300' : 'text-white'}`}>
              {hasDtcs ? `⚠ ${dtcs.length} lỗi kỹ thuật active` : '✓ Không có lỗi kỹ thuật'}
            </span>
          </div>
          <p className="text-[11px] text-slate-400 font-medium truncate">
            {hasDtcs ? `${primaryDtc.code} · ${primaryDtc.description}` : 'Hệ thống xe hoạt động bình thường'}
          </p>
        </div>
      </div>

      <div className="flex items-center gap-1.5 shrink-0">
        <span className="text-[10px] font-bold text-slate-400 bg-slate-900 px-2 py-1 rounded border border-slate-800 hidden xs:inline">
          Chẩn đoán
        </span>
        <ChevronRight size={16} className="text-slate-400 group-hover:text-white transition" />
      </div>
    </div>
  );
};
