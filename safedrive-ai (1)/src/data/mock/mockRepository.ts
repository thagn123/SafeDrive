import { 
  VehicleState, 
  RiskAssessment, 
  DtcItem, 
  ScenarioPreset,
  AppSettings,
  DriverSupportSignals,
  RestRecommendation,
  ConfidenceLevel
} from '../../types/safedrive';

export const DTC_P0301: DtcItem = {
  code: 'P0301',
  title: 'Lỗi đánh lửa xi-lanh số 1',
  description: 'Hệ thống OBD-II phát hiện hiện tượng bỏ lửa (misfire) liên tục ở xi-lanh số 1 động cơ.',
  severity: 'MEDIUM',
  recommendation: 'Giảm tốc độ, tránh tăng tốc mạnh và di chuyển xe đến trạm dịch vụ gần nhất để kiểm tra bugi/cuộn cao áp.'
};

export const DTC_OVERHEAT: DtcItem = {
  code: 'ENGINE_OVERHEAT',
  title: 'Cảnh báo nhiệt độ động cơ quá cao',
  description: 'Nhiệt độ dung dịch làm mát động cơ vượt quá ngưỡng cho phép (110°C).',
  severity: 'HIGH',
  recommendation: 'Bật quạt sưởi cabin để hỗ trợ xả nhiệt, tấp vào vị trí an toàn và tắt máy chờ động cơ hạ nhiệt.'
};

export const DEFAULT_DRIVER_SIGNALS: DriverSupportSignals = {
  continuousDrivingMinutes: 135, // 2h 15m
  lastSteeringInteractionMs: Date.now() - 12000, // 12 seconds ago
  steeringSignalAvailable: true,
  driverSeatOccupied: true,
  seatSensorAvailable: true,
  wearableConnected: false,
  wearableLastUpdateMs: null,
  wearableHeartRateBpm: null,
  wearablePermissionGranted: false,
  userReportedFatigue: false
};

export const DEFAULT_VEHICLE_STATE: VehicleState = {
  speedKmh: 62,
  cabinTemperatureC: 25,
  engineTemperatureC: 92,
  batteryOrFuelPercent: 74,
  driverSupportSignals: DEFAULT_DRIVER_SIGNALS,
  activeDtcs: [],
  crashDetected: false,
  passengerResponse: 'RESPONSIVE',
};

export const DEFAULT_APP_SETTINGS: AppSettings = {
  backendUrl: 'http://10.0.2.2:8000/',
  isConnected: true,
  ttsEnabled: true,
  developerMode: false,
  wakeWordEnabled: true,
  micPermissionGranted: true,
  appVersion: 'SafeDrive AI v1.2.0 (HVS - HaUI Automotive)',
  realEmergencyDispatchEnabled: false
};

/**
 * Logic mock đánh giá nhu cầu nghỉ dựa trên các tín hiệu gián tiếp.
 */
