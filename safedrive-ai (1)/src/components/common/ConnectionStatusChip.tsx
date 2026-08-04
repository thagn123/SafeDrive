import React from 'react';
import { Wifi, WifiOff } from 'lucide-react';

interface ConnectionStatusChipProps {
  isConnected: boolean;
  onClick?: () => void;
  className?: string;
}

export const ConnectionStatusChip: React.FC<ConnectionStatusChipProps> = ({ 
  isConnected, 
  onClick,
  className = '' 
}) => {
  return (
    <button
      onClick={onClick}
      type="button"
      className={`inline-flex items-center gap-2 px-3 py-1.5 rounded-full text-xs font-medium transition-all ${
        isConnected 
          ? 'bg-emerald-950/60 text-emerald-400 border border-emerald-800/60 hover:bg-emerald-900/60' 
          : 'bg-slate-800/80 text-slate-300 border border-slate-700 hover:bg-slate-700/80'
      } ${className}`}
      title={isConnected ? 'Đang kết nối backend SafeDrive AI' : 'Chế độ Ngoại tuyến (Đang dùng Mock State)'}
    >
      <span className="relative flex h-2 w-2">
        {isConnected && (
          <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-emerald-400 opacity-75"></span>
        )}
        <span className={`relative inline-flex rounded-full h-2 w-2 ${isConnected ? 'bg-emerald-500' : 'bg-slate-500'}`}></span>
      </span>
      {isConnected ? (
        <span className="inline-flex items-center gap-1">
          <Wifi size={13} aria-hidden="true" />
          <span>Đã kết nối</span>
        </span>
      ) : (
        <span className="inline-flex items-center gap-1">
          <WifiOff size={13} aria-hidden="true" />
          <span>Ngoại tuyến</span>
        </span>
      )}
    </button>
  );
};
