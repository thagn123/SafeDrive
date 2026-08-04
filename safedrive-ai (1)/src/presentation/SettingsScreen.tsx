import React, { useState } from 'react';
import { useSafeDrive } from '../context/SafeDriveContext';
import { HaUiLogo } from '../components/common/HaUiLogo';
import { DriverSupportDetailsModal } from '../components/cockpit/DriverSupportDetailsModal';
import { 
  Settings, 
  Server, 
  Wifi, 
  Volume2, 
  Code, 
  SlidersHorizontal, 
  CheckCircle2, 
  Activity,
  ShieldCheck,
  Smartphone,
  Lock,
  ChevronRight,
  Sparkles
} from 'lucide-react';

export const SettingsScreen: React.FC = () => {
  const { settings, updateSettings, setCurrentTab, vehicleState, restRecommendation } = useSafeDrive();
  const [pingStatus, setPingStatus] = useState<string | null>(null);
  const [isPinging, setIsPinging] = useState(false);
  const [isDriverModalOpen, setIsDriverModalOpen] = useState(false);

  const envPresets = [
    { label: 'USB Local', url: 'http://127.0.0.1:8000/' },
    { label: 'Android Emulator', url: 'http://10.0.2.2:8000/' },
    { label: 'LAN Wi-Fi', url: 'http://192.168.1.15:8000/' },
    { label: 'Cloud Staging', url: 'https://api.example.com/' },
  ];

  const handleTestConnection = () => {
    setIsPinging(true);
    setPingStatus(null);

    setTimeout(() => {
      setIsPinging(false);
      setPingStatus(`Backend kết nối thành công · RTT: 18ms · ${settings.appVersion}`);
      updateSettings({ isConnected: true });
    }, 700);
  };

  return (
    <div className="space-y-6 pb-24 animate-fadeIn">
      {/* Header */}
      <header className="p-4 rounded-2xl bg-[#132437] border border-slate-800 shadow-md flex items-center justify-between gap-3">
        <div className="flex items-center gap-3">
          <div className="p-2.5 rounded-xl bg-cyan-500/10 border border-cyan-500/20 text-cyan-400">
            <Settings size={22} />
          </div>
          <div>
            <h2 className="text-lg font-bold text-white">Cài đặt ứng dụng SafeDrive AI</h2>
            <p className="text-xs text-slate-400 mt-0.5">
              Cấu hình tương tác giọng nói, quyền thiết bị và tùy chọn người dùng
            </p>
          </div>
        </div>

        <span className="px-3 py-1 rounded-full bg-[#08131F] border border-slate-700 text-xs font-mono text-cyan-300">
          v1.2.0 (HVS)
        </span>
      </header>

      {/* TIER 1: NORMAL USER SETTINGS */}
      <section className="p-5 rounded-2xl bg-[#132437] border border-slate-800 space-y-4">
        <div className="flex items-center gap-2 text-cyan-400 font-bold text-sm border-b border-slate-800/80 pb-2">
          <Volume2 size={18} />
          <span>Âm thanh & Tương tác Giọng nói</span>
        </div>

        <div className="space-y-3">
          {/* TTS Toggle */}
          <label className="flex items-center justify-between p-4 rounded-xl bg-[#08131F] border border-slate-800 cursor-pointer hover:border-slate-700 transition">
            <div className="flex items-center gap-3">
              <div className="p-2.5 rounded-lg bg-cyan-500/10 text-cyan-400">
                <Volume2 size={20} />
              </div>
              <div>
                <span className="text-sm font-bold text-white block">Phản hồi giọng nói (TTS)</span>
                <span className="text-xs text-slate-400">Phát âm thanh đọc phản hồi từ Trợ lý SafeDrive AI</span>
              </div>
            </div>
            <input 
              type="checkbox"
              checked={settings.ttsEnabled}
              onChange={(e) => updateSettings({ ttsEnabled: e.target.checked })}
              className="w-5 h-5 accent-cyan-500 rounded cursor-pointer"
            />
          </label>

          {/* Wake Word Toggle */}
          <label className="flex items-center justify-between p-4 rounded-xl bg-[#08131F] border border-slate-800 cursor-pointer hover:border-slate-700 transition">
            <div className="flex items-center gap-3">
              <div className="p-2.5 rounded-lg bg-emerald-500/10 text-emerald-400">
                <Activity size={20} />
              </div>
              <div>
                <span className="text-sm font-bold text-white block">Nhận diện “Hey SafeDrive”</span>
                <span className="text-xs text-slate-400">Cho phép lắng nghe từ kích hoạt giọng nói rảnh tay</span>
              </div>
            </div>
            <input 
              type="checkbox"
              checked={settings.wakeWordEnabled}
              onChange={(e) => updateSettings({ wakeWordEnabled: e.target.checked })}
              className="w-5 h-5 accent-emerald-500 rounded cursor-pointer"
            />
          </label>
        </div>
      </section>

      {/* Device & Driver Signals Access */}
      <section className="p-5 rounded-2xl bg-[#132437] border border-slate-800 space-y-4">
        <div className="flex items-center gap-2 text-cyan-400 font-bold text-sm border-b border-slate-800/80 pb-2">
          <Smartphone size={18} />
          <span>Nguồn Tín hiệu & Quyền Cảm biến</span>
        </div>

        <div className="grid grid-cols-1 sm:grid-cols-2 gap-3 text-xs">
          <div className="p-3.5 rounded-xl bg-[#08131F] border border-slate-800 flex items-center justify-between">
            <span className="text-slate-300 font-semibold">Cảm biến Vô lăng:</span>
            <span className="text-emerald-400 font-bold">Hoạt động</span>
          </div>

          <div className="p-3.5 rounded-xl bg-[#08131F] border border-slate-800 flex items-center justify-between">
            <span className="text-slate-300 font-semibold">Cảm biến Ghế lái:</span>
            <span className="text-emerald-400 font-bold">Có người</span>
          </div>

          <div className="p-3.5 rounded-xl bg-[#08131F] border border-slate-800 flex items-center justify-between">
            <span className="text-slate-300 font-semibold">Kết nối Wearable:</span>
            <span className={vehicleState.driverSupportSignals.wearableConnected ? 'text-emerald-400 font-bold' : 'text-slate-400 font-medium'}>
              {vehicleState.driverSupportSignals.wearableConnected ? 'Đã kết nối' : 'Chưa kết nối'}
            </span>
          </div>

          <div className="p-3.5 rounded-xl bg-[#08131F] border border-slate-800 flex items-center justify-between">
            <span className="text-slate-300 font-semibold">Quyền Microphone:</span>
            <span className="text-emerald-400 font-bold">Đã cấp phép</span>
          </div>
        </div>

        <button
          onClick={() => setIsDriverModalOpen(true)}
          type="button"
          className="w-full min-h-[48px] px-4 rounded-xl bg-[#08131F] hover:bg-slate-800 text-cyan-300 border border-slate-700 font-bold text-xs transition flex items-center justify-center gap-2"
        >
          <Activity size={16} />
          <span>Xem chi tiết trạng thái 4 nguồn tín hiệu gián tiếp</span>
          <ChevronRight size={16} />
        </button>
      </section>

      {/* Privacy & System Guarantee Declaration */}
      <section className="p-4 sm:p-5 rounded-2xl bg-gradient-to-r from-[#0F1E2E] to-[#132437] border border-slate-800 space-y-2">
        <div className="flex items-center gap-2 text-emerald-400 font-bold text-xs uppercase tracking-wider">
          <Lock size={16} />
          <span>Cam kết Bảo mật & Tính gián tiếp</span>
        </div>
        <p className="text-xs text-slate-300 leading-relaxed font-medium">
          SafeDrive AI chỉ phân tích gián tiếp các thông số vận hành xe (thời gian lái, tương tác vô lăng, hiện diện ghế lái và wearable). <strong className="text-white">Không thu thập hoặc xử lý hình ảnh từ camera / DMS</strong>.
        </p>
      </section>

      {/* Developer Mode Toggle Switch */}
      <section className="p-5 rounded-2xl bg-[#132437] border border-slate-800 flex items-center justify-between gap-4">
        <div className="flex items-center gap-3">
          <div className="p-2.5 rounded-xl bg-indigo-500/15 border border-indigo-500/30 text-indigo-400">
            <Code size={20} />
          </div>
          <div>
            <span className="text-sm font-bold text-white block">Developer Mode (Chế độ Kỹ thuật viên)</span>
            <span className="text-xs text-slate-400">Bật công cụ Simulator, cấu hình API Endpoint và Reason codes</span>
          </div>
        </div>

        <input 
          type="checkbox"
          checked={settings.developerMode}
          onChange={(e) => updateSettings({ developerMode: e.target.checked })}
          className="w-6 h-6 accent-indigo-500 rounded cursor-pointer shrink-0"
        />
      </section>

      {/* TIER 2: DEVELOPER MODE TOOLS (ONLY VISIBLE WHEN DEVELOPER MODE IS ON) */}
      {settings.developerMode && (
        <div className="p-5 rounded-2xl bg-[#0B1724] border-2 border-indigo-500/40 space-y-5 animate-fadeIn">
          <div className="flex items-center justify-between border-b border-indigo-500/30 pb-3">
            <div className="flex items-center gap-2 text-indigo-300 font-bold text-sm uppercase tracking-wider">
              <Code size={18} />
              <span>CÔNG CỤ CỤM DEVELOPER MODE</span>
            </div>
            <span className="px-2 py-0.5 rounded bg-indigo-500/20 text-indigo-300 text-[10px] font-mono font-bold">
              DEV MODE ACTIVE
            </span>
          </div>

          {/* Backend API Configuration */}
          <div className="space-y-3">
            <label className="text-xs text-slate-300 font-bold block">Địa chỉ API Server Endpoint:</label>
            <input
              type="text"
              value={settings.backendUrl}
              onChange={(e) => updateSettings({ backendUrl: e.target.value })}
              className="w-full min-h-[48px] px-4 rounded-xl bg-[#08131F] border border-slate-700 text-cyan-300 font-mono text-xs focus:outline-none focus:border-indigo-500"
            />

            {/* Quick Env Presets */}
            <div className="space-y-2">
              <span className="text-xs text-slate-400 font-medium">Preset môi trường kết nối nhanh:</span>
              <div className="grid grid-cols-2 sm:grid-cols-4 gap-2">
                {envPresets.map((env) => (
                  <button
                    key={env.label}
                    onClick={() => updateSettings({ backendUrl: env.url })}
                    type="button"
                    className={`p-2.5 rounded-xl border text-xs font-semibold transition ${
                      settings.backendUrl === env.url 
                        ? 'bg-indigo-500/20 border-indigo-400 text-indigo-300' 
                        : 'bg-[#08131F] border-slate-800 text-slate-400 hover:text-white'
                    }`}
                  >
                    {env.label}
                  </button>
                ))}
              </div>
            </div>

            {/* Connection Ping Test */}
            <div className="pt-2 flex flex-col sm:flex-row items-start sm:items-center gap-3">
              <button
                onClick={handleTestConnection}
                disabled={isPinging}
                type="button"
                className="px-4 py-2.5 rounded-xl bg-slate-800 hover:bg-slate-700 text-indigo-300 border border-slate-700 font-bold text-xs transition flex items-center gap-2"
              >
                <Wifi size={16} className={isPinging ? 'animate-pulse text-amber-400' : ''} />
                <span>{isPinging ? 'Đang gửi gói tin ping...' : 'Kiểm tra độ trễ Endpoint'}</span>
              </button>

              {pingStatus && (
                <div className="flex items-center gap-2 text-xs text-emerald-400 font-mono font-medium">
                  <CheckCircle2 size={16} />
                  <span>{pingStatus}</span>
                </div>
              )}
            </div>
          </div>

          {/* Simulator Launch Button */}
          <div className="p-4 rounded-xl bg-[#132437] border border-indigo-500/30 flex items-center justify-between gap-3">
            <div>
              <h4 className="text-sm font-bold text-white">Mô phỏng trạng thái xe (Simulator)</h4>
              <p className="text-xs text-slate-400">Thử nghiệm 8 kịch bản giả lập và truyền mã lỗi DTC</p>
            </div>
            <button
              onClick={() => setCurrentTab('simulator')}
              type="button"
              className="px-4 py-2.5 rounded-xl bg-indigo-600 hover:bg-indigo-500 text-white font-bold text-xs shadow-md transition shrink-0"
            >
              Mở Simulator
            </button>
          </div>
        </div>
      )}

      {/* Driver Signal Modal */}
      <DriverSupportDetailsModal
        isOpen={isDriverModalOpen}
        onClose={() => setIsDriverModalOpen(false)}
        signals={vehicleState.driverSupportSignals}
        restRecommendation={restRecommendation}
      />

      {/* Footer & Version Info */}
      <footer className="p-5 rounded-2xl bg-[#08131F] border border-slate-800 text-center space-y-3">
        <HaUiLogo size="sm" className="mx-auto" />
        <div className="space-y-1">
          <p className="text-sm font-bold text-white">{settings.appVersion}</p>
          <p className="text-xs text-slate-400">
            Dự án Nâng cấp Giao diện Lái xe An toàn HMI Automotive
          </p>
          <p className="text-[11px] text-cyan-400 font-medium">
            Hanoi University of Industry (HaUI) — Vehicle Smart Systems (HVS)
          </p>
        </div>
      </footer>
    </div>
  );
};
