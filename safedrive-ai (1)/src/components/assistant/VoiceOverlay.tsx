import React, { useState } from 'react';
import { useSafeDrive } from '../../context/SafeDriveContext';
import { 
  Mic, 
  Volume2, 
  Square, 
  X, 
  Sparkles, 
  ShieldCheck, 
  Send,
  AlertCircle 
} from 'lucide-react';

export const VoiceOverlay: React.FC = () => {
  const { 
    voiceState, 
    voiceTranscript, 
    voiceResponseText, 
    cancelVoice, 
    submitVoiceQuery, 
    stopSpeaking,
    isAssistantThinking 
  } = useSafeDrive();

  const [inputQuery, setInputQuery] = useState('');

  if (voiceState === 'IDLE' || voiceState === 'DISABLED') {
    return null;
  }

  const handleQuickSpeech = (query: string) => {
    setInputQuery(query);
    submitVoiceQuery(query);
  };

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (inputQuery.trim()) {
      submitVoiceQuery(inputQuery);
      setInputQuery('');
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-end sm:items-center justify-center p-4 bg-slate-950/85 backdrop-blur-md animate-fadeIn">
      <div className="w-full max-w-lg rounded-3xl bg-[#0D1B2A] border-2 border-cyan-500/50 p-6 shadow-2xl text-slate-100 space-y-6">
        {/* Header */}
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2.5">
            <div className="p-2 rounded-xl bg-cyan-500/20 text-cyan-400">
              <Sparkles size={22} className="animate-pulse" />
            </div>
            <div>
              <span className="text-xs font-black uppercase tracking-widest text-cyan-400">
                SAFEDRIVE VOICE ASSISTANT
              </span>
              <p className="text-xs text-slate-400">Điều khiển bằng giọng nói ô tô</p>
            </div>
          </div>

          <button
            onClick={cancelVoice}
            type="button"
            className="p-2 rounded-xl text-slate-400 hover:text-white hover:bg-slate-800 transition"
            aria-label="Đóng"
          >
            <X size={20} />
          </button>
        </div>

        {/* State Visualizer & Status */}
        <div className="flex flex-col items-center justify-center py-4 space-y-4 text-center">
          {/* Animated Mic Circle */}
          <div className="relative flex items-center justify-center">
            {voiceState === 'LISTENING' && (
              <>
                <span className="animate-ping absolute inline-flex h-24 w-24 rounded-full bg-cyan-400 opacity-30" />
                <span className="animate-pulse absolute inline-flex h-20 w-20 rounded-full bg-cyan-500/20" />
              </>
            )}
            {voiceState === 'SPEAKING' && (
              <span className="animate-pulse absolute inline-flex h-20 w-20 rounded-full bg-blue-500/30" />
            )}

            <div className={`relative z-10 w-16 h-16 rounded-full flex items-center justify-center border-2 transition-all ${
              voiceState === 'WAKE_WORD_DETECTED'
                ? 'bg-amber-500/20 border-amber-400 text-amber-300 scale-110'
                : voiceState === 'LISTENING'
                ? 'bg-cyan-500/20 border-cyan-400 text-cyan-300'
                : voiceState === 'PROCESSING'
                ? 'bg-indigo-500/20 border-indigo-400 text-indigo-300'
                : voiceState === 'SPEAKING'
                ? 'bg-blue-500/20 border-blue-400 text-blue-300'
                : 'bg-red-500/20 border-red-400 text-red-300'
            }`}>
              {voiceState === 'SPEAKING' ? (
                <Volume2 size={30} className="animate-pulse" />
              ) : (
                <Mic size={30} className={voiceState === 'LISTENING' ? 'animate-bounce' : ''} />
              )}
            </div>
          </div>

          {/* Privacy Mic Indicator */}
          <div className="inline-flex items-center gap-2 px-3 py-1 rounded-full bg-[#08131F] border border-slate-800 text-[11px] font-mono text-emerald-400">
            <span className="w-2 h-2 rounded-full bg-emerald-400 animate-ping" />
            <span>Microphone đang mở (Bảo mật quyền riêng tư)</span>
          </div>

          {/* Title based on state */}
          <div className="space-y-1 max-w-sm">
            <h3 className="text-lg font-bold text-white">
              {voiceState === 'WAKE_WORD_DETECTED' && 'Đã nhận diện “Hey SafeDrive”!'}
              {voiceState === 'LISTENING' && 'SafeDrive đang lắng nghe...'}
              {voiceState === 'PROCESSING' && 'SafeDrive đang xử lý câu hỏi...'}
              {voiceState === 'SPEAKING' && 'SafeDrive đang đọc phản hồi'}
              {voiceState === 'ERROR' && 'Không nghe rõ yêu cầu'}
            </h3>

            <p className="text-xs text-slate-300 leading-relaxed font-medium">
              {voiceTranscript 
                ? `“${voiceTranscript}”` 
                : voiceResponseText 
                ? voiceResponseText 
                : 'Hãy nói câu hỏi của bạn hoặc chọn các tình huống mô phỏng bên dưới.'}
            </p>
          </div>
        </div>

        {/* Quick Sample Voice Prompts (Useful for previewing voice flow) */}
        {voiceState === 'LISTENING' && (
          <div className="space-y-2 pt-1 border-t border-slate-800">
            <span className="text-[11px] font-semibold text-slate-400 uppercase tracking-wider block">
              Thử giọng nói nhanh:
            </span>
            <div className="flex flex-wrap gap-2">
              <button
                onClick={() => handleQuickSpeech('Xe của tôi có mã lỗi gì không?')}
                type="button"
                className="px-3 py-1.5 rounded-xl bg-[#08131F] hover:bg-slate-800 text-xs text-cyan-300 border border-slate-700 transition"
              >
                “Xe của tôi có mã lỗi gì không?”
              </button>
              <button
                onClick={() => handleQuickSpeech('Tôi đã lái bao nhiêu phút rồi?')}
                type="button"
                className="px-3 py-1.5 rounded-xl bg-[#08131F] hover:bg-slate-800 text-xs text-cyan-300 border border-slate-700 transition"
              >
                “Tôi đã lái bao nhiêu phút rồi?”
              </button>
              <button
                onClick={() => handleQuickSpeech('Nhiệt độ động cơ hiện tại?')}
                type="button"
                className="px-3 py-1.5 rounded-xl bg-[#08131F] hover:bg-slate-800 text-xs text-cyan-300 border border-slate-700 transition"
              >
                “Nhiệt độ động cơ hiện tại?”
              </button>
            </div>
          </div>
        )}

        {/* Form input for typing alternative */}
        {voiceState === 'LISTENING' && (
          <form onSubmit={handleSubmit} className="flex gap-2">
            <input 
              type="text"
              value={inputQuery}
              onChange={(e) => setInputQuery(e.target.value)}
              placeholder="Hoặc nhập câu hỏi..."
              className="flex-1 min-h-[44px] px-3.5 rounded-xl bg-[#08131F] border border-slate-700 text-xs text-white placeholder-slate-500 focus:outline-none focus:border-cyan-500"
            />
            <button
              type="submit"
              disabled={!inputQuery.trim()}
              className="px-4 py-2.5 rounded-xl bg-cyan-500 hover:bg-cyan-400 text-slate-950 font-bold text-xs transition disabled:opacity-50"
            >
              <Send size={16} />
            </button>
          </form>
        )}

        {/* Action Controls */}
        <div className="flex items-center gap-3 pt-2">
          {voiceState === 'SPEAKING' ? (
            <button
              onClick={stopSpeaking}
              type="button"
              className="w-full min-h-[48px] px-4 rounded-xl bg-slate-800 hover:bg-slate-700 text-slate-200 font-bold text-sm transition flex items-center justify-center gap-2 border border-slate-700"
            >
              <Square size={16} />
              <span>Dừng đọc / Khôi phục</span>
            </button>
          ) : (
            <button
              onClick={cancelVoice}
              type="button"
              className="w-full min-h-[48px] px-4 rounded-xl bg-slate-800 hover:bg-slate-700 text-slate-300 font-bold text-sm transition flex items-center justify-center gap-2 border border-slate-700"
            >
              <X size={16} />
              <span>Hủy bỏ</span>
            </button>
          )}
        </div>
      </div>
    </div>
  );
};