export function evaluateRestRecommendation(signals: DriverSupportSignals): RestRecommendation {
  let availableCount = 0;
  if (signals.continuousDrivingMinutes !== null) availableCount++;
  if (signals.steeringSignalAvailable) availableCount++;
  if (signals.seatSensorAvailable) availableCount++;
  if (signals.wearableConnected) availableCount++;

  const reasons: string[] = [];

  const now = Date.now();
  const wearableStale = signals.wearableConnected && 
    signals.wearableLastUpdateMs !== null && 
    (now - signals.wearableLastUpdateMs > 120000);

  if (!signals.steeringSignalAvailable) reasons.push('steering_signal_unavailable');
  if (!signals.seatSensorAvailable) reasons.push('seat_sensor_unavailable');
  if (!signals.wearableConnected) reasons.push('wearable_not_connected');
  if (wearableStale) reasons.push('wearable_data_stale');

  // Confidence level rule:
  // 0-1 source -> LOW
  // 2-3 sources -> MEDIUM
  // 4 sources & fresh -> HIGH
  let confidence: ConfidenceLevel = 'LOW';
  if (availableCount <= 1) {
    confidence = 'LOW';
    if (!reasons.includes('insufficient_signal_sources')) {
      reasons.push('insufficient_signal_sources');
    }
  } else if (availableCount <= 3) {
    confidence = 'MEDIUM';
  } else if (availableCount === 4 && !wearableStale) {
    confidence = 'HIGH';
  } else {
    confidence = 'MEDIUM';
  }

  const mins = signals.continuousDrivingMinutes;

  if (signals.userReportedFatigue) {
    reasons.push('user_reported_fatigue');
    return {
      level: 'REST_RECOMMENDED',
      title: 'Khuyến nghị dừng nghỉ',
      message: 'Bạn vừa cho biết mình đang mệt. SafeDrive khuyến nghị dừng nghỉ tại vị trí an toàn.',
      confidence,
      availableSourceCount: availableCount,
      totalSourceCount: 4,
      reasonCodes: reasons,
      updatedAtMs: now
    };
  }

  if (mins !== null && mins >= 240) {
    reasons.push('continuous_driving_over_4h');
    return {
      level: 'REST_RECOMMENDED',
      title: 'Khuyến nghị dừng nghỉ',
      message: 'Bạn đã lái xe liên tục hơn 4 giờ. SafeDrive khuyến nghị dừng nghỉ tại vị trí an toàn.',
      confidence,
      availableSourceCount: availableCount,
      totalSourceCount: 4,
      reasonCodes: reasons,
      updatedAtMs: now
    };
  }

  if (mins !== null && mins >= 180) {
    reasons.push('continuous_driving_over_3h');
    return {
      level: 'CONSIDER_REST',
      title: 'Nên cân nhắc nghỉ',
      message: 'Bạn đã lái xe liên tục trong thời gian dài. Hãy cân nhắc nghỉ tại vị trí phù hợp.',
      confidence,
      availableSourceCount: availableCount,
      totalSourceCount: 4,
      reasonCodes: reasons,
      updatedAtMs: now
    };
  }

  if ((mins !== null && mins >= 120) || wearableStale) {
    if (mins !== null && mins >= 120) reasons.push('continuous_driving_over_2h');
    return {
      level: 'MONITOR',
      title: 'Nên theo dõi',
      message: 'Thời gian lái liên tục đang tăng. Hãy theo dõi tình trạng của bạn.',
      confidence,
      availableSourceCount: availableCount,
      totalSourceCount: 4,
      reasonCodes: reasons,
      updatedAtMs: now
    };
  }

  if (availableCount <= 1 || mins === null) {
    return {
      level: 'INSUFFICIENT_DATA',
      title: 'Chưa đủ dữ liệu',
      message: 'Chưa đủ dữ liệu để đưa ra khuyến nghị.',
      confidence: 'LOW',
      availableSourceCount: availableCount,
      totalSourceCount: 4,
      reasonCodes: reasons,
      updatedAtMs: now
    };
  }

  return {
    level: 'NO_IMMEDIATE_INDICATION',
    title: 'Chưa ghi nhận dấu hiệu cần nghỉ',
    message: 'Chưa ghi nhận tín hiệu cho thấy cần nghỉ ngay.',
    confidence,
    availableSourceCount: availableCount,
    totalSourceCount: 4,
    reasonCodes: ['system_nominal'],
    updatedAtMs: now
  };
}

export function evaluateRisk(state: VehicleState): RiskAssessment {
  if (state.crashDetected) {
    return {
      level: 'CRITICAL',
      title: 'Phát hiện va chạm!',
      message: state.passengerResponse === 'NO_RESPONSE'
        ? 'Cảnh báo va chạm nghiêm trọng: Người trong xe không phản hồi. Kích hoạt đếm ngược SOS khẩn cấp.'
        : 'Phát hiện va chạm xe. Vui lòng xác nhận tình trạng an toàn của bạn.',
      reasonCodes: ['crash_detected', ...(state.passengerResponse === 'NO_RESPONSE' ? ['passenger_no_response'] : [])]
    };
  }

  const restRec = evaluateRestRecommendation(state.driverSupportSignals);

  if (restRec.level === 'REST_RECOMMENDED') {
    return {
      level: 'HIGH',
      title: 'Khuyến nghị dừng nghỉ',
      message: restRec.message,
      reasonCodes: restRec.reasonCodes
    };
  }

  if (state.engineTemperatureC >= 108) {
    return {
      level: 'HIGH',
      title: 'Động cơ quá nhiệt',
      message: `Nhiệt độ động cơ vượt ngưỡng cao (${state.engineTemperatureC}°C). Hãy giảm tải và dừng tại nơi an toàn.`,
      reasonCodes: ['engine_temperature_high', 'cooling_system_warning']
    };
  }

  if (state.activeDtcs.length > 0) {
    const hasHighDtc = state.activeDtcs.some(d => d.severity === 'HIGH');
    return {
      level: hasHighDtc ? 'HIGH' : 'MEDIUM',
      title: hasHighDtc ? 'Cảnh báo lỗi kỹ thuật' : 'Lỗi chẩn đoán DTC',
      message: `Phát hiện ${state.activeDtcs.length} mã lỗi cần theo dõi: ${state.activeDtcs.map(d => d.code).join(', ')}.`,
      reasonCodes: state.activeDtcs.map(d => `dtc_active_${d.code.toLowerCase()}`)
    };
  }

  if (restRec.level === 'CONSIDER_REST' || restRec.level === 'MONITOR') {
    return {
      level: 'MEDIUM',
      title: restRec.title,
      message: restRec.message,
      reasonCodes: restRec.reasonCodes
    };
  }

  return {
    level: 'LOW',
    title: 'Mức độ an toàn: THẤP',
    message: 'Xe và hành trình đang ở trạng thái ổn định.',
    reasonCodes: ['system_nominal']
  };
}

