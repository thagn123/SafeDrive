import React, { createContext, useContext, useState, useEffect, useCallback, ReactNode } from 'react';
import { 
  VehicleState, 
  RiskAssessment, 
  ChatMessage, 
  AppSettings, 
  NavigationTab, 
  SafeDriveAction,
  ActionType,
  VoiceAssistantState,
  RestRecommendation,
  EmergencyState,
  SystemConnectionStatus
} from '../types/safedrive';
import { 
  DEFAULT_VEHICLE_STATE, 
  DEFAULT_APP_SETTINGS, 
  INITIAL_CHAT_MESSAGES, 
  SCENARIO_PRESETS, 
  evaluateRisk,
  evaluateRestRecommendation
} from '../data/mock/mockRepository';

interface SafeDriveContextType {
  vehicleState: VehicleState;
  riskAssessment: RiskAssessment;
  restRecommendation: RestRecommendation;
  chatMessages: ChatMessage[];
  settings: AppSettings;
  currentTab: NavigationTab;
  setCurrentTab: (tab: NavigationTab) => void;
  updateVehicleState: (partial: Partial<VehicleState>) => void;
  applyPresetScenario: (presetId: string) => void;
  resetToDefault: () => void;
  updateSettings: (partial: Partial<AppSettings>) => void;
  sendChatMessage: (text: string) => Promise<void>;
  
  // Voice Assistant
  voiceState: VoiceAssistantState;
  voiceTranscript: string;
  voiceResponseText: string;
  triggerWakeWord: () => void;
  cancelVoice: () => void;
  submitVoiceQuery: (text: string) => Promise<void>;
  stopSpeaking: () => void;
  
  // Safety Alert
  isSafetyAlertVisible: boolean;
  dismissSafetyAlert: () => void;
  openSafetyAlertManual: () => void;
  
  // SOS & Emergency State Machine
  isSosModalOpen: boolean;
  openSosModal: () => void;
  closeSosModal: () => void;
  sosConfirmed: boolean;
  confirmSos: () => void;
  cancelSos: () => void;
  
  emergencyState: EmergencyState;
  emergencyDeadlineMs: number | null;
  startEmergencyFlow: () => void;
  cancelEmergency: () => void;
  processEmergencyVoice: (phrase: string) => void;

  // System Status
  systemConnectionStatus: SystemConnectionStatus;

  // Actions & Confirmations
  pendingAction: SafeDriveAction | null;
  executeAction: (action: SafeDriveAction) => void;
  confirmPendingAction: () => void;
  cancelPendingAction: () => void;
  
  // Navigation helpers
  pendingPrompt: string;
  prefillAssistantQuery: (prompt: string) => void;
  clearPendingPrompt: () => void;
  
  // Chat state
  isAssistantThinking: boolean;
}

const SafeDriveContext = createContext<SafeDriveContextType | undefined>(undefined);

