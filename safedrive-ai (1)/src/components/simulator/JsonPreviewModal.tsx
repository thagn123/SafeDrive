import React, { useState } from 'react';
import { VehicleState, RiskAssessment } from '../../types/safedrive';
import { Code, Copy, Check, X } from 'lucide-react';

interface JsonPreviewModalProps {
  vehicleState: VehicleState;
  riskAssessment: RiskAssessment;
  isOpen: boolean;
  onClose: () => void;
}

export const JsonPreviewModal: React.FC<JsonPreviewModalProps> = ({
  vehicleState,
  riskAssessment,
  isOpen,
  onClose
}) => {
  const [copied, setCopied] = useState(false);

  if (!isOpen) return null;

  const payload = {
    request_id: `req_${Date.now().toString().slice(-6)}`,
    timestamp: new Date().toISOString(),
    vehicle_state: {
      speed_kmh: vehicleState.speedKmh,
      cabin_temperature_c: vehicleState.cabinTemperatureC,
      engine_temperature_c: vehicleState.engineTemperatureC,
      battery_percent: vehicleState.batteryOrFuelPercent,
      driver_support_signals: vehicleState.driverSupportSignals,
      active_dtcs: vehicleState.activeDtcs,
      crash_detected: vehicleState.crashDetected,
      passenger_response: vehicleState.passengerResponse,
    },
    risk_assessment: riskAssessment
  };

  const jsonString = JSON.stringify(payload, null, 2);

  const handleCopy = () => {
    navigator.clipboard.writeText(jsonString);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-slate-950/80 backdrop-blur-sm animate-fadeIn">
      <div className="w-full max-w-2xl bg-[#132437] border border-cyan-500/30 rounded-2xl p-6 shadow-2xl text-slate-100 space-y-4 max-h-[85vh] flex flex-col">
        <div className="flex items-center justify-between border-b border-slate-800 pb-3">
          <div className="flex items-center gap-2 text-cyan-400">
            <Code size={20} />
            <h3 className="text-base font-bold text-white">Payload JSON Demo Backend</h3>
          </div>

          <div className="flex items-center gap-2">
            <button
              onClick={handleCopy}
              type="button"
              className="px-3 py-1.5 rounded-lg bg-slate-800 hover:bg-slate-700 text-xs font-semibold text-cyan-300 border border-slate-700 flex items-center gap-1.5 transition"
            >
              {copied ? <Check size={14} className="text-emerald-400" /> : <Copy size={14} />}
              <span>{copied ? 'Đã chép' : 'Sao chép JSON'}</span>
            </button>
            <button
              onClick={onClose}
              type="button"
              className="p-1.5 rounded-lg text-slate-400 hover:text-white hover:bg-slate-800 transition"
            >
              <X size={20} />
            </button>
          </div>
        </div>

        <div className="flex-1 overflow-auto bg-[#08131F] border border-slate-800 rounded-xl p-4 font-mono text-xs text-cyan-300">
          <pre className="whitespace-pre-wrap">{jsonString}</pre>
        </div>

        <div className="flex justify-end pt-2">
          <button
            onClick={onClose}
            type="button"
            className="px-5 py-2.5 rounded-xl bg-cyan-500 hover:bg-cyan-400 text-slate-950 font-bold text-sm shadow-md transition"
          >
            Đóng
          </button>
        </div>
      </div>
    </div>
  );
};