export const SCENARIO_PRESETS: ScenarioPreset[] = [
  {
    id: 'new_trip',
    title: '1. Hành trình mới',
    subtitle: 'Nội dung: 20 phút lái',
    description: 'Lái xe 20 phút, 3/4 nguồn tín hiệu hợp lệ. Chưa ghi nhận dấu hiệu cần nghỉ.',
    iconName: 'ShieldCheck',
    vehicleState: {
      ...DEFAULT_VEHICLE_STATE,
      driverSupportSignals: {
        continuousDrivingMinutes: 20,
        lastSteeringInteractionMs: Date.now() - 5000,
        steeringSignalAvailable: true,
        driverSeatOccupied: true,
        seatSensorAvailable: true,
        wearableConnected: false,
        wearableLastUpdateMs: null,
        wearableHeartRateBpm: null,
        wearablePermissionGranted: false,
        userReportedFatigue: false
      }
    }
  },
  {
    id: 'over_2h',
    title: '2. Đã lái hơn 2 giờ',
    subtitle: 'Lái 135 phút (MONITOR)',
    description: 'Thời gian lái 135 phút. Khuyến nghị theo dõi tình trạng của bạn.',
    iconName: 'Clock',
    vehicleState: {
      ...DEFAULT_VEHICLE_STATE,
      driverSupportSignals: {
        continuousDrivingMinutes: 135,
        lastSteeringInteractionMs: Date.now() - 15000,
        steeringSignalAvailable: true,
        driverSeatOccupied: true,
        seatSensorAvailable: true,
        wearableConnected: true,
        wearableLastUpdateMs: Date.now() - 20000,
        wearableHeartRateBpm: 76,
        wearablePermissionGranted: true,
        userReportedFatigue: false
      }
    }
  },
  {
    id: 'consider_rest',
    title: '3. Nên cân nhắc nghỉ',
    subtitle: 'Lái 200 phút (CONSIDER_REST)',
    description: 'Đã lái xe liên tục 200 phút (3h20m). Khuyên bạn nên cân nhắc dừng nghỉ.',
    iconName: 'Coffee',
    vehicleState: {
      ...DEFAULT_VEHICLE_STATE,
      driverSupportSignals: {
        continuousDrivingMinutes: 200,
        lastSteeringInteractionMs: Date.now() - 25000,
        steeringSignalAvailable: true,
        driverSeatOccupied: true,
        seatSensorAvailable: true,
        wearableConnected: true,
        wearableLastUpdateMs: Date.now() - 10000,
        wearableHeartRateBpm: 72,
        wearablePermissionGranted: true,
        userReportedFatigue: false
      }
    }
  },
  {
    id: 'rest_recommended',
    title: '4. Đã lái hơn 4 giờ',
    subtitle: 'Lái 260 phút (REST_RECOMMENDED)',
    description: 'Thời gian lái xe đã đạt 260 phút (trên 4h). SafeDrive khuyến nghị dừng nghỉ.',
    iconName: 'AlertTriangle',
    vehicleState: {
      ...DEFAULT_VEHICLE_STATE,
      driverSupportSignals: {
        continuousDrivingMinutes: 260,
        lastSteeringInteractionMs: Date.now() - 40000,
        steeringSignalAvailable: true,
        driverSeatOccupied: true,
        seatSensorAvailable: true,
        wearableConnected: true,
        wearableLastUpdateMs: Date.now() - 15000,
        wearableHeartRateBpm: 68,
        wearablePermissionGranted: true,
        userReportedFatigue: false
      }
    }
  },
  {
    id: 'insufficient_data',
    title: '5. Chưa đủ dữ liệu',
    subtitle: 'Chỉ có 1 nguồn cảm biến',
    description: 'Thời gian lái chưa xác định, chỉ có cảm biến ghế. Yêu cầu thêm dữ liệu.',
    iconName: 'HelpCircle',
    vehicleState: {
      ...DEFAULT_VEHICLE_STATE,
      driverSupportSignals: {
        continuousDrivingMinutes: null,
        lastSteeringInteractionMs: null,
        steeringSignalAvailable: false,
        driverSeatOccupied: true,
        seatSensorAvailable: true,
        wearableConnected: false,
        wearableLastUpdateMs: null,
        wearableHeartRateBpm: null,
        wearablePermissionGranted: false,
        userReportedFatigue: false
      }
    }
  },
  {
    id: 'user_reported_fatigue',
    title: '6. Người dùng báo đang mệt',
    subtitle: 'Chủ động báo mệt (REST_RECOMMENDED)',
    description: 'Người dùng vừa phản hồi đang cảm thấy mệt mỏi qua giọng nói hoặc giao diện.',
    iconName: 'UserX',
    vehicleState: {
      ...DEFAULT_VEHICLE_STATE,
      driverSupportSignals: {
        continuousDrivingMinutes: 90,
        lastSteeringInteractionMs: Date.now() - 10000,
        steeringSignalAvailable: true,
        driverSeatOccupied: true,
        seatSensorAvailable: true,
        wearableConnected: true,
        wearableLastUpdateMs: Date.now() - 5000,
        wearableHeartRateBpm: 65,
        wearablePermissionGranted: true,
        userReportedFatigue: true
      }
    }
  },
  {
    id: 'overheat',
    title: '7. Động cơ quá nhiệt',
    subtitle: 'Cảnh báo quá nhiệt (HIGH)',
    description: 'Nhiệt độ động cơ 112°C. Hệ thống khuyến nghị giảm tải và tấp vào vị trí an toàn.',
    iconName: 'Flame',
    vehicleState: {
      ...DEFAULT_VEHICLE_STATE,
      speedKmh: 55,
      engineTemperatureC: 112,
      activeDtcs: [DTC_OVERHEAT],
    }
  },
  {
    id: 'crash',
    title: '8. Va chạm giả lập',
    subtitle: 'Tình huống khẩn cấp (CRITICAL)',
    description: 'Phát hiện va chạm gia tốc lớn, người trong xe KHÔNG PHẢN HỒI. Đếm ngược SOS 10s.',
    iconName: 'Siren',
    vehicleState: {
      ...DEFAULT_VEHICLE_STATE,
      speedKmh: 0,
      activeDtcs: [],
      crashDetected: true,
      passengerResponse: 'NO_RESPONSE',
    }
  }
];

