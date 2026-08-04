import React, { useState, useEffect } from 'react';
import { useSafeDrive } from '../../context/SafeDriveContext';
import { Siren, ShieldAlert, CheckCircle2, PhoneOff, Mic, Volume2, MapPin } from 'lucide-react';
import { EmergencyState } from '../../types/safedrive';

export const EmergencyOverlay: React.FC = () => {
  const { 
    emergencyState, 
    emergencyDeadlineMs, 
    cancelEmergency, 
    processEmergencyVoice,
    settings,
    vehicleState
  } = useSafeDrive();

  const [now, setNow] = useState<number>(Date.now());

  useEffect(() => {
    if (emergencyState === 'IDLE' || emergencyState === 'CANCELLED') return;

    const interval = setInterval(() => {
      setNow(Date.now());
    }, 250);

    return () => clearInterval(interval);
  }, [emergencyState]);

  if (emergencyState === 'IDLE' || emergencyState === 'CANCELLED') {
    return null;
  }

  const remainingMs = emergencyDeadlineMs ? Math.max(0, emergencyDeadlineMs - now) : 0;
  const remainingSeconds = Math.ceil(remainingMs / 1000);

  const friendlyEvidenceList = [
    { label: 'Tác động va chạm', value: 'Phát hiện tác động gia tốc mạnh' },
    { label: 'Trạng thái di chuyển', value: 'Xe dừng lại sau tác động' },
    { label: 'Phản hồi người lái', value: 'Không nhận được phản hồi' },
    { label: 'Cảm biến ghế lái', value: 'Ghi nhận có người ở ghế lái' },
  ];

  return (
    <div 
      aria-live="assertive"
      aria-modal="true"
      role="dialog"
      aria-labelledby="emergency-modal-title"
      className="fixed inset-0 z-50 flex flex-col justify-between bg-[#08131F]/98 backdrop-blur-2xl p-4 sm:p-6 md:p-8 text-white select-none overflow-y-auto animate-fadeIn"
    >
      {/* Top Banner Disclaimer */}
      <div className="flex items-center justify-between gap-3 border-b border-red-500/40 pb-3">
        <div className="inline-flex items-center gap-2 px-3 py-1.5 rounded-full bg-red-500/20 text-red-300 border border-red-500/50 text-xs font-bold uppercase tracking-widest">
          <PhoneOff size={16} />
          <span>SOS MÔ PHỎNG (PROTOTYPE)</span>
        </div>
        <span className="text-[11px] text-slate-400 font-mono">
          real_emergency_dispatch_enabled: false
        </span>
      </div>

      {/* Main Content Area based on State */}
      <div className="max-w-2xl mx-auto w-full my-auto space-y-6 text-center py-4">
        {/* Pulsing Siren Icon */}
        <div className="relative inline-flex items-center justify-center">
          <div className="w-20 h-20 sm:w-24 sm:h-24 rounded-full bg-red-500/20 border-2 border-red-500/60 flex items-center justify-center text-red-400 animate-pulse">
            <Siren size={44} />
          </div>
          <span className="absolute inset-0 rounded-full bg-red-500/10 animate-ping pointer-events-none" />
        </div>

        {/* STATE 1: VERIFYING_EVIDENCE (5s) */}
        {emergencyState === 'VERIFYING_EVIDENCE' && (
          <div className="space-y-4 animate-fadeIn">
            <span className="px-3 py-1 rounded-md bg-amber-500/20 text-amber-300 border border-amber-500/40 text-xs font-bold uppercase tracking-wider">
              BƯỚC 1/3: XÁC MINH BẰNG CHỨNG CẢM BIẾN
            </span>
            <h2 id="emergency-modal-title" className="text-2xl sm:text-3xl font-black tracking-tight text-white">
              Đang xác minh tình huống khẩn cấp...
            </h2>
            <p className="text-sm text-slate-300 max-w-lg mx-auto leading-relaxed font-medium">
              Hệ thống đang đối chiếu dữ liệu cảm biến gia tốc, trạng thái dừng xe và cảm biến hiện diện ghế lái.
            </p>

            {/* Countdown Badge */}
            <div className="inline-flex items-center justify-center px-6 py-3 rounded-2xl bg-amber-950/60 border border-amber-500/50 text-amber-300 font-mono text-xl font-bold">
              Thời gian xác minh: {remainingSeconds}s
            </div>
          </div>
        )}

        {/* STATE 2: AWAITING_USER_RESPONSE (15s) */}
        {emergencyState === 'AWAITING_USER_RESPONSE' && (
          <div className="space-y-5 animate-fadeIn">
            <span className="px-3 py-1 rounded-md bg-orange-500/20 text-orange-300 border border-orange-500/40 text-xs font-bold uppercase tracking-wider">
              BƯỚC 2/3: CHỜ XÁC NHẬN TỪ NGƯỜI LÁI
            </span>
            <h2 id="emergency-modal-title" className="text-3xl sm:text-4xl font-black tracking-tight text-white">
              Bạn có ổn không?
            </h2>
            <p className="text-base text-slate-200 max-w-xl mx-auto leading-relaxed font-medium">
              Hệ thống đang xác minh tình huống. Nếu bạn vẫn ổn, hãy nói <strong className="text-cyan-300">“Tôi ổn”</strong> hoặc nhấn nút bên dưới.
            </p>

            {/* Timer circle */}
            <div className="inline-flex flex-col items-center justify-center w-28 h-28 sm:w-32 sm:h-32 rounded-full border-4 border-orange-500 bg-orange-950/60 text-white shadow-xl shadow-orange-500/20">
              <span className="text-3xl sm:text-4xl font-black font-mono">{remainingSeconds}s</span>
              <span className="text-[10px] text-slate-300 font-semibold uppercase">Thời gian chờ</span>
            </div>

            {/* Main Action Button */}
            <div>
              <button
                onClick={cancelEmergency}
                type="button"
                className="w-full min-h-[56px] px-6 rounded-2xl bg-emerald-500 hover:bg-emerald-400 text-slate-950 font-black text-lg shadow-xl shadow-emerald-500/30 transition flex items-center justify-center gap-3 active:scale-98"
              >
                <CheckCircle2 size={26} />
                <span>TÔI VẪN ỔN — HỦY SOS</span>
              </button>
            </div>
          </div>
        )}

        {/* STATE 3: FINAL_COUNTDOWN (10s) */}
        {emergencyState === 'FINAL_COUNTDOWN' && (
          <div className="space-y-5 animate-fadeIn">
            <span className="px-3 py-1 rounded-md bg-red-500/20 text-red-300 border border-red-500/50 text-xs font-bold uppercase tracking-wider animate-pulse">
              BƯỚC 3/3: ĐẾM NGƯỢC GỬI TÍN HIỆU SOS MÔ PHỎNG
            </span>
            <h2 id="emergency-modal-title" className="text-2xl sm:text-3xl font-black tracking-tight text-white">
              SOS mô phỏng sẽ được gửi sau {remainingSeconds} giây
            </h2>
            <p className="text-sm text-red-200 max-w-lg mx-auto leading-relaxed font-medium">
              Không nhận được phản hồi. Hệ thống chuẩn bị phát tín hiệu cứu hộ giả lập theo quy trình tự động.
            </p>

            {/* Big Red Timer */}
            <div className="inline-flex items-center justify-center w-32 h-32 sm:w-36 sm:h-36 rounded-full border-4 border-red-500 bg-red-950 text-white text-5xl sm:text-6xl font-black font-mono shadow-2xl shadow-red-500/50 animate-pulse">
              {remainingSeconds}s
            </div>

            {/* Main Cancel Button */}
            <div>
              <button
                onClick={cancelEmergency}
                type="button"
                className="w-full min-h-[56px] px-6 rounded-2xl bg-slate-800 hover:bg-slate-700 text-white border-2 border-emerald-500/80 font-black text-lg shadow-xl transition flex items-center justify-center gap-3"
              >
                <CheckCircle2 size={26} className="text-emerald-400" />
                <span>HỦY SOS — TÔI VẪN ỔN</span>
              </button>
            </div>
          </div>
        )}

        {/* STATE 4: SOS_SIMULATED_SENT */}
        {emergencyState === 'SOS_SIMULATED_SENT' && (
          <div className="p-6 sm:p-8 rounded-3xl bg-emerald-950/70 border-2 border-emerald-500/80 space-y-5 animate-fadeIn text-center">
            <div className="w-16 h-16 mx-auto rounded-full bg-emerald-500/20 border border-emerald-500/50 flex items-center justify-center text-emerald-400">
              <CheckCircle2 size={40} />
            </div>
            <h2 id="emergency-modal-title" className="text-2xl sm:text-3xl font-black text-white">
              Đã gửi tín hiệu SOS mô phỏng khẩn cấp
            </h2>
            <p className="text-sm text-slate-200 leading-relaxed font-medium max-w-md mx-auto">
              Tọa độ giả lập (<strong className="text-cyan-300 font-mono">21.0285, 105.8542</strong>) và bằng chứng sự cố đã được đóng gói trong gói tin thử nghiệm SafeDrive AI.
            </p>
            <button
              onClick={cancelEmergency}
              type="button"
              className="w-full min-h-[52px] px-6 rounded-2xl bg-slate-800 hover:bg-slate-700 text-white font-bold text-base transition border border-slate-700"
            >
              Quay lại Cockpit
            </button>
          </div>
        )}

        {/* Evidence Card (User Friendly Language) */}
        {emergencyState !== 'SOS_SIMULATED_SENT' && (
          <div className="p-4 rounded-2xl bg-[#0F1D2C] border border-slate-800 text-left space-y-2.5">
            <div className="flex items-center justify-between text-xs text-slate-400 font-semibold uppercase tracking-wider">
              <span>Bằng chứng xác minh gián tiếp:</span>
              <span className="text-cyan-400 font-mono">4/4 Tín hiệu</span>
            </div>

            <div className="grid grid-cols-1 sm:grid-cols-2 gap-2 text-xs">
              {friendlyEvidenceList.map((item, idx) => (
                <div key={idx} className="p-2.5 rounded-xl bg-[#08131F] border border-slate-800 flex items-center justify-between">
                  <span className="text-slate-400 font-medium">{item.label}:</span>
                  <span className="text-slate-200 font-bold">{item.value}</span>
                </div>
              ))}
            </div>

            {/* Developer reason codes option */}
            {settings.developerMode && (
              <div className="pt-2 border-t border-slate-800 text-[11px] font-mono text-cyan-400">
                <span>Reason codes: crash_detected, passenger_no_response, seat_occupied</span>
              </div>
            )}
          </div>
        )}

        {/* Quick Voice Simulation Buttons */}
        {emergencyState !== 'SOS_SIMULATED_SENT' && (
          <div className="pt-2 space-y-2">
            <span className="text-xs text-slate-400 font-medium block">
              Thử nghiệm câu lệnh giọng nói (Phản hồi nhanh):
            </span>
            <div className="flex items-center justify-center gap-2 flex-wrap">
              <button
                onClick={() => processEmergencyVoice('Tôi vẫn ổn')}
                type="button"
                className="px-4 py-2 rounded-xl bg-[#0F1D2C] hover:bg-slate-800 text-cyan-300 border border-slate-700 text-xs font-bold transition flex items-center gap-1.5"
              >
                <Mic size={14} />
                <span>Nói “Tôi ổn”</span>
              </button>
              <button
                onClick={() => processEmergencyVoice('Hủy SOS')}
                type="button"
                className="px-4 py-2 rounded-xl bg-[#0F1D2C] hover:bg-slate-800 text-cyan-300 border border-slate-700 text-xs font-bold transition flex items-center gap-1.5"
              >
                <Mic size={14} />
                <span>Nói “Hủy SOS”</span>
              </button>
              <button
                onClick={() => processEmergencyVoice('Không cần hỗ trợ')}
                type="button"
                className="px-4 py-2 rounded-xl bg-[#0F1D2C] hover:bg-slate-800 text-cyan-300 border border-slate-700 text-xs font-bold transition flex items-center gap-1.5"
              >
                <Mic size={14} />
                <span>Nói “Không cần hỗ trợ”</span>
              </button>
            </div>
          </div>
        )}
      </div>

      {/* Footer */}
      <div className="text-center text-[11px] text-slate-500 pt-3 border-t border-slate-800/80">
        SafeDrive AI Automotive HMI — HaUI Vehicle Smart Systems (HVS)
      </div>
    </div>
  );
};
