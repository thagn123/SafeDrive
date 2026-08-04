import React from 'react';
import { DriverSupportSignals } from '../../types/safedrive';
import { Activity, CheckCircle2, XCircle, MinusCircle, ChevronRight } from 'lucide-react';

interface DriverSignalSummaryProps {
  signals: DriverSupportSignals;
  onOpenDetails: () => void;
}

export const DriverSignalSummary: React.FC<DriverSignalSummaryProps> = ({
  signals,
  onOpenDetails
}) => {
  // Compute active signal counts
  const items = [
    {
      label: 'Vô lăng',
      active: signals.steeringSignalAvailable,
      value: signals.steeringSignalAvailable ? 'Gần đây' : 'Không có'
    },
    {
      label: 'Ghế lái',
      active: signals.seatSensorAvailable && (signals.driverSeatOccupied ?? false),
      value: signals.seatSensorAvailable 
        ? (signals.driverSeatOccupied ? 'Có người' : 'Trống') 
        : 'Tắt'
    },
    {
      label: 'Wearable',
      active: signals.wearableConnected,
      value: signals.wearableConnected 
        ? `${signals.wearableHeartRateBpm ?? 72} bpm` 
        : 'Chưa kết nối'
    },
    {
      label: 'Thời gian',
      active: signals.continuousDrivingMinutes !== null,
      value: signals.continuousDrivingMinutes !== null 
        ? `${signals.continuousDrivingMinutes}m` 
        : 'Không có'
    }
  ];

  const activeCount = items.filter(i => i.active).length;

  return (
    <div 
      onClick={onOpenDetails}
      className="p-3 sm:p-3.5 rounded-2xl bg-[#132437] border border-slate-800 hover:border-cyan-500/40 cursor-pointer transition shadow-sm shrink-0 group flex flex-col justify-between"
    >
      <div className="flex items-center justify-between mb-2">
        <div className="flex items-center gap-2">
          <Activity size={14} className="text-cyan-400 shrink-0" />
          <span className="text-[10px] font-extrabold uppercase tracking-widest text-slate-400">
            TÍN HIỆU HỖ TRỢ
          </span>
        </div>

        <div className="flex items-center gap-2">
          <span className="text-[10px] font-bold text-cyan-400 bg-cyan-500/10 px-2 py-0.5 rounded border border-cyan-500/20">
            {activeCount}/4 nguồn hoạt động
          </span>
          <ChevronRight size={14} className="text-slate-500 group-hover:text-white transition" />
        </div>
      </div>

      <div className="grid grid-cols-2 sm:grid-cols-4 gap-2 text-xs">
        {items.map((item, idx) => {
          return (
            <div 
              key={idx}
              className="p-2 rounded-xl bg-[#0B1724] border border-slate-800/80 flex items-center justify-between gap-1.5"
            >
              <div className="flex items-center gap-1.5 truncate">
                {item.active ? (
                  <CheckCircle2 size={13} className="text-emerald-400 shrink-0" />
                ) : (
                  <MinusCircle size={13} className="text-slate-500 shrink-0" />
                )}
                <span className="font-semibold text-slate-300 truncate text-[11px]">{item.label}:</span>
              </div>
              <span className={`text-[11px] font-medium truncate ${item.active ? 'text-slate-100 font-bold' : 'text-slate-400'}`}>
                {item.value}
              </span>
            </div>
          );
        })}
      </div>
    </div>
  );
};