export const INITIAL_CHAT_MESSAGES = [
  {
    id: 'msg_0',
    sender: 'SAFEDRIVE' as const,
    text: 'Xin chào! Tôi là Trợ lý SafeDrive AI từ HaUI Vehicle Smart Systems (HVS). Tôi hỗ trợ kiểm tra thông số hành trình, cảnh báo an toàn và chẩn đoán kỹ thuật.',
    timestamp: '20:30',
    latencyMs: 120,
    route: 'safety_fast_path'
  },
  {
    id: 'msg_1',
    sender: 'USER' as const,
    text: 'Xe của tôi đã lái bao lâu rồi?',
    timestamp: '20:31'
  },
  {
    id: 'msg_2',
    sender: 'SAFEDRIVE' as const,
    text: 'Xe đã di chuyển liên tục 2 giờ 15 phút. Dựa trên các tín hiệu hỗ trợ gián tiếp (vô lăng, ghế lái), hệ thống khuyến nghị bạn nên theo dõi tình trạng sức khỏe.',
    timestamp: '20:31',
    latencyMs: 186,
    route: 'safety_fast_path',
    risk: {
      level: 'LOW' as const,
      title: 'Nên theo dõi',
      message: 'Thời gian lái liên tục đang tăng. Hãy theo dõi tình trạng của bạn.',
      reasonCodes: ['continuous_driving_over_2h']
    }
  }
];