export const SafeDriveProvider: React.FC<{ children: ReactNode }> = ({ children }) => {
  const [vehicleState, setVehicleState] = useState<VehicleState>(DEFAULT_VEHICLE_STATE);
  const [riskAssessment, setRiskAssessment] = useState<RiskAssessment>(evaluateRisk(DEFAULT_VEHICLE_STATE));
  const [restRecommendation, setRestRecommendation] = useState<RestRecommendation>(evaluateRestRecommendation(DEFAULT_VEHICLE_STATE.driverSupportSignals));
  const [chatMessages, setChatMessages] = useState<ChatMessage[]>(INITIAL_CHAT_MESSAGES);
  const [settings, setSettings] = useState<AppSettings>(DEFAULT_APP_SETTINGS);
  const [currentTab, setCurrentTab] = useState<NavigationTab>('cockpit');
  
  // Voice Assistant State
  const [voiceState, setVoiceState] = useState<VoiceAssistantState>('IDLE');
  const [voiceTranscript, setVoiceTranscript] = useState<string>('');
  const [voiceResponseText, setVoiceResponseText] = useState<string>('');

  // Alert & SOS State
  const [alertDismissedForLevel, setAlertDismissedForLevel] = useState<string | null>(null);
  const [isManualSafetyAlertOpen, setIsManualSafetyAlertOpen] = useState(false);
  const [isSosModalOpen, setIsSosModalOpen] = useState(false);
  const [sosConfirmed, setSosConfirmed] = useState(false);

  // New Emergency State Machine
  const [emergencyState, setEmergencyState] = useState<EmergencyState>('IDLE');
  const [emergencyDeadlineMs, setEmergencyDeadlineMs] = useState<number | null>(null);

  // Pending Confirmation
  const [pendingAction, setPendingAction] = useState<SafeDriveAction | null>(null);
  
  // Chat Prompt
  const [pendingPrompt, setPendingPrompt] = useState<string>('');
  const [isAssistantThinking, setIsAssistantThinking] = useState<boolean>(false);

  // System Connection Status derived
  const systemConnectionStatus: SystemConnectionStatus = settings.isConnected ? 'NORMAL' : 'OFFLINE';

  // Emergency helper methods
  const cancelEmergency = useCallback(() => {
    setEmergencyState('CANCELLED');
    setEmergencyDeadlineMs(null);
    setIsSosModalOpen(false);
    setSosConfirmed(false);
    setVehicleState(prev => ({
      ...prev,
      crashDetected: false,
      passengerResponse: 'RESPONSIVE'
    }));
  }, []);

  const startEmergencyFlow = useCallback(() => {
    setEmergencyState('VERIFYING_EVIDENCE');
    setEmergencyDeadlineMs(Date.now() + 5000); // 5 seconds verification
    setIsSosModalOpen(true);
    setVehicleState(prev => ({
      ...prev,
      crashDetected: true,
      passengerResponse: 'NO_RESPONSE'
    }));
  }, []);

  const processEmergencyVoice = useCallback((phrase: string) => {
    const lower = phrase.toLowerCase();
    if (
      lower.includes('ổn') || 
      lower.includes('hủy') || 
      lower.includes('không') || 
      lower.includes('ok') || 
      lower.includes('bình thường')
    ) {
      cancelEmergency();
    }
  }, [cancelEmergency]);

  // Recalculate Risk & Rest Recommendation whenever VehicleState changes
  useEffect(() => {
    const newRisk = evaluateRisk(vehicleState);
    const newRest = evaluateRestRecommendation(vehicleState.driverSupportSignals);
    setRiskAssessment(newRisk);
    setRestRecommendation(newRest);
    
    // Auto trigger Emergency State Machine if Crash Detected and IDLE
    if (vehicleState.crashDetected && (emergencyState === 'IDLE' || emergencyState === 'CANCELLED')) {
      startEmergencyFlow();
    }
  }, [vehicleState, emergencyState, startEmergencyFlow]);

  // Emergency State Machine Deadline Timer
  useEffect(() => {
    if (
      emergencyState === 'IDLE' || 
      emergencyState === 'CANCELLED' || 
      emergencyState === 'SOS_SIMULATED_SENT' ||
      !emergencyDeadlineMs
    ) {
      return;
    }

    const timer = setInterval(() => {
      const now = Date.now();
      if (now >= emergencyDeadlineMs) {
        if (emergencyState === 'VERIFYING_EVIDENCE') {
          setEmergencyState('AWAITING_USER_RESPONSE');
          setEmergencyDeadlineMs(now + 15000); // 15 seconds
        } else if (emergencyState === 'AWAITING_USER_RESPONSE') {
          setEmergencyState('FINAL_COUNTDOWN');
          setEmergencyDeadlineMs(now + 10000); // 10 seconds
        } else if (emergencyState === 'FINAL_COUNTDOWN') {
          setEmergencyState('SOS_SIMULATED_SENT');
          setEmergencyDeadlineMs(null);
          setSosConfirmed(true);
        }
      }
    }, 200);

    return () => clearInterval(timer);
  }, [emergencyState, emergencyDeadlineMs]);

  // Sync VoiceState with Settings
  useEffect(() => {
    if (!settings.wakeWordEnabled && voiceState !== 'DISABLED') {
      setVoiceState('DISABLED');
    } else if (settings.wakeWordEnabled && voiceState === 'DISABLED') {
      setVoiceState('IDLE');
    }
  }, [settings.wakeWordEnabled, voiceState]);

  // Determine if safety alert should show
  const isSafetyAlertVisible = 
    isManualSafetyAlertOpen || 
    ((riskAssessment.level === 'HIGH' || riskAssessment.level === 'CRITICAL') && 
     alertDismissedForLevel !== `${riskAssessment.level}_${riskAssessment.title}`);

  const dismissSafetyAlert = useCallback(() => {
    setAlertDismissedForLevel(`${riskAssessment.level}_${riskAssessment.title}`);
    setIsManualSafetyAlertOpen(false);
  }, [riskAssessment]);

  const openSafetyAlertManual = useCallback(() => {
    setIsManualSafetyAlertOpen(true);
  }, []);

  const updateVehicleState = useCallback((partial: Partial<VehicleState>) => {
    setVehicleState(prev => {
      const next = { ...prev, ...partial };
      return next;
    });
    setAlertDismissedForLevel(null);
  }, []);

  const applyPresetScenario = useCallback((presetId: string) => {
    const preset = SCENARIO_PRESETS.find(p => p.id === presetId);
    if (preset) {
      setVehicleState(preset.vehicleState);
      setAlertDismissedForLevel(null);
      setSosConfirmed(false);
      if (preset.id === 'crash') {
        setIsSosModalOpen(true);
      } else {
        setIsSosModalOpen(false);
      }
    }
  }, []);

  const resetToDefault = useCallback(() => {
    setVehicleState(DEFAULT_VEHICLE_STATE);
    setAlertDismissedForLevel(null);
    setIsSosModalOpen(false);
    setSosConfirmed(false);
  }, []);

  const updateSettings = useCallback((partial: Partial<AppSettings>) => {
    setSettings(prev => ({ ...prev, ...partial }));
  }, []);

  const prefillAssistantQuery = useCallback((prompt: string) => {
    setPendingPrompt(prompt);
    setCurrentTab('assistant');
  }, []);

  const clearPendingPrompt = useCallback(() => {
    setPendingPrompt('');
  }, []);

  // Action Executor
  const executeAction = useCallback((action: SafeDriveAction) => {
    if (action.requiresConfirmation) {
      setPendingAction(action);
      return;
    }

    performActionType(action.type);
  }, []);

  const performActionType = (type: ActionType) => {
    switch (type) {
      case 'SHOW_WARNING':
        setIsManualSafetyAlertOpen(true);
        break;
      case 'OPEN_DIAGNOSTICS':
        setCurrentTab('diagnostics');
        break;
      case 'SUGGEST_REST_STOP':
        setCurrentTab('cockpit');
        break;
      case 'START_SOS_COUNTDOWN':
        setIsSosModalOpen(true);
        break;
      case 'NONE':
      default:
        break;
    }
  };

  const confirmPendingAction = useCallback(() => {
    if (pendingAction) {
      performActionType(pendingAction.type);
      setPendingAction(null);
    }
  }, [pendingAction]);

  const cancelPendingAction = useCallback(() => {
    setPendingAction(null);
  }, []);

  // SOS Modal Controls
  const openSosModal = useCallback(() => {
    setIsSosModalOpen(true);
  }, []);

  const closeSosModal = useCallback(() => {
    setIsSosModalOpen(false);
  }, []);

  const confirmSos = useCallback(() => {
    setSosConfirmed(true);
  }, []);

  const cancelSos = useCallback(() => {
    setIsSosModalOpen(false);
    setSosConfirmed(false);
  }, []);

  // TTS Speech Synthesis helper
  const speakText = useCallback((text: string) => {
    if (!settings.ttsEnabled || typeof window === 'undefined' || !('speechSynthesis' in window)) return;
    try {
      window.speechSynthesis.cancel();
      const utterance = new SpeechSynthesisUtterance(text);
      utterance.lang = 'vi-VN';
      utterance.rate = 1.0;
      window.speechSynthesis.speak(utterance);
    } catch {
      // Ignore audio synthesis errors
    }
  }, [settings.ttsEnabled]);

  // Voice Assistant Flow
  const triggerWakeWord = useCallback(() => {
    if (!settings.wakeWordEnabled) {
      setVoiceState('DISABLED');
      return;
    }
    setVoiceState('WAKE_WORD_DETECTED');
    setVoiceTranscript('');
    setVoiceResponseText('');

    // Play subtle audio cue or proceed to LISTENING
    setTimeout(() => {
      setVoiceState('LISTENING');
    }, 600);
  }, [settings.wakeWordEnabled]);

  const cancelVoice = useCallback(() => {
    if (typeof window !== 'undefined' && 'speechSynthesis' in window) {
      window.speechSynthesis.cancel();
    }
    setVoiceState(settings.wakeWordEnabled ? 'IDLE' : 'DISABLED');
    setVoiceTranscript('');
    setVoiceResponseText('');
  }, [settings.wakeWordEnabled]);

  const stopSpeaking = useCallback(() => {
    if (typeof window !== 'undefined' && 'speechSynthesis' in window) {
      window.speechSynthesis.cancel();
    }
    setVoiceState(settings.wakeWordEnabled ? 'IDLE' : 'DISABLED');
  }, [settings.wakeWordEnabled]);

  // Mock Intelligent Backend Response
  const sendChatMessage = useCallback(async (userText: string) => {
    const userMsg: ChatMessage = {
      id: `msg_user_${Date.now()}`,
      sender: 'USER',
      text: userText,
      timestamp: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
    };

    setChatMessages(prev => [...prev, userMsg]);
    setIsAssistantThinking(true);

    const latencyMs = Math.floor(Math.random() * 200) + 240;
    
    setTimeout(() => {
      let replyText = '';
      let replyRisk: RiskAssessment | undefined = evaluateRisk(vehicleState);
      let replyActions: SafeDriveAction[] = [];
      let route = 'safety_fast_path';

      const lower = userText.toLowerCase();

      if (!settings.isConnected) {
        replyText = 'Không thể kết nối với máy chủ SafeDrive AI. Các cảnh báo an toàn và chẩn đoán nội bộ trên xe vẫn đang hoạt động bình thường.';
        route = 'offline_fallback';
      } else if (lower.includes('nhiệt độ') || lower.includes('nhiệt')) {
        replyText = `Nhiệt độ động cơ hiện tại là ${vehicleState.engineTemperatureC}°C và nhiệt độ cabin là ${vehicleState.cabinTemperatureC}°C. `;
        if (vehicleState.engineTemperatureC >= 108) {
          replyText += 'Động cơ đang ở mức quá nhiệt! Vui lòng giảm tốc độ và kiểm tra hệ thống làm mát.';
          replyActions.push({
            id: 'act_overheat',
            type: 'SHOW_WARNING',
            title: 'Mở cảnh báo quá nhiệt',
            requiresConfirmation: false
          });
        } else {
          replyText += 'Hệ thống làm mát vẫn làm việc bình thường.';
        }
      } else if (lower.includes('lỗi') || lower.includes('dtc') || lower.includes('xe bị gì') || lower.includes('p0301')) {
        if (vehicleState.activeDtcs.length > 0) {
          const dtcList = vehicleState.activeDtcs.map(d => `${d.code}: ${d.title}`).join('; ');
          replyText = `Hệ thống ghi nhận ${vehicleState.activeDtcs.length} mã lỗi: ${dtcList}. Khuyên bạn nên đưa xe đến trạm dịch vụ chuyên nghiệp.`;
          replyActions.push({
            id: 'act_dtc_nav',
            type: 'OPEN_DIAGNOSTICS',
            title: 'Xem chi tiết chẩn đoán',
            requiresConfirmation: false
          });
        } else {
          replyText = 'Hiện tại không phát hiện bất kỳ mã lỗi chẩn đoán DTC nào trên hệ thống điều khiển xe.';
        }
      } else if (lower.includes('buồn ngủ') || lower.includes('mệt') || lower.includes('lái lâu') || lower.includes('nghỉ')) {
        const mins = vehicleState.driverSupportSignals.continuousDrivingMinutes;
        const timeStr = mins ? `${Math.floor(mins / 60)} giờ ${mins % 60} phút` : 'chưa xác định';
        replyText = `Thời gian lái xe liên tục hiện tại: ${timeStr}. Dựa trên các tín hiệu hỗ trợ gián tiếp (vô lăng, ghế lái), SafeDrive khuyên bạn nên cân nhắc dừng nghỉ tại vị trí an toàn.`;
        replyActions.push({
          id: 'act_rest_stop',
          type: 'SUGGEST_REST_STOP',
          title: 'Đề xuất dừng nghỉ',
          requiresConfirmation: true
        });
      } else if (lower.includes('sos') || lower.includes('va chạm') || lower.includes('cứu hộ')) {
        replyText = 'Đang kiểm tra tình trạng an toàn khẩn cấp. Phát hiện yêu cầu trợ giúp cứu hộ!';
        replyActions.push({
          id: 'act_sos_start',
          type: 'START_SOS_COUNTDOWN',
          title: 'Mở đếm ngược SOS mô phỏng',
          requiresConfirmation: true
        });
      } else if (lower.includes('tốc độ') || lower.includes('vận tốc')) {
        replyText = `Xe đang di chuyển với vận tốc ${vehicleState.speedKmh} km/h. Giới hạn an toàn trên tuyến đường là 80 km/h.`;
      } else if (lower.includes('pin') || lower.includes('nhiên liệu')) {
        replyText = `Mức năng lượng/nhiên liệu còn lại: ${vehicleState.batteryOrFuelPercent}%. Ước tính còn di chuyển được khoảng ${Math.round(vehicleState.batteryOrFuelPercent * 4.2)} km.`;
      } else {
        const mins = vehicleState.driverSupportSignals.continuousDrivingMinutes;
        const timeStr = mins ? `${Math.floor(mins / 60)} giờ ${mins % 60} phút` : 'chưa xác định';
        replyText = `Tôi đã nhận câu hỏi: "${userText}". Các thông số vận hành xe (Tốc độ: ${vehicleState.speedKmh}km/h, Thời gian lái liên tục: ${timeStr}) đang được tổng hợp và theo dõi theo thời gian thực.`;
      }

      const botMsg: ChatMessage = {
        id: `msg_bot_${Date.now()}`,
        sender: 'SAFEDRIVE',
        text: replyText,
        timestamp: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }),
        risk: replyRisk,
        actions: replyActions,
        route,
        latencyMs
      };

      setChatMessages(prev => [...prev, botMsg]);
      setIsAssistantThinking(false);
      speakText(replyText);

      // Return text for voice state machine if active
      setVoiceResponseText(replyText);
    }, latencyMs);
  }, [vehicleState, settings.isConnected, speakText]);

  const submitVoiceQuery = useCallback(async (text: string) => {
    setVoiceTranscript(text);
    setVoiceState('PROCESSING');
    await sendChatMessage(text);
    setVoiceState('SPEAKING');
  }, [sendChatMessage]);

  return (
    <SafeDriveContext.Provider
      value={{
        vehicleState,
        riskAssessment,
        restRecommendation,
        chatMessages,
        settings,
        currentTab,
        setCurrentTab,
        updateVehicleState,
        applyPresetScenario,
        resetToDefault,
        updateSettings,
        sendChatMessage,
        voiceState,
        voiceTranscript,
        voiceResponseText,
        triggerWakeWord,
        cancelVoice,
        submitVoiceQuery,
        stopSpeaking,
        isSafetyAlertVisible,
        dismissSafetyAlert,
        openSafetyAlertManual,
        isSosModalOpen,
        openSosModal,
        closeSosModal,
        sosConfirmed,
        confirmSos,
        cancelSos,
        emergencyState,
        emergencyDeadlineMs,
        startEmergencyFlow,
        cancelEmergency,
        processEmergencyVoice,
        systemConnectionStatus,
        pendingAction,
        executeAction,
        confirmPendingAction,
        cancelPendingAction,
        pendingPrompt,
        prefillAssistantQuery,
        clearPendingPrompt,
        isAssistantThinking
      }}
    >
      {children}
    </SafeDriveContext.Provider>
  );
};

export const useSafeDrive = () => {
  const context = useContext(SafeDriveContext);
  if (!context) {
    throw new Error('useSafeDrive must be used within a SafeDriveProvider');
  }
  return context;
};

