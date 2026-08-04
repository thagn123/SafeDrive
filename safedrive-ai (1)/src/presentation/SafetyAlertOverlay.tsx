import React from 'react';
import { useSafeDrive } from '../context/SafeDriveContext';
import { RiskBadge } from '../components/common/RiskBadge';
import { AlertTriangle, Siren, CheckCircle, MessageSquareText, ShieldAlert, X } from 'lucide-react';

export const SafetyAlertOverlay: React.FC = () => {
  const { 
    riskAssessment, 
    isSafetyAlertVisible, 
    dismissSafetyAlert, 
    setCurrentTab,
    openSosModal,
    settings
  } = useSafeDrive();

  if (!isSafetyAlertVisible) return null;

  const isCritical = riskAssessment.level === 'CRITICAL';

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-slate-950/90 backdrop-blur-md animate-fadeIn">
      <div className={`w-full max-w-lg rounded-3xl border-2 p-6 sm:p-8 shadow-2xl text-slate-100 space-y-6 ${
        isCritical 
          ? 'bg-gradient-to-br from-[#2A121D] via-[#1E1120] to-[#08131F] border-red-500/80 shadow-red-500/20' 
          : 'bg-gradient-to-br from-[#271E14] via-[#1A2E44] to-[#08131F] border-orange-500/80 shadow-orange-500/20'
      }`}>
        {/* Header */}
        <div className="flex items-start justify-between gap-3">
          <div className="flex items-center gap-3">
            <div className={`p-3.5 rounded-2xl border ${
              isCritical 
                ? 'bg-red-500/20 border-red-500/50 text-red-400' 
                : 'bg-orange-500/20 border-orange-500/50 text-orange-400'
            }`}>
              {isCritical ? <Siren size={32} className="animate-pulse" /> : <AlertTriangle size={32} />}
            </div>
            <div>
              <span className="text-xs font-black tracking-widest uppercase text-orange-400 block">
                CẢNH BÁO AN TOÀN
              </span>
              <RiskBadge level={riskAssessment.level} size="md" className="mt-1" />
            </div>
          </div>

          <button
            onClick={dismissSafetyAlert}
            type="button"
            className="p-2 rounded-xl text-slate-400 hover:text-white hover:bg-slate-800 transition"
            aria-label="Đóng"
          >
            <X size={22} />
          </button>
        </div>

        {/* Message */}
        <div className="space-y-2">
          <h2 className="text-2xl font-extrabold text-white tracking-tight">
            {riskAssessment.title}
          </h2>
          <p className="text-sm sm:text-base text-slate-200 leading-relaxed font-medium">
            {riskAssessment.message}
          </p>
        </div>

        {/* Reason Codes (Developer mode or debug) */}
        {settings.developerMode && riskAssessment.reasonCodes.length > 0 && (
          <div className="p-3 rounded-xl bg-[#08131F]/80 border border-slate-800 text-xs font-mono text-slate-300 flex items-center gap-2 flex-wrap">
            <span className="text-cyan-400 font-bold">Reason:</span>
            {riskAssessment.reasonCodes.map((c, i) => (
              <span key={i} className="bg-slate-800 px-2 py-0.5 rounded text-cyan-300">
                {c}
              </span>
            ))}
          </div>
        )}

        {/* Contextual Action CTAs */}
        <div className="space-y-3 pt-2">
          {isCritical ? (
            <button
              onClick={() => {
                dismissSafetyAlert();
                openSosModal();
                setCurrentTab('sos');
              }}
              type="button"
              className="w-full min-h-[52px] px-5 rounded-2xl bg-red-600 hover:bg-red-500 text-white font-black text-base shadow-xl shadow-red-600/30 transition flex items-center justify-center gap-2"
            >
              <Siren size={22} />
              <span>Mở SOS Mô phỏng Khẩn cấp</span>
            </button>
          ) : (
            <button
              onClick={() => {
                dismissSafetyAlert();
                setCurrentTab('assistant');
              }}
              type="button"
              className="w-full min-h-[52px] px-5 rounded-2xl bg-orange-500 hover:bg-orange-400 text-slate-950 font-extrabold text-base shadow-xl shadow-orange-500/20 transition flex items-center justify-center gap-2"
            >
              <MessageSquareText size={22} />
              <span>Hỏi Trợ lý SafeDrive AI</span>
            </button>
          )}

          <button
            onClick={dismissSafetyAlert}
            type="button"
            className="w-full min-h-[48px] px-5 rounded-2xl bg-slate-800 hover:bg-slate-700 text-slate-200 font-bold text-sm transition flex items-center justify-center gap-2 border border-slate-700"
          >
            <CheckCircle size={18} className="text-emerald-400" />
            <span>Tôi vẫn ổn / Đã hiểu</span>
          </button>
        </div>
      </div>
    </div>
  );
};
