import React from 'react';
import { RiskAssessment } from '../../types/safedrive';
import { RiskBadge } from '../common/RiskBadge';
import { ShieldCheck, AlertTriangle, Siren, Info, Eye } from 'lucide-react';

interface RiskHeroCardProps {
  risk: RiskAssessment;
  onActionClick?: () => void;
  developerMode?: boolean;
}

export const RiskHeroCard: React.FC<RiskHeroCardProps> = ({
  risk,
  onActionClick,
  developerMode = false
}) => {
  const levelStyles = {
    LOW: {
      cardBg: 'bg-gradient-to-br from-[#132437] via-[#132437] to-emerald-950/40 border-emerald-500/30',
      accentGlow: 'shadow-emerald-950/20',
      icon: ShieldCheck,
      iconColor: 'text-emerald-400 bg-emerald-500/10 border-emerald-500/20'
    },
    MEDIUM: {
      cardBg: 'bg-gradient-to-br from-[#132437] via-[#132437] to-amber-950/50 border-amber-500/40',
      accentGlow: 'shadow-amber-950/30',
      icon: Info,
      iconColor: 'text-amber-400 bg-amber-500/10 border-amber-500/30'
    },
    HIGH: {
      cardBg: 'bg-gradient-to-br from-[#132437] via-[#1A2E44] to-orange-950/60 border-orange-500/50',
      accentGlow: 'shadow-orange-950/40',
      icon: AlertTriangle,
      iconColor: 'text-orange-400 bg-orange-500/20 border-orange-500/40'
    },
    CRITICAL: {
      cardBg: 'bg-gradient-to-br from-[#1E1120] via-[#2A121D] to-red-950/80 border-red-500/70',
      accentGlow: 'shadow-red-950/50 animate-pulse',
      icon: Siren,
      iconColor: 'text-red-400 bg-red-500/25 border-red-500/50'
    }
  }[risk.level];

  const IconComponent = levelStyles.icon;

  return (
    <div className={`relative overflow-hidden rounded-2xl border p-5 sm:p-6 shadow-xl transition-all ${levelStyles.cardBg} ${levelStyles.accentGlow}`}>
      <div className="flex flex-col sm:flex-row sm:items-start justify-between gap-4">
        <div className="flex items-start gap-4">
          <div className={`p-3.5 rounded-2xl border shrink-0 ${levelStyles.iconColor}`}>
            <IconComponent size={32} className={risk.level === 'CRITICAL' ? 'animate-bounce' : ''} />
          </div>
          <div className="space-y-1">
            <div className="flex items-center gap-2 flex-wrap">
              <RiskBadge level={risk.level} size="lg" />
              <span className="text-xs uppercase tracking-wider text-slate-400 font-semibold">Trạng thái an toàn</span>
            </div>
            <h2 className="text-xl sm:text-2xl font-bold text-white tracking-tight pt-1">
              {risk.title}
            </h2>
            <p className="text-sm sm:text-base text-slate-300 leading-relaxed max-w-xl">
              {risk.message}
            </p>
          </div>
        </div>

        {onActionClick && (risk.level === 'HIGH' || risk.level === 'CRITICAL') && (
          <button
            onClick={onActionClick}
            type="button"
            className="sm:self-center min-h-[48px] px-4 py-2.5 rounded-xl bg-orange-500 hover:bg-orange-400 text-slate-950 font-bold text-sm shadow-lg transition flex items-center justify-center gap-2 shrink-0"
          >
            <Eye size={18} />
            <span>Xem chi tiết cảnh báo</span>
          </button>
        )}
      </div>

      {/* Developer Mode: Reason Codes Chips */}
      {developerMode && risk.reasonCodes.length > 0 && (
        <div className="mt-4 pt-3 border-t border-slate-800/80 flex items-center gap-2 flex-wrap text-xs text-slate-400">
          <span className="font-mono text-[10px] uppercase text-cyan-400">Reason Codes:</span>
          {risk.reasonCodes.map((code, idx) => (
            <span key={idx} className="font-mono bg-[#08131F] border border-slate-700/80 px-2 py-0.5 rounded text-[11px] text-slate-300">
              {code}
            </span>
          ))}
        </div>
      )}
    </div>
  );
};
