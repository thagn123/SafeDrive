import React, { useState, useEffect } from 'react';
import { Siren, MapPin, CheckCircle, XCircle, PhoneOff } from 'lucide-react';

interface SosCountdownCardProps {
  onConfirm: () => void;
  onCancel: () => void;
  isConfirmed: boolean;
}

export const SosCountdownCard: React.FC<SosCountdownCardProps> = ({
  onConfirm,
  onCancel,
  isConfirmed
}) => {
  const [seconds, setSeconds] = useState(10);
  const [isCounting, setIsCounting] = useState(true);

  useEffect(() => {
    if (!isCounting || isConfirmed) return;

    if (seconds <= 0) {
      setIsCounting(false);
      onConfirm();
      return;
    }

    const timer = setInterval(() => {
      setSeconds(prev => prev - 1);
    }, 1000);

    return () => clearInterval(timer);
  }, [seconds, isCounting, isConfirmed, onConfirm]);

  return (
    <div className="w-full max-w-xl mx-auto p-6 sm:p-8 rounded-3xl bg-gradient-to-br from-[#1E1120] via-[#2A121D] to-[#08131F] border-2 border-red-500/80 shadow-2xl text-center space-y-6">
      <div className="inline-flex items-center gap-2 px-3 py-1 rounded-full bg-red-500/20 text-red-400 border border-red-500/40 text-xs font-bold uppercase tracking-widest">
        <PhoneOff size={14} />
        <span>Chế độ mô phỏng thử nghiệm</span>
      </div>

      <div className="space-y-2">
        <div className="w-16 h-16 mx-auto rounded-full bg-red-500/20 border border-red-500/50 flex items-center justify-center text-red-400 animate-pulse">
          <Siren size={36} />
        </div>
        <h2 className="text-2xl sm:text-3xl font-black text-white tracking-tight">
          SOS MÔ PHỎNG KHẨN CẤP
        </h2>
        <p className="text-xs sm:text-sm text-red-300 font-medium">
          Tuyệt đối không thực hiện cuộc gọi hay gửi tin nhắn khẩn cấp thật.
        </p>
      </div>

      {!isConfirmed ? (
        <>
          <div className="p-4 rounded-2xl bg-[#08131F]/90 border border-red-500/30 text-left space-y-3">
            <div className="flex items-center justify-between text-xs text-slate-300">
              <span className="font-semibold text-slate-400">Lý do kích hoạt:</span>
              <span className="font-bold text-red-400">Va chạm + Không phản hồi</span>
            </div>

            <div className="flex items-center gap-2 text-xs text-slate-300">
              <MapPin size={16} className="text-cyan-400 shrink-0" />
              <span>Tọa độ mô phỏng: <strong className="text-white font-mono">21.0285, 105.8542</strong> (HaUI Autonomous Vehicle Lab)</span>
            </div>
          </div>

          {/* Big Countdown Timer */}
          <div className="py-2">
            <div className="inline-flex items-center justify-center w-28 h-28 sm:w-32 sm:h-32 rounded-full border-4 border-red-500 bg-red-950/50 text-white font-black text-4xl sm:text-5xl shadow-lg shadow-red-500/30 animate-pulse">
              {seconds}s
            </div>
            <p className="text-xs text-slate-400 mt-2 font-medium">
              Tự động gửi tín hiệu cứu hộ giả lập khi hết thời gian đếm ngược
            </p>
          </div>

          {/* Action Buttons */}
          <div className="flex items-center gap-4 pt-2">
            <button
              onClick={onCancel}
              type="button"
              className="flex-1 min-h-[52px] px-4 rounded-2xl border border-slate-700 bg-slate-800/90 hover:bg-slate-700 text-slate-200 font-bold text-sm sm:text-base transition flex items-center justify-center gap-2"
            >
              <XCircle size={20} />
              <span>Hủy SOS</span>
            </button>

            <button
              onClick={onConfirm}
              type="button"
              className="flex-1 min-h-[52px] px-4 rounded-2xl bg-red-600 hover:bg-red-500 text-white font-black text-sm sm:text-base shadow-xl shadow-red-600/30 transition flex items-center justify-center gap-2"
            >
              <CheckCircle size={20} />
              <span>Xác nhận ngay</span>
            </button>
          </div>
        </>
      ) : (
        <div className="p-6 rounded-2xl bg-emerald-950/60 border border-emerald-500/50 space-y-4 animate-fadeIn">
          <div className="p-3 rounded-full bg-emerald-500/20 text-emerald-400 w-12 h-12 mx-auto flex items-center justify-center">
            <CheckCircle size={28} />
          </div>
          <h3 className="text-xl font-bold text-white">Đã gửi yêu cầu SOS mô phỏng</h3>
          <p className="text-sm text-slate-300 leading-relaxed">
            Hệ thống SafeDrive AI đã ghi nhận tọa độ cứu hộ giả lập và hoàn tất mô phỏng quy trình phản ứng khẩn cấp.
          </p>
          <button
            onClick={onCancel}
            type="button"
            className="w-full min-h-[48px] px-4 rounded-xl bg-slate-800 hover:bg-slate-700 text-slate-200 font-bold text-sm transition"
          >
            Quay lại Cockpit
          </button>
        </div>
      )}
    </div>
  );
};
