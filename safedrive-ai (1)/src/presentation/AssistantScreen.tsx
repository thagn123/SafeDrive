import React, { useState, useEffect, useRef } from 'react';
import { useSafeDrive } from '../context/SafeDriveContext';
import { ChatBubble } from '../components/assistant/ChatBubble';
import { Volume2, VolumeX, Send, Mic, Sparkles, RefreshCw, AlertCircle } from 'lucide-react';

export const AssistantScreen: React.FC = () => {
  const { 
    chatMessages, 
    sendChatMessage, 
    isAssistantThinking, 
    settings, 
    updateSettings,
    executeAction,
    pendingPrompt,
    clearPendingPrompt,
    triggerWakeWord,
    voiceState,
    voiceTranscript
  } = useSafeDrive();

  const [inputQuery, setInputQuery] = useState('');
  const chatEndRef = useRef<HTMLDivElement>(null);

  // Auto-fill prompt if navigated from Diagnostics or Cockpit
  useEffect(() => {
    if (pendingPrompt) {
      setInputQuery(pendingPrompt);
      clearPendingPrompt();
    }
  }, [pendingPrompt, clearPendingPrompt]);

  // Sync input query with live transcript while listening/processing
  useEffect(() => {
    if (voiceTranscript && (voiceState === 'LISTENING' || voiceState === 'PROCESSING')) {
      setInputQuery(voiceTranscript);
    }
  }, [voiceTranscript, voiceState]);

  // Auto-clear input field when voice state transitions
  useEffect(() => {
    if (
      voiceState === 'SPEAKING' ||
      voiceState === 'IDLE' ||
      voiceState === 'ERROR' ||
      voiceState === 'WAKE_WORD_DETECTED' ||
      voiceState === 'PROCESSING'
    ) {
      setInputQuery('');
    }
  }, [voiceState]);

  // Auto scroll to bottom
  useEffect(() => {
    chatEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [chatMessages, isAssistantThinking]);

  const handleSend = async (textToSend?: string) => {
    const query = (textToSend || inputQuery).trim();
    if (!query || isAssistantThinking) return;

    setInputQuery('');
    await sendChatMessage(query);
    setInputQuery('');
  };

  const handleMicClick = () => {
    setInputQuery('');
    triggerWakeWord();
  };

  const handleQuickPrompt = (promptText: string) => {
    handleSend(promptText);
  };

  return (
    <div className="flex flex-col h-[calc(100vh-140px)] max-h-[850px] bg-[#08131F] rounded-2xl border border-slate-800 overflow-hidden shadow-2xl animate-fadeIn">
      {/* Top Bar Header */}
      <header className="p-4 bg-[#132437] border-b border-slate-800 flex items-center justify-between gap-3 shrink-0">
        <div className="flex items-center gap-3">
          <div className="p-2.5 rounded-xl bg-cyan-500/10 border border-cyan-500/20 text-cyan-400">
            <Sparkles size={20} />
          </div>
          <div>
            <h2 className="text-base font-bold text-white">Trợ lý SafeDrive AI</h2>
            <p className="text-xs text-slate-400">
              {settings.isConnected ? 'Sẵn sàng phản hồi (Local/Cloud Engine)' : 'Chế độ ngoại tuyến'}
            </p>
          </div>
        </div>

        {/* TTS Toggle */}
        <button
          onClick={() => updateSettings({ ttsEnabled: !settings.ttsEnabled })}
          type="button"
          className={`p-2.5 rounded-xl border transition flex items-center gap-1.5 text-xs font-semibold ${
            settings.ttsEnabled
              ? 'bg-cyan-500/15 border-cyan-500/30 text-cyan-300'
              : 'bg-slate-800 border-slate-700 text-slate-400'
          }`}
          title={settings.ttsEnabled ? 'Tắt đọc giọng nói (TTS)' : 'Bật đọc giọng nói (TTS)'}
        >
          {settings.ttsEnabled ? <Volume2 size={18} /> : <VolumeX size={18} />}
          <span className="hidden sm:inline">{settings.ttsEnabled ? 'Âm thanh bật' : 'Tắt âm thanh'}</span>
        </button>
      </header>

      {/* Offline Alert Warning */}
      {!settings.isConnected && (
        <div className="px-4 py-2 bg-amber-950/80 border-b border-amber-500/30 text-amber-300 text-xs flex items-center justify-between">
          <div className="flex items-center gap-2">
            <AlertCircle size={15} />
            <span>Đang ở chế độ ngoại tuyến. Các câu hỏi được xử lý cục bộ với dữ liệu mock.</span>
          </div>
          <button
            onClick={() => updateSettings({ isConnected: true })}
            type="button"
            className="underline text-amber-200 hover:text-white font-bold"
          >
            Thử lại
          </button>
        </div>
      )}

      {/* Chat Messages Scroll Container */}
      <div className="flex-1 overflow-y-auto p-4 space-y-2">
        {chatMessages.map(msg => (
          <ChatBubble 
            key={msg.id} 
            message={msg} 
            onExecuteAction={executeAction}
            developerMode={settings.developerMode}
          />
        ))}

        {/* Thinking Indicator */}
        {isAssistantThinking && (
          <div className="flex items-center gap-3 my-3 animate-fadeIn">
            <div className="w-9 h-9 rounded-full bg-cyan-600/30 border border-cyan-400/40 text-cyan-400 flex items-center justify-center shrink-0">
              <Sparkles size={18} className="animate-spin" />
            </div>
            <div className="p-3.5 rounded-2xl bg-[#132437] border border-slate-800 text-slate-300 text-xs flex items-center gap-2">
              <span className="animate-pulse font-medium">SafeDrive đang phân tích dữ liệu xe...</span>
            </div>
          </div>
        )}

        <div ref={chatEndRef} />
      </div>

      {/* Quick Suggestion Chips */}
      <div className="px-4 py-2 bg-[#0B1726] border-t border-slate-800/80 flex items-center gap-2 overflow-x-auto no-scrollbar shrink-0 text-xs">
        <span className="text-slate-500 font-semibold shrink-0 text-[11px]">Gợi ý:</span>
        <button
          onClick={() => handleQuickPrompt('Tôi đang cảm thấy mệt')}
          type="button"
          className="px-3 py-1.5 rounded-full bg-[#132437] hover:bg-slate-700 text-slate-200 border border-slate-700 whitespace-nowrap transition font-medium"
        >
          Tôi đang cảm thấy mệt
        </button>
        <button
          onClick={() => handleQuickPrompt('Tôi đã lái xe bao lâu rồi?')}
          type="button"
          className="px-3 py-1.5 rounded-full bg-[#132437] hover:bg-slate-700 text-slate-200 border border-slate-700 whitespace-nowrap transition font-medium"
        >
          Tôi đã lái bao lâu?
        </button>
        <button
          onClick={() => handleQuickPrompt('Xe có cảnh báo an toàn nào không?')}
          type="button"
          className="px-3 py-1.5 rounded-full bg-[#132437] hover:bg-slate-700 text-slate-200 border border-slate-700 whitespace-nowrap transition font-medium"
        >
          Xe có cảnh báo gì?
        </button>
        <button
          onClick={() => handleQuickPrompt('Kiểm tra nhiệt độ động cơ')}
          type="button"
          className="px-3 py-1.5 rounded-full bg-[#132437] hover:bg-slate-700 text-slate-200 border border-slate-700 whitespace-nowrap transition font-medium"
        >
          Nhiệt độ động cơ?
        </button>
        <button
          onClick={() => handleQuickPrompt('Gợi ý điểm dừng nghỉ gần đây')}
          type="button"
          className="px-3 py-1.5 rounded-full bg-[#132437] hover:bg-slate-700 text-slate-200 border border-slate-700 whitespace-nowrap transition font-medium"
        >
          Tìm điểm nghỉ gần đây
        </button>
      </div>

      {/* Footer Input Bar */}
      <footer className="p-3 bg-[#132437] border-t border-slate-800 shrink-0">
        <form 
          onSubmit={(e) => { e.preventDefault(); handleSend(); }}
          className="flex items-center gap-2"
        >
          {/* Mic simulator button */}
          <button
            type="button"
            onClick={handleMicClick}
            className="p-3 rounded-xl border border-slate-700 bg-[#08131F] text-cyan-400 hover:text-white hover:border-cyan-500 transition"
            title="Kích hoạt Hey SafeDrive (Giọng nói)"
          >
            <Mic size={20} />
          </button>

          {/* Input text field */}
          <input
            type="text"
            value={inputQuery}
            onChange={(e) => setInputQuery(e.target.value)}
            placeholder="Nhập câu hỏi hoặc nói 'Hey SafeDrive'..."
            disabled={isAssistantThinking}
            className="flex-1 min-h-[48px] px-4 rounded-xl bg-[#08131F] border border-slate-700 text-white placeholder-slate-500 text-sm focus:outline-none focus:border-cyan-500 transition"
          />

          {/* Send button */}
          <button
            type="submit"
            disabled={!inputQuery.trim() || isAssistantThinking}
            className="min-h-[48px] px-4 rounded-xl bg-cyan-500 hover:bg-cyan-400 disabled:opacity-50 disabled:hover:bg-cyan-500 text-slate-950 font-bold transition flex items-center justify-center shrink-0"
          >
            <Send size={20} />
          </button>
        </form>
      </footer>
    </div>
  );
};
