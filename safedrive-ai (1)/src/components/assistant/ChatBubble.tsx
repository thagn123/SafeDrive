import React from 'react';
import { ChatMessage, SafeDriveAction } from '../../types/safedrive';
import { RiskBadge } from '../common/RiskBadge';
import { ChatMetadata } from './ChatMetadata';
import { SafetyActionCard } from './SafetyActionCard';
import { Shield, User } from 'lucide-react';

interface ChatBubbleProps {
  message: ChatMessage;
  onExecuteAction: (action: SafeDriveAction) => void;
  developerMode?: boolean;
}

export const ChatBubble: React.FC<ChatBubbleProps> = ({
  message,
  onExecuteAction,
  developerMode = false
}) => {
  const isUser = message.sender === 'USER';

  return (
    <div className={`flex items-start gap-3 my-3 ${isUser ? 'flex-row-reverse' : 'flex-row'}`}>
      {/* Avatar */}
      <div className={`w-9 h-9 rounded-full flex items-center justify-center shrink-0 shadow-md ${
        isUser 
          ? 'bg-cyan-500 text-slate-950 font-bold' 
          : 'bg-gradient-to-tr from-cyan-600 to-blue-700 text-white border border-cyan-400/40'
      }`}>
        {isUser ? <User size={18} /> : <Shield size={18} />}
      </div>

      {/* Bubble Content */}
      <div className={`max-w-[85%] sm:max-w-[75%] space-y-2 ${isUser ? 'items-end' : 'items-start'}`}>
        <div className={`p-4 rounded-2xl text-sm leading-relaxed shadow-lg ${
          isUser 
            ? 'bg-cyan-600 text-white rounded-tr-none' 
            : 'bg-[#132437] border border-slate-800 text-slate-100 rounded-tl-none'
        }`}>
          <p className="whitespace-pre-wrap font-sans">{message.text}</p>

          {/* Inline Risk Assessment */}
          {!isUser && message.risk && message.risk.level !== 'LOW' && (
            <div className="mt-3 p-3 rounded-xl bg-[#08131F] border border-slate-800 space-y-2">
              <div className="flex items-center gap-2">
                <RiskBadge level={message.risk.level} size="sm" />
                <span className="text-xs font-bold text-white">{message.risk.title}</span>
              </div>
              <p className="text-xs text-slate-300">{message.risk.message}</p>

              {/* Developer Reason Codes */}
              {developerMode && message.risk.reasonCodes.length > 0 && (
                <div className="flex gap-1 flex-wrap pt-1">
                  {message.risk.reasonCodes.map((code, idx) => (
                    <span key={idx} className="text-[10px] font-mono bg-slate-800 text-cyan-300 px-1.5 py-0.5 rounded">
                      {code}
                    </span>
                  ))}
                </div>
              )}
            </div>
          )}

          {/* Action Cards */}
          {!isUser && message.actions && message.actions.length > 0 && (
            <div className="space-y-2 pt-1">
              {message.actions.map(action => (
                <SafetyActionCard key={action.id} action={action} onExecute={onExecuteAction} />
              ))}
            </div>
          )}
        </div>

        {/* Footer info */}
        <div className={`flex items-center gap-2 px-1 text-[11px] text-slate-400 ${isUser ? 'justify-end' : 'justify-start'}`}>
          <span>{message.timestamp}</span>
          {!isUser && developerMode && (
            <ChatMetadata latencyMs={message.latencyMs} route={message.route} />
          )}
        </div>
      </div>
    </div>
  );
};
