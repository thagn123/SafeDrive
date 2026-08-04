import React from 'react';
import { DriverSupportSignals, RestRecommendation } from '../../types/safedrive';
import { DriverStatusCard } from './DriverStatusCard';
import { X, Activity } from 'lucide-react';

interface DriverSupportDetailsModalProps {
  isOpen: boolean;
  onClose: () => void;
  signals: DriverSupportSignals;
  restRecommendation: RestRecommendation;
}

export const DriverSupportDetailsModal: React.FC<DriverSupportDetailsModalProps> = ({
  isOpen,
  onClose,
  signals,
  restRecommendation
}) => {
  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-slate-950/80 backdrop-blur-md animate-fadeIn">
      <div className="w-full max-w-lg max-h-[85vh] overflow-y-auto rounded-3xl bg-[#0D1B2A] border border-slate-700 p-5 shadow-2xl space-y-4 text-slate-100">
        <div className="flex items-center justify-between pb-3 border-b border-slate-800">
          <div className="flex items-center gap-2.5">
            <div className="p-2 rounded-xl bg-cyan-500/20 text-cyan-400">
              <Activity size={20} />
            </div>
            <div>
              <h3 className="text-base font-bold text-white">Chi tiết tín hiệu hỗ trợ</h3>
              <p className="text-xs text-slate-400">Dữ liệu nguồn và đánh giá tổng hợp</p>
            </div>
          </div>

          <button
            onClick={onClose}
            type="button"
            className="p-2 rounded-xl text-slate-400 hover:text-white hover:bg-slate-800 transition"
          >
            <X size={20} />
          </button>
        </div>

        <DriverStatusCard 
          signals={signals}
          restRecommendation={restRecommendation}
        />

        <div className="pt-2">
          <button
            onClick={onClose}
            type="button"
            className="w-full min-h-[44px] rounded-xl bg-slate-800 hover:bg-slate-700 text-slate-200 text-xs font-bold transition"
          >
            Đóng
          </button>
        </div>
      </div>
    </div>
  );
};
