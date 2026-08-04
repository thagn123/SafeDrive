import React, { useState } from 'react';
import { useSafeDrive } from '../../context/SafeDriveContext';
import { CompactAppHeader } from './CompactAppHeader';
import { StatusHeroCard } from './StatusHeroCard';
import { VehicleOverviewPanel } from './VehicleOverviewPanel';
import { DriverSignalSummary } from './DriverSignalSummary';
import { DtcSummaryCard } from './DtcSummaryCard';
import { VoiceAssistantStatusCard } from './VoiceAssistantStatusCard';
import { DriverSupportDetailsModal } from './DriverSupportDetailsModal';

export const ResponsiveCockpitLayout: React.FC = () => {
  const { 
    vehicleState, 
    riskAssessment, 
    restRecommendation,
    settings, 
    setCurrentTab, 
    updateSettings,
    openSafetyAlertManual,
    triggerWakeWord,
    voiceState
  } = useSafeDrive();

  const [isDetailsModalOpen, setIsDetailsModalOpen] = useState(false);

  return (
    <div className="w-full h-[calc(100vh-84px)] max-h-[780px] mx-auto p-1.5 sm:p-2.5 overflow-hidden select-none flex flex-col justify-between">
      {/* HEADER BAR */}
      <div className="shrink-0 mb-1.5 sm:mb-2">
        <CompactAppHeader 
          isConnected={settings.isConnected}
          onToggleConnection={() => updateSettings({ isConnected: !settings.isConnected })}
        />
      </div>

      {/* 12-COLUMN CSS GRID LAYOUT - FIT TO VIEWPORT WITHOUT VERTICAL SCROLLING */}
      <div className="grid grid-cols-12 gap-1.5 sm:gap-2 flex-1 min-h-0 overflow-hidden items-stretch">
        {/* Status Hero Card (Col 1-12 on mobile, Col 1-7 on md/lg/landscape) */}
        <div className="col-span-12 md:col-span-7 flex flex-col justify-stretch min-h-0">
          <StatusHeroCard 
            risk={riskAssessment}
            restRecommendation={restRecommendation}
            activeSourceCount={3}
            totalSourceCount={4}
            onOpenDetails={() => setIsDetailsModalOpen(true)}
            onOpenSafetyAlert={openSafetyAlertManual}
          />
        </div>

        {/* Vehicle Overview Panel (Col 1-12 on mobile, Col 8-12 on md/lg/landscape) */}
        <div className="col-span-12 md:col-span-5 flex flex-col justify-stretch min-h-0">
          <VehicleOverviewPanel 
            speedKmh={vehicleState.speedKmh}
            engineTemperatureC={vehicleState.engineTemperatureC}
            energyPercent={vehicleState.batteryOrFuelPercent ?? 74}
            continuousDrivingMinutes={vehicleState.driverSupportSignals.continuousDrivingMinutes}
            cabinTemperatureC={vehicleState.cabinTemperatureC}
            onMetricClick={() => setIsDetailsModalOpen(true)}
          />
        </div>

        {/* Driver Signal Summary Strip (Col 1-12 on mobile, Col 1-6 on md/lg/landscape) */}
        <div className="col-span-12 md:col-span-6 flex flex-col justify-stretch min-h-0">
          <DriverSignalSummary 
            signals={vehicleState.driverSupportSignals}
            onOpenDetails={() => setIsDetailsModalOpen(true)}
          />
        </div>

        {/* DTC Summary Card (Col 1-6 on mobile, Col 7-9 on md/lg/landscape) */}
        <div className="col-span-6 md:col-span-3 flex flex-col justify-stretch min-h-0">
          <DtcSummaryCard 
            dtcs={vehicleState.activeDtcs}
            onOpenDiagnostics={() => setCurrentTab('diagnostics')}
          />
        </div>

        {/* Voice Assistant Status Card (Col 7-12 on mobile, Col 10-12 on md/lg/landscape) */}
        <div className="col-span-6 md:col-span-3 flex flex-col justify-stretch min-h-0">
          <VoiceAssistantStatusCard 
            voiceState={voiceState}
            onTriggerVoice={triggerWakeWord}
          />
        </div>
      </div>

      {/* Driver Support Signals Detail Modal */}
      <DriverSupportDetailsModal 
        isOpen={isDetailsModalOpen}
        onClose={() => setIsDetailsModalOpen(false)}
        signals={vehicleState.driverSupportSignals}
        restRecommendation={restRecommendation}
      />
    </div>
  );
};
