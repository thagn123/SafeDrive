import React from 'react';
import { AlertTriangle, CheckCircle, X } from 'lucide-react';
import { SafeDriveAction } from '../../types/safedrive';

interface ConfirmActionDialogProps {
  action: SafeDriveAction | null;
  onConfirm: () => void;
  onCancel: () => void;
}

export const ConfirmActionDialog: React.FC<ConfirmActionDialogProps> = ({
  action,
  onConfirm,
  onCancel
}) => {
  if (!action) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-slate-950/80 backdrop-blur-sm animate-fadeIn">
      <div 
        className="w-full max-w-md bg-[#132437] border border-cyan-500/30 rounded-2xl p-6 shadow-2xl text-slate-100 space-y-5"
        role="dialog"
        aria-labelledby="confirm-dialog-title"
        aria-modal="true"
      >
        <div className="flex items-start justify-between gap-3">
          <div className="flex items-center gap-3">
            <div className="p-3 rounded-xl bg-cyan-500/10 text-cyan-400 border border-cyan-500/20">
              <AlertTriangle size={24} />
            </div>
            <div>
              <h3 id="confirm-dialog-title" className="text-lg font-bold text-white">
                Xác nhận hành động an toàn
              </h3>
              <p className="text-xs text-slate-400 mt-0.5">
                SafeDrive AI cần sự đồng ý của bạn
              </p>
            </div>
          </div>
          <button 
            onClick={onCancel}
            className="p-1.5 rounded-lg text-slate-400 hover:text-white hover:bg-slate-800 transition"
            aria-label="Đóng"
          >
            <X size={20} />
          </button>
        </div>

        <div className="p-4 rounded-xl bg-[#08131F] border border-slate-800 text-sm text-slate-200">
          <p className="font-semibold text-cyan-300 mb-1">{action.title}</p>
          <p className="text-xs text-slate-400 leading-relaxed">
            Bạn có chắc chắn muốn thực thi hành động này khi đang vận hành xe không?
          </p>
        </div>

        <div className="flex items-center gap-3 pt-2">
          <button
            onClick={onCancel}
            type="button"
            className="flex-1 min-h-[48px] px-4 rounded-xl border border-slate-700 bg-slate-800/80 hover:bg-slate-700 text-slate-200 font-medium text-sm transition"
          >
            Hủy bỏ
          </button>
          <button
            onClick={onConfirm}
            type="button"
            className="flex-1 min-h-[48px] px-4 rounded-xl bg-cyan-500 hover:bg-cyan-400 text-slate-950 font-bold text-sm shadow-lg shadow-cyan-500/20 transition flex items-center justify-center gap-2"
          >
            <CheckCircle size={18} />
            <span>Xác nhận</span>
          </button>
        </div>
      </div>
    </div>
  );
};
