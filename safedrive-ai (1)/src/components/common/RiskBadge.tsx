import React from 'react';
import { RiskLevel } from '../../types/safedrive';
import { ShieldCheck, AlertCircle, AlertTriangle, Siren } from 'lucide-react';

interface RiskBadgeProps {
  level: RiskLevel;
  showText?: boolean;
  size?: 'sm' | 'md' | 'lg';
  className?: string;
}

export const RiskBadge: React.FC<RiskBadgeProps> = ({ 
  level, 
  showText = true, 
  size = 'md',
  className = '' 
}) => {
  const config = {
    LOW: {
      label: 'THẤP',
      fullLabel: 'AN TOÀN (LOW)',
      bgColor: 'bg-emerald-500/20',
      textColor: 'text-emerald-400',
      borderColor: 'border-emerald-500/40',
      icon: ShieldCheck,
    },
    MEDIUM: {
      label: 'TRUNG BÌNH',
      fullLabel: 'THEO DÕI (MEDIUM)',
      bgColor: 'bg-amber-500/20',
      textColor: 'text-amber-400',
      borderColor: 'border-amber-500/40',
      icon: AlertCircle,
    },
    HIGH: {
      label: 'CAO',
      fullLabel: 'CẢNH BÁO (HIGH)',
      bgColor: 'bg-orange-500/20',
      textColor: 'text-orange-400',
      borderColor: 'border-orange-500/50',
      icon: AlertTriangle,
    },
    CRITICAL: {
      label: 'NGUY HIỂM',
      fullLabel: 'KHẨN CẤP (CRITICAL)',
      bgColor: 'bg-red-500/30',
      textColor: 'text-red-400',
      borderColor: 'border-red-500/60',
      icon: Siren,
    }
  }[level];

  const Icon = config.icon;

  const sizeClasses = {
    sm: 'text-xs px-2 py-0.5 gap-1 font-medium',
    md: 'text-sm px-2.5 py-1 gap-1.5 font-semibold',
    lg: 'text-base px-3.5 py-1.5 gap-2 font-bold'
  }[size];

  const iconSizes = {
    sm: 14,
    md: 18,
    lg: 22
  }[size];

  return (
    <div 
      className={`inline-flex items-center rounded-full border ${config.bgColor} ${config.textColor} ${config.borderColor} ${sizeClasses} ${className}`}
      aria-label={`Mức độ rủi ro: ${config.fullLabel}`}
    >
      <Icon size={iconSizes} className={`shrink-0 ${level === 'CRITICAL' ? 'animate-pulse' : ''}`} aria-hidden="true" />
      {showText && <span>{size === 'lg' ? config.fullLabel : config.label}</span>}
    </div>
  );
};
