export type PassengerResponse = 'RESPONSIVE' | 'NO_RESPONSE';

export type RiskLevel = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';

export type ChatSender = 'USER' | 'SAFEDRIVE';

export type ActionType = 
  | 'SHOW_WARNING' 
  | 'OPEN_DIAGNOSTICS' 
  | 'SUGGEST_REST_STOP' 
  | 'START_SOS_COUNTDOWN' 
  | 'NONE';

export interface DtcItem {
  code: string;
  title: string;
  description: string;
  severity: RiskLevel;
  recommendation: string;
}

export interface DriverSupportSignals {
  continuousDrivingMinutes: number | null;
  lastSteeringInteractionMs: number | null;
  steeringSignalAvailable: boolean;
  driverSeatOccupied: boolean | null;
  seatSensorAvailable: boolean;
  wearableConnected: boolean;
  wearableLastUpdateMs: number | null;
  wearableHeartRateBpm: number | null;
  wearablePermissionGranted: boolean;
  userReportedFatigue?: boolean;
}

export type RestRecommendationLevel = 
  | 'INSUFFICIENT_DATA'
  | 'NO_IMMEDIATE_INDICATION'
  | 'MONITOR'
  | 'CONSIDER_REST'
  | 'REST_RECOMMENDED';

export type ConfidenceLevel = 'LOW' | 'MEDIUM' | 'HIGH';

export interface RestRecommendation {
  level: RestRecommendationLevel;
  title: string;
  message: string;
  confidence: ConfidenceLevel;
  availableSourceCount: number;
  totalSourceCount: number;
  reasonCodes: string[];
  updatedAtMs: number;
}

export interface VehicleState {
  speedKmh: number;
  cabinTemperatureC: number;
  engineTemperatureC: number;
  batteryOrFuelPercent: number;
  driverSupportSignals: DriverSupportSignals;
  activeDtcs: DtcItem[];
  crashDetected: boolean;
  passengerResponse: PassengerResponse;
}

export interface RiskAssessment {
  level: RiskLevel;
  title: string;
  message: string;
  reasonCodes: string[];
}

export interface SafeDriveAction {
  id: string;
  type: ActionType;
  title: string;
  requiresConfirmation: boolean;
}

export interface ChatMessage {
  id: string;
  text: string;
  sender: ChatSender;
  timestamp: string;
  risk?: RiskAssessment;
  actions?: SafeDriveAction[];
  route?: string;
  latencyMs?: number;
}

export type NavigationTab = 'cockpit' | 'assistant' | 'diagnostics' | 'settings' | 'simulator' | 'sos';

export type VoiceAssistantState = 
  | 'DISABLED'
  | 'IDLE'
  | 'WAKE_WORD_DETECTED'
  | 'LISTENING'
  | 'PROCESSING'
  | 'SPEAKING'
  | 'ERROR';

export interface ScenarioPreset {
  id: string;
  title: string;
  subtitle: string;
  description: string;
  iconName: string;
  vehicleState: VehicleState;
}

export type EmergencyState = 
  | 'IDLE'
  | 'CANDIDATE_DETECTED'
  | 'VERIFYING_EVIDENCE'
  | 'AWAITING_USER_RESPONSE'
  | 'FINAL_COUNTDOWN'
  | 'SOS_SIMULATED_SENT'
  | 'CANCELLED';

export type SystemConnectionStatus = 
  | 'NORMAL'
  | 'OFFLINE'
  | 'NO_AI_SERVICE'
  | 'NO_VEHICLE_DATA'
  | 'STALE_DATA';

export interface AppSettings {
  backendUrl: string;
  isConnected: boolean;
  ttsEnabled: boolean;
  developerMode: boolean;
  wakeWordEnabled: boolean;
  micPermissionGranted: boolean;
  appVersion: string;
  realEmergencyDispatchEnabled: boolean;
}
