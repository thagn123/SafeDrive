import React from 'react';
import { HaUiLogo } from '../common/HaUiLogo';
import { ConnectionStatusChip } from '../common/ConnectionStatusChip';

interface CompactAppHeaderProps {
  isConnected: boolean;
  onToggleConnection: () => void;
}

export const CompactAppHeader: React.FC<CompactAppHeaderProps> = ({
  isConnected,
  onToggleConnection
}) => {
  return (
    <header className="h-[52px] sm:h-[56px] px-3.5 sm:px-4 rounded-2xl bg-[#132437] border border-slate-800 flex items-center justify-between shrink-0 shadow-sm">
      <div className="flex items-center gap-2.5">
        <HaUiLogo size="sm" />
        <div className="flex flex-col">
          <h1 className="text-sm sm:text-base font-black text-white tracking-tight leading-none">
            SafeDrive AI
          </h1>
          <span className="text-[10px] text-slate-400 font-medium hidden xs:inline mt-0.5">
            Hỗ trợ lái xe an toàn
          </span>
        </div>
      </div>

      <div className="flex items-center gap-2">
        <ConnectionStatusChip 
          isConnected={isConnected} 
          onClick={onToggleConnection}
        />
      </div>
    </header>
  );
};
