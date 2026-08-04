import React from 'react';
import { SafeDriveProvider, useSafeDrive } from './context/SafeDriveContext';
import { CockpitScreen } from './presentation/CockpitScreen';
import { AssistantScreen } from './presentation/AssistantScreen';
import { DiagnosticsScreen } from './presentation/DiagnosticsScreen';
import { SettingsScreen } from './presentation/SettingsScreen';
import { SimulatorScreen } from './presentation/SimulatorScreen';
import { SosScreen } from './presentation/SosScreen';
import { SafetyAlertOverlay } from './presentation/SafetyAlertOverlay';
import { EmergencyOverlay } from './components/sos/EmergencyOverlay';
import { ConfirmActionDialog } from './components/common/ConfirmActionDialog';
import { BottomNavBar } from './navigation/BottomNavBar';
import { VoiceOverlay } from './components/assistant/VoiceOverlay';

const MainLayout: React.FC = () => {
  const { 
    currentTab, 
    pendingAction, 
    confirmPendingAction, 
    cancelPendingAction 
  } = useSafeDrive();

  const renderScreen = () => {
    switch (currentTab) {
      case 'cockpit':
        return <CockpitScreen />;
      case 'assistant':
        return <AssistantScreen />;
      case 'diagnostics':
        return <DiagnosticsScreen />;
      case 'settings':
        return <SettingsScreen />;
      case 'simulator':
        return <SimulatorScreen />;
      case 'sos':
        return <SosScreen />;
      default:
        return <CockpitScreen />;
    }
  };

  return (
    <div className="min-h-screen bg-[#08131F] text-slate-100 font-sans selection:bg-cyan-500 selection:text-slate-950">
      {/* Centered Mobile/Automotive Frame container */}
      <div className="max-w-4xl mx-auto px-4 pt-4 sm:pt-6">
        {renderScreen()}
      </div>

      {/* Global Emergency Overlay State Machine */}
      <EmergencyOverlay />

      {/* Global Safety Alert Overlay */}
      <SafetyAlertOverlay />

      {/* Global Voice Assistant Overlay */}
      <VoiceOverlay />

      {/* Global Confirmation Modal */}
      <ConfirmActionDialog
        action={pendingAction}
        onConfirm={confirmPendingAction}
        onCancel={cancelPendingAction}
      />

      {/* Navigation Bar */}
      <BottomNavBar />
    </div>
  );
};

export default function App() {
  return (
    <SafeDriveProvider>
      <MainLayout />
    </SafeDriveProvider>
  );
}
