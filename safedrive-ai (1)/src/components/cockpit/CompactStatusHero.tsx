import React from 'react';
import { RiskAssessment } from '../../types/safedrive';
import { ShieldCheck, AlertTriangle, Siren, Info, ChevronRight } from 'lucide-react';

interface CompactStatusHeroProps {
  risk: RiskAssessment;
  onOpenDetails?: () => void;
  onOpenSafetyAlert?: () => void;
}

export const CompactStatusHero: React.FC<CompactStatusHeroProps> = ({
  risk,
  onOpenDetails,
  onOpenSafetyAlert
}) => {
  const getStatusConfig = () => {
    switch (risk.level) {
      case 'CRITICAL':
        return {
          bg: 'bg-gradient-to-r from-red-950/80 via-[#2A121D] to-[#1E1120] border-red-500/70',
          badgeBg: 'bg-red-500/20 text-red-300 border-red-500/40',
          badgeText: 'KHẨN CẤP',
          title: 'CẢNH BÁO KHẨN CẤP',
          icon: Siren,
          iconColor: 'text-red-400 bg-red-500/20 border-red-500/40'
        };
      case 'HIGH':
        return {
          bg: 'bg-gradient-to-r from-orange-950/70 via-[#1A2E44] to-[#132437] border-orange-500/50',
          badgeBg: 'bg-orange-500/20 text-orange-300 border-orange-500/40',
          badgeText: 'KHUYẾN NGHỊ NGHỈ',
          title: 'KHUYẾN NGHỊ DỪNG NGHỈ',
          icon: AlertTriangle,
          iconColor: 'text-orange-400 bg-orange-500/20 border-orange-500/40'
        };
      case 'MEDIUM':
        return {
          bg: 'bg-gradient-to-r from-amber-950/50 via-[#132437] to-[#132437] border-amber-500/40',
          badgeBg: 'bg-amber-500/20 text-amber-300 border-amber-500/30',
          badgeText: 'THEO DÕI',
          title: 'NÊN THEO DÕI',
          icon: Info,
          iconColor: 'text-amber-400 bg-amber-500/20 border-amber-500/30'
        };
      case 'LOW':
      default:
        return {
          bg: 'bg-gradient-to-r from-emerald-950/30 via-[#132437] to-[#132437] border-emerald-500/30',
          badgeBg: 'bg-emerald-500/20 text-emerald-300 border-emerald-500/30',
          badgeText: 'BÌNH THƯỜNG',
          title: 'HOẠT ĐỘNG BÌNH THƯỜNG',
          icon: ShieldCheck,
          iconColor: 'text-emerald-400 bg-emerald-500/15 border-emerald-500/30'
        };
    }
  };

  const config = getStatusConfig();
  const IconComponent = config.icon;

  const handleClick = () => {
    if (risk.level === 'CRITICAL' || risk.level === 'HIGH') {
      if (onOpenSafetyAlert) onOpenSafetyAlert();
      else if (onOpenDetails) onOpenDetails();
    } else {
      if (onOpenDetails) onOpenDetails();
    }
  };

  return (
    <div 
      onClick={handleClick}
      className={`min-h-[120px] max-h-[150px] p-4 rounded-2xl border ${config.bg} shadow-lg transition flex flex-col justify-between cursor-pointer hover:border-cyan-500/40 shrink-0 group`}
    >
      {/* Top row: Icon + Title + Chip */}
      <div className="flex items-center justify-between gap-3">
        <div className="flex items-center gap-3">
          <div className={`p-2.5 rounded-xl border shrink-0 ${config.iconColor}`}>
            <IconComponent size={22} className={risk.level === 'CRITICAL' ? 'animate-bounce' : ''} />
          </div>
          <h2 className="text-base font-black text-white tracking-tight">
            {config.title}
          </h2>
        </div>

        <div className="flex items-center gap-1.5">
          <span className={`px-2.5 py-1 rounded-lg border text-[11px] font-bold tracking-wider ${config.badgeBg}`}>
            {config.badgeText}
          </span>
          <ChevronRight size={16} className="text-slate-400 group-hover:text-white transition" />
        </div>
      </div>

      {/* Main Message (Max 2 lines, no reason codes or duplicate labels) */}
      <p className="text-xs sm:text-sm text-slate-200 font-medium leading-relaxed line-clamp-2 mt-2">
        {risk.message}
      </p>
    </div>
  );
};
