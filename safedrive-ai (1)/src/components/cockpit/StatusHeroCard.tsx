import React from 'react';
import { RiskAssessment, RestRecommendation } from '../../types/safedrive';
import { ShieldCheck, AlertTriangle, Siren, Info, Sparkles, CheckCircle2 } from 'lucide-react';

interface StatusHeroCardProps {
  risk: RiskAssessment;
  restRecommendation?: RestRecommendation;
  activeSourceCount?: number;
  totalSourceCount?: number;
  onOpenDetails?: () => void;
  onOpenSafetyAlert?: () => void;
}

export const StatusHeroCard: React.FC<StatusHeroCardProps> = ({
  risk,
  restRecommendation,
  activeSourceCount = 3,
  totalSourceCount = 4,
  onOpenDetails,
  onOpenSafetyAlert
}) => {
  const getStatusConfig = () => {
    switch (risk.level) {
      case 'CRITICAL':
        return {
          bg: 'bg-gradient-to-r from-red-950/80 via-[#2A121D] to-[#1E1120] border-red-500/70',
          badgeBg: 'bg-red-500/20 text-red-300 border-red-500/50',
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
          badgeText: 'NÊN THEO DÕI',
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

  const sourcesUsed = restRecommendation?.availableSourceCount ?? activeSourceCount;
  const sourcesTotal = restRecommendation?.totalSourceCount ?? totalSourceCount;

  const getConfidenceText = () => {
    if (restRecommendation?.confidence) {
      if (restRecommendation.confidence === 'HIGH') return 'Tin cậy: Cao';
      if (restRecommendation.confidence === 'MEDIUM') return 'Tin cậy: Trung bình';
      return 'Tin cậy: Thấp';
    }
    if (risk.level === 'CRITICAL' || risk.level === 'HIGH') return 'Tin cậy: Cao';
    if (risk.level === 'MEDIUM') return 'Tin cậy: Trung bình';
    return 'Tin cậy: Cao';
  };

  const handleClick = () => {
    if (risk.level === 'CRITICAL' || risk.level === 'HIGH') {
      if (onOpenSafetyAlert) onOpenSafetyAlert();
      else if (onOpenDetails) onOpenDetails();
    } else {
      if (onOpenDetails) onOpenDetails();
    }
  };

  const displayMessage = restRecommendation?.message || risk.message;
  const displayTitle = restRecommendation?.title || config.title;

  return (
    <div 
      onClick={handleClick}
      className={`p-3 sm:p-3.5 rounded-2xl border ${config.bg} shadow-md transition flex flex-col justify-between cursor-pointer hover:border-cyan-500/40 shrink-0 group relative overflow-hidden min-h-[128px] h-full`}
    >
      {/* Eyebrow & Status Badge */}
      <div className="flex items-center justify-between gap-2 shrink-0">
        <span className="text-[10px] font-extrabold uppercase tracking-widest text-slate-400 flex items-center gap-1.5">
          <Sparkles size={12} className="text-cyan-400" /> TRẠNG THÁI HIỆN TẠI
        </span>
        <span className={`px-2.5 py-0.5 rounded-md border text-[10px] font-bold tracking-wider ${config.badgeBg}`}>
          {config.badgeText}
        </span>
      </div>

      {/* Main Priority Info: Title & Recommendation */}
      <div className="flex items-start gap-3 my-1.5 flex-1 min-h-0">
        <div className={`p-2 rounded-xl border shrink-0 mt-0.5 ${config.iconColor}`}>
          <IconComponent size={20} className={risk.level === 'CRITICAL' ? 'animate-bounce' : ''} />
        </div>
        <div className="flex-1 min-w-0 space-y-1">
          <h2 className="text-sm sm:text-base font-black text-white tracking-tight leading-snug truncate">
            {displayTitle}
          </h2>
          <p className="text-xs text-slate-200 font-medium leading-snug line-clamp-2">
            {displayMessage}
          </p>
        </div>
      </div>

      {/* Metadata Strip (Confidence, Sources, Updated Time) */}
      <div className="pt-2 border-t border-slate-800/80 flex items-center justify-between text-[11px] text-slate-400 font-medium gap-2 shrink-0">
        <div className="flex items-center gap-2 truncate">
          <span className="text-slate-300 font-semibold">{getConfidenceText()}</span>
          <span className="text-slate-600">•</span>
          <span className="text-cyan-400 font-bold">{sourcesUsed}/{sourcesTotal} nguồn</span>
        </div>
        <span className="text-[10px] text-slate-400 font-mono shrink-0">Vừa cập nhật</span>
      </div>
    </div>
  );
};

