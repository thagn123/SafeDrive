import React from 'react';
import { useSafeDrive } from '../context/SafeDriveContext';
import { RiskBadge } from '../components/common/RiskBadge';
import { EmptyState } from '../components/common/EmptyState';
import { Wrench, ShieldCheck, MessageSquareText, AlertCircle, ArrowRight } from 'lucide-react';

export const DiagnosticsScreen: React.FC = () => {
  const { vehicleState, prefillAssistantQuery, setCurrentTab, settings } = useSafeDrive();
  const dtcs = vehicleState.activeDtcs;

  const handleAskAssistant = (code: string, title: string) => {
    const query = `Hãy giải thích mã lỗi ${code} (${title}), mức độ nguy hiểm và tôi nên làm gì?`;
    prefillAssistantQuery(query);
  };

  return (
    <div className="space-y-6 pb-24 animate-fadeIn">
      {/* Header */}
      <header className="p-4 rounded-2xl bg-[#132437] border border-slate-800 shadow-md flex items-center justify-between gap-3">
        <div className="flex items-center gap-3">
          <div className="p-2.5 rounded-xl bg-amber-500/10 border border-amber-500/20 text-amber-400">
            <Wrench size={22} />
          </div>
          <div>
            <h2 className="text-lg font-bold text-white">Chẩn đoán kỹ thuật DTC</h2>
            <p className="text-xs text-slate-400 mt-0.5">
              Hệ thống quét mã lỗi tự động OBD-II / CAN-Bus
            </p>
          </div>
        </div>

        <span className="px-3 py-1 rounded-full bg-[#08131F] border border-slate-700 text-xs font-mono text-cyan-300">
          Lỗi đang hoạt động: {dtcs.length}
        </span>
      </header>

      {/* Main Content */}
      {dtcs.length === 0 ? (
        <EmptyState 
          icon={ShieldCheck}
          title="Không có lỗi kỹ thuật đang hoạt động"
          description="Tất cả các hệ thống cảm biến, truyền động và động cơ đang hoạt động đúng tiêu chuẩn kỹ thuật an toàn."
          action={
            settings.developerMode ? (
              <button
                onClick={() => setCurrentTab('simulator')}
                type="button"
                className="mt-2 px-4 py-2 rounded-xl bg-slate-800 hover:bg-slate-700 text-cyan-300 text-xs font-bold transition flex items-center gap-2"
              >
                <span>Chuyển tới Simulator (Dev Mode)</span>
                <ArrowRight size={14} />
              </button>
            ) : undefined
          }
        />
      ) : (
        <div className="space-y-4">
          {dtcs.map((dtc) => (
            <div 
              key={dtc.code}
              className="p-5 sm:p-6 rounded-2xl bg-[#132437] border border-amber-500/40 shadow-xl space-y-4"
            >
              <div className="flex items-start justify-between gap-3 flex-wrap">
                <div className="space-y-1">
                  <div className="flex items-center gap-2">
                    <span className="text-2xl font-black font-mono text-amber-400 tracking-wider">
                      {dtc.code}
                    </span>
                    <RiskBadge level={dtc.severity} size="sm" />
                  </div>
                  <h3 className="text-lg font-bold text-white">{dtc.title}</h3>
                </div>

                <button
                  onClick={() => handleAskAssistant(dtc.code, dtc.title)}
                  type="button"
                  className="px-4 py-2.5 rounded-xl bg-cyan-500 hover:bg-cyan-400 text-slate-950 font-bold text-xs shadow-lg transition flex items-center gap-2 shrink-0"
                >
                  <MessageSquareText size={16} />
                  <span>Hỏi SafeDrive AI</span>
                </button>
              </div>

              {/* Description */}
              <div className="p-3.5 rounded-xl bg-[#08131F] border border-slate-800 space-y-1.5 text-xs">
                <div className="flex items-center gap-1.5 text-slate-400 font-semibold uppercase tracking-wider text-[10px]">
                  <AlertCircle size={14} className="text-amber-400" />
                  <span>Mô tả kỹ thuật</span>
                </div>
                <p className="text-slate-200 leading-relaxed">{dtc.description}</p>
              </div>

              {/* Recommendation */}
              <div className="p-3.5 rounded-xl bg-emerald-950/40 border border-emerald-500/30 space-y-1.5 text-xs">
                <span className="text-emerald-400 font-bold uppercase tracking-wider text-[10px] block">
                  Khuyến nghị khắc phục:
                </span>
                <p className="text-slate-200 leading-relaxed font-medium">{dtc.recommendation}</p>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
};
