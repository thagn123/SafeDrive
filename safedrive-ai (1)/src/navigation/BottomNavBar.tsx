import React from 'react';
import { useSafeDrive } from '../context/SafeDriveContext';
import { NavigationTab } from '../types/safedrive';
import { LayoutDashboard, MessageSquareText, Wrench, Settings } from 'lucide-react';

export const BottomNavBar: React.FC = () => {
  const { currentTab, setCurrentTab, riskAssessment, vehicleState, emergencyState, settings } = useSafeDrive();

  // Hide Bottom Navigation completely during active SOS / Emergency flow
  if (emergencyState !== 'IDLE' && emergencyState !== 'CANCELLED') {
    return null;
  }

  const tabs: { id: NavigationTab; label: string; icon: React.ElementType; badge?: boolean }[] = [
    { 
      id: 'cockpit', 
      label: 'Cockpit', 
      icon: LayoutDashboard,
      badge: riskAssessment.level === 'HIGH' || riskAssessment.level === 'CRITICAL'
    },
    { 
      id: 'assistant', 
      label: 'Trợ lý AI', 
      icon: MessageSquareText 
    },
    { 
      id: 'diagnostics', 
      label: 'Chẩn đoán', 
      icon: Wrench,
      badge: vehicleState.activeDtcs.length > 0
    },
    { 
      id: 'settings', 
      label: 'Cài đặt', 
      icon: Settings 
    },
  ];

  return (
    <nav 
      aria-label="Điều hướng chính"
      className="fixed bottom-0 left-0 right-0 z-40 bg-[#0B1220]/95 backdrop-blur-lg border-t border-slate-800 px-2 py-2 max-w-4xl mx-auto shadow-2xl"
    >
      <div className="flex items-center justify-around">
        {tabs.map((tab) => {
          const Icon = tab.icon;
          const isActive = currentTab === tab.id;

          return (
            <button
              key={tab.id}
              onClick={() => setCurrentTab(tab.id)}
              type="button"
              className={`relative flex flex-col items-center justify-center min-h-[52px] px-3 py-1.5 rounded-2xl transition-all duration-200 flex-1 max-w-[100px] ${
                isActive 
                  ? 'text-cyan-400 bg-cyan-500/10 font-bold' 
                  : 'text-slate-400 hover:text-slate-200 hover:bg-slate-800/50 font-medium'
              }`}
            >
              <div className="relative">
                <Icon size={22} />
                {tab.badge && (
                  <span className="absolute -top-1 -right-1.5 flex h-2.5 w-2.5">
                    <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-orange-400 opacity-75"></span>
                    <span className="relative inline-flex rounded-full h-2.5 w-2.5 bg-orange-500"></span>
                  </span>
                )}
              </div>

              <span className="text-[11px] mt-1 font-sans tracking-tight">
                {tab.label}
              </span>

              {isActive && (
                <div className="absolute bottom-1 w-5 h-1 bg-cyan-400 rounded-full" />
              )}
            </button>
          );
        })}
      </div>
    </nav>
  );
};
