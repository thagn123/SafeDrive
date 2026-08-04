import React from 'react';
import { ScenarioPreset } from '../../types/safedrive';
import { ShieldCheck, EyeOff, Flame, Wrench, Siren, Check } from 'lucide-react';

interface ScenarioPresetCardProps {
  preset: ScenarioPreset;
  isSelected: boolean;
  onSelect: () => void;
}

export const ScenarioPresetCard: React.FC<ScenarioPresetCardProps> = ({
  preset,
  isSelected,
  onSelect
}) => {
  const getIcon = (iconName: string) => {
    switch (iconName) {
      case 'ShieldCheck': return ShieldCheck;
      case 'EyeOff': return EyeOff;
      case 'Flame': return Flame;
      case 'Wrench': return Wrench;
      case 'Siren': return Siren;
      default: return ShieldCheck;
    }
  };

  const IconComponent = getIcon(preset.iconName);

  return (
    <button
      onClick={onSelect}
      type="button"
      className={`p-4 rounded-2xl border text-left transition-all relative flex flex-col justify-between space-y-2 ${
        isSelected 
          ? 'bg-gradient-to-br from-[#132437] to-cyan-950/40 border-cyan-400 shadow-lg shadow-cyan-500/10 ring-2 ring-cyan-400/30' 
          : 'bg-[#132437] border-slate-800 hover:border-slate-700 hover:bg-[#182C42]'
      }`}
    >
      <div className="flex items-start justify-between gap-2">
        <div className={`p-2.5 rounded-xl border ${
          isSelected 
            ? 'bg-cyan-500 text-slate-950 border-cyan-300' 
            : 'bg-slate-800 text-cyan-400 border-slate-700'
        }`}>
          <IconComponent size={20} />
        </div>

        {isSelected && (
          <div className="p-1 rounded-full bg-cyan-400 text-slate-950">
            <Check size={14} />
          </div>
        )}
      </div>

      <div>
        <h4 className="text-sm font-bold text-white">{preset.title}</h4>
        <p className="text-xs font-semibold text-cyan-400 mt-0.5">{preset.subtitle}</p>
        <p className="text-xs text-slate-400 mt-1 line-clamp-2 leading-relaxed">{preset.description}</p>
      </div>
    </button>
  );
};
