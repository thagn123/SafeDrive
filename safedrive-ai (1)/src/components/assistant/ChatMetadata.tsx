import React from 'react';
import { Zap, Cloud } from 'lucide-react';

interface ChatMetadataProps {
  latencyMs?: number;
  route?: string;
  className?: string;
}

export const ChatMetadata: React.FC<ChatMetadataProps> = ({
  latencyMs,
  route,
  className = ''
}) => {
  if (!latencyMs && !route) return null;

  const isFastPath = route === 'safety_fast_path';

  return (
    <div className={`inline-flex items-center gap-1.5 px-2.5 py-1 rounded-md bg-[#08131F] border border-slate-800 text-[11px] font-mono text-slate-400 ${className}`}>
      {isFastPath ? (
        <Zap size={12} className="text-cyan-400 shrink-0" />
      ) : (
        <Cloud size={12} className="text-indigo-400 shrink-0" />
      )}
      <span>{isFastPath ? 'Fast Path' : 'Cloud Reasoning'}</span>
      {latencyMs && (
        <>
          <span className="text-slate-600">·</span>
          <span className="text-cyan-300 font-semibold">{latencyMs} ms</span>
        </>
      )}
    </div>
  );
};
