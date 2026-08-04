import React from 'react';
import { VoiceAssistantState } from '../../types/safedrive';
import { Mic, Volume2, Sparkles, Loader2 } from 'lucide-react';

interface CompactVoiceStatusProps {
  voiceState: VoiceAssistantState;
  onTriggerVoice: () => void;
}

export const CompactVoiceStatus: React.FC<CompactVoiceStatusProps> = ({
  voiceState,
  onTriggerVoice
}) => {
  const getVoiceStateConfig = () => {
    switch (voiceState) {
      case 'WAKE_WORD_DETECTED':
        return {
          icon: Sparkles,
          iconColor: 'text-amber-400',
          title: 'Đã nhận diện “Hey SafeDrive”',
          sub: 'Chờ nhận câu hỏi...'
        };
      case 'LISTENING':
        return {
          icon: Mic,
          iconColor: 'text-cyan-400 animate-bounce',
          title: 'Đang lắng nghe...',
          sub: 'Nói câu hỏi của bạn'
        };
      case 'PROCESSING':
        return {
          icon: Loader2,
          iconColor: 'text-indigo-400 animate-spin',
          title: 'Đang xử lý...',
          sub: 'Vui lòng chờ giây lát'
        };
      case 'SPEAKING':
        return {
          icon: Volume2,
          iconColor: 'text-blue-400 animate-pulse',
          title: 'SafeDrive đang trả lời',
          sub: 'Chạm để dừng đọc'
        };
      case 'DISABLED':
        return {
          icon: Mic,
          iconColor: 'text-slate-500',
          title: 'Microphone đang tắt',
          sub: 'Bật từ cài đặt'
        };
      case 'IDLE':
      default:
        return {
          icon: Mic,
          iconColor: 'text-cyan-400',
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
      className="h-[52px] sm:h-[56px] px-4 rounded-2xl bg-[#132437] border border-slate-800 hover:border-cyan-500/40 flex items-center justify-between cursor-pointer transition shrink-0 shadow-sm group"
    >
      <div className="flex items-center gap-3">
        <div className="p-2 rounded-xl bg-cyan-500/10 border border-cyan-500/20 text-cyan-400 group-hover:scale-105 transition">
          <IconComponent size={18} className={config.iconColor} />
        </div>
        <div>
          <div className="flex items-center gap-2">
            <span className="w-2 h-2 rounded-full bg-emerald-400 animate-pulse" />
            <span className="text-xs font-bold text-white">{config.title}</span>
          </div>
          <p className="text-[11px] text-slate-400 font-medium">
            {config.sub}
          </p>
        </div>
      </div>

      <span className="text-[10px] font-mono font-semibold text-cyan-400 bg-cyan-500/10 px-2.5 py-1 rounded-lg border border-cyan-500/20">
        Voice Assistant
      </span>
    </div>
  );
};
