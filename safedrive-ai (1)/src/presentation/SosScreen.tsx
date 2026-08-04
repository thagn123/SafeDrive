import React, { useEffect } from 'react';
import { useSafeDrive } from '../context/SafeDriveContext';
import { Siren, PhoneOff, CheckCircle2 } from 'lucide-react';

export const SosScreen: React.FC = () => {
  const { startEmergencyFlow, emergencyState, cancelEmergency, setCurrentTab } = useSafeDrive();

  useEffect(() => {
    if (emergencyState === 'IDLE' || emergencyState === 'CANCELLED') {
      startEmergencyFlow();
    }
  }, [emergencyState, startEmergencyFlow]);

  return (
    <div className="p-6 rounded-3xl bg-[#0F1D2C] border border-slate-800 text-center space-y-4 max-w-lg mx-auto my-12 animate-fadeIn">
      <div className="w-16 h-16 mx-auto rounded-full bg-red-500/20 border border-red-500/40 flex items-center justify-center text-red-400 animate-pulse">
        <Siren size={36} />
      </div>

      <h2 className="text-2xl font-black text-white">Chế độ SOS Mô phỏng Khẩn cấp</h2>
      <p className="text-xs text-slate-300 leading-relaxed">
        Giao diện đếm ngược cấp cứu khẩn cấp đang được hiển thị ở màn hình chính. Vui lòng theo dõi các bước xác minh bằng chứng và nút hủy SOS.
      </p>

      <div className="pt-2">
        <button
          onClick={() => {
            cancelEmergency();
            setCurrentTab('cockpit');
          }}
          type="button"
          className="w-full min-h-[48px] px-4 rounded-xl bg-slate-800 hover:bg-slate-700 text-white font-bold text-xs transition border border-slate-700"
        >
          Quay lại Cockpit
        </button>
      </div>
    </div>
  );
};
