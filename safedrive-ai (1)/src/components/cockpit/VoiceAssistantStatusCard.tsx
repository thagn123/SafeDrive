import React from 'react';
import { VoiceAssistantState } from '../../types/safedrive';
import { Mic, Volume2, Sparkles, Loader2, AlertCircle } from 'lucide-react';

interface VoiceAssistantStatusCardProps {
  voiceState: VoiceAssistantState;
  onTriggerVoice: () => void;
}

export const VoiceAssistantStatusCard: React.FC<VoiceAssistantStatusCardProps> = ({
  voiceState,
  onTriggerVoice
}) => {
  const getVoiceStateConfig = () => {
    switch (voiceState) {
      case 'WAKE_WORD_DETECTED':
        return {
          icon: Sparkles,
          iconColor: 'text-amber-400',
          dotBg: 'bg-amber-400',
          title: 'Đã nhận diện “Hey SafeDrive”',
          sub: 'Đang mở trợ lý thoại...'
        };
      case 'LISTENING':
        return {
          icon: Mic,
          iconColor: 'text-cyan-400 animate-bounce',
          dotBg: 'bg-cyan-400 animate-ping',
          title: 'SafeDrive đang nghe...',
          sub: 'Hãy nói câu lệnh của bạn'
        };
      case 'PROCESSING':
        return {
          icon: Loader2,
          iconColor: 'text-indigo-400 animate-spin',
          dotBg: 'bg-indigo-400',
          title: 'Đang xử lý yêu cầu...',
          sub: 'Vui lòng chờ giây lát'
        };
      case 'SPEAKING':
        return {
          icon: Volume2,
          iconColor: 'text-blue-400 animate-pulse',
          dotBg: 'bg-blue-400 animate-pulse',
          title: 'SafeDrive đang trả lời',
          sub: 'Chạm để dừng đọc'
        };
      case 'DISABLED':
        return {
          icon: Mic,
          iconColor: 'text-slate-500',
          dotBg: 'bg-slate-600',
          title: 'Microphone đang tắt',
          sub: 'Bật nhận diện từ Cài đặt'
        };
      case 'ERROR':
        return {
          icon: AlertCircle,
          iconColor: 'text-red-400',
          dotBg: 'bg-red-500',
          title: 'Không nghe rõ',
          sub: 'Vui lòng thử nói lại'
        };
      case 'IDLE':
      default:
        return {
          icon: Mic,
          iconColor: 'text-cyan-400',
          dotBg: 'bg-emerald-400 animate-pulse',
          title: 'Đang chờ lệnh',
          sub: 'Nói “Hey SafeDrive” để bắt đầu'
        };
    }
  };

  const config = getVoiceStateConfig();
  const IconComponent = config.icon;

  return (
    <div 
      onClick={onTriggerVoice}
      className="p-3 rounded-2xl bg-[#132437] border border-slate-800 hover:border-cyan-500/40 cursor-pointer transition shadow-sm shrink-0 group flex items-center justify-between gap-3 h-[52px] sm:h-[56px]"
    >
      <div className="flex items-center gap-3 truncate">
        <div className="p-2 rounded-xl bg-cyan-500/10 border border-cyan-500/20 text-cyan-400 group-hover:scale-105 transition shrink-0">
          <IconComponent size={18} className={config.iconColor} />
        </div>

        <div className="truncate">
          <div className="flex items-center gap-2">
            <span className={`w-2 h-2 rounded-full shrink-0 ${config.dotBg}`} />
            <span className="text-xs font-bold text-white truncate">{config.title}</span>
          </div>
          <p className="text-[11px] text-slate-400 font-medium truncate">
            {config.sub}
          </p>
        </div>
      </div>

      <div className="flex items-center gap-1 shrink-0">
        <div className="flex items-end gap-0.5 h-4 px-1.5 opacity-60 group-hover:opacity-100 transition">
          <span className="w-1 bg-cyan-400 rounded-full h-2 animate-pulse" />
          <span className="w-1 bg-cyan-400 rounded-full h-4 animate-pulse delay-75" />
          <span className="w-1 bg-cyan-400 rounded-full h-3 animate-pulse delay-150" />
        </div>
      </div>
    </div>
  );
};
