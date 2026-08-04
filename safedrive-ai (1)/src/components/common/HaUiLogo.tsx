import React from 'react';

interface HaUiLogoProps {
  className?: string;
  size?: 'sm' | 'md' | 'lg';
}

export const HaUiLogo: React.FC<HaUiLogoProps> = ({ className = '', size = 'md' }) => {
  const heightClass = size === 'sm' ? 'h-7' : size === 'lg' ? 'h-12' : 'h-9';

  return (
    <div className={`inline-flex items-center gap-2 select-none ${className}`}>
      {/* Visual rendering matching the HaUI / HVS logo badge */}
      <svg
        viewBox="0 0 320 100"
        fill="none"
        xmlns="http://www.w3.org/2000/svg"
        className={`${heightClass} w-auto drop-shadow-sm`}
      >
        {/* Yellow H */}
        <path d="M15 10 L45 10 L45 42 L75 42 L75 10 L105 10 L105 90 L75 90 L75 62 L45 62 L45 90 L15 90 Z" fill="#EAB308" />
        
        {/* Blue V */}
        <path d="M115 10 L142 10 L160 58 L178 10 L205 10 L172 90 L148 90 Z" fill="#1D4ED8" />
        
        {/* Blue S */}
        <path d="M215 10 L285 10 L285 32 L240 32 L240 45 L285 55 L285 90 L215 90 L215 68 L260 68 L260 55 L215 45 Z" fill="#1D4ED8" />

        {/* HVS Crest Badge Box */}
        <rect x="15" y="70" width="32" height="24" rx="3" fill="#EAB308" stroke="#FFFFFF" strokeWidth="1" />
        <circle cx="31" cy="82" r="7" fill="#DC2626" />
        <path d="M28 82 L31 77 L34 82 L32 82 L32 86 L30 86 L30 82 Z" fill="#FFFFFF" />

        {/* HVS Text */}
        <text x="52" y="89" fill="#1E40AF" fontWeight="900" fontSize="24" fontFamily="sans-serif">
          HVS
        </text>

        {/* Red Vertical Bar Divider */}
        <line x1="125" y1="72" x2="125" y2="92" stroke="#DC2626" strokeWidth="3" strokeLinecap="round" />

        {/* AUTONOMOUS VEHICLE SOLUTIONS Subtitle */}
        <text x="135" y="80" fill="#38BDF8" fontWeight="700" fontSize="10" fontFamily="sans-serif" letterSpacing="1">
          AUTONOMOUS VEHICLE
        </text>
        <text x="135" y="92" fill="#1D4ED8" fontWeight="800" fontSize="10" fontFamily="sans-serif" letterSpacing="1">
          SOLUTIONS
        </text>
      </svg>
    </div>
  );
};
