import React from 'react';
import { DriverSupportSignals, RestRecommendation } from '../../types/safedrive';
import { Activity, Clock, CircleDot, UserCheck, Watch, Info, ShieldAlert } from 'lucide-react';

interface DriverStatusCardProps {
  signals: DriverSupportSignals;
  restRecommendation: RestRecommendation;
}

export const DriverStatusCard: React.FC<DriverStatusCardProps> = ({
  signals,
  restRecommendation
}) => {
  // Format continuous driving time
  const formatDrivingTime = (mins: number | null) => {
    if (mins === null) return 'Chưa có dữ liệu';
    const h = Math.floor(mins / 60);
    const m = mins % 60;
    if (h === 0) return `${m} phút`;
    if (m === 0) return `${h} giờ`;
    return `${h} giờ ${m} phút`;
  };

  // Format steering interaction
  const getSteeringStatus = () => {
    if (!signals.steeringSignalAvailable) {
      return { title: 'Tín hiệu không khả dụng', sub: 'Không có cảm biến vô lăng' };
    }
    if (signals.lastSteeringInteractionMs === null) {
      return { title: 'Chưa có dữ liệu', sub: 'Chờ tương tác vô lăng' };
    }
    const secs = Math.round((Date.now() - signals.lastSteeringInteractionMs) / 1000);
    return { 
      title: 'Có tương tác gần đây', 
      sub: `Lần gần nhất: ${secs < 0 ? 0 : secs} giây trước` 
    };
  };

  // Seat sensor text
  const getSeatStatus = () => {
    if (!signals.seatSensorAvailable) {
      return { title: 'Không có dữ liệu', sub: 'Cảm biến ghế chưa được trang bị' };
    }
    if (signals.driverSeatOccupied === true) {
      return { title: 'Đã phát hiện người ngồi', sub: 'Tín hiệu hiện diện, không xác định mức độ tỉnh táo' };
    }
    if (signals.driverSeatOccupied === false) {
      return { title: 'Không phát hiện người ngồi', sub: 'Ghế lái đang trống' };
    }
    return { title: 'Không có dữ liệu', sub: 'Tín hiệu hiện diện, không xác định mức độ tỉnh táo' };
  };

  // Wearable text
  const getWearableStatus = () => {
    if (!signals.wearableConnected) {
      return { title: 'Chưa kết nối', sub: 'Kết nối wearable để bổ sung tín hiệu tham khảo' };
    }
    if (signals.wearablePermissionGranted && signals.wearableHeartRateBpm) {
      return { 
        title: `Đã kết nối (${signals.wearableHeartRateBpm} BPM)`, 
        sub: 'Dữ liệu tham khảo, không dùng để chẩn đoán' 
      };
    }
    return { title: 'Đã kết nối', sub: 'Dữ liệu gần nhất: 20 giây trước' };
  };

  // Recommendation Level Styling
  const getRecLevelStyle = (level: RestRecommendation['level']) => {
    switch (level) {
      case 'REST_RECOMMENDED':
        return {
          bg: 'bg-red-500/15 border-red-500/40 text-red-400',
          badgeText: 'Khuyến nghị dừng nghỉ'
        };
      case 'CONSIDER_REST':
        return {
          bg: 'bg-orange-500/15 border-orange-500/40 text-orange-400',
          badgeText: 'Nên cân nhắc nghỉ'
        };
      case 'MONITOR':
        return {
          bg: 'bg-amber-500/15 border-amber-500/40 text-amber-300',
          badgeText: 'Nên theo dõi'
        };
      case 'NO_IMMEDIATE_INDICATION':
        return {
          bg: 'bg-emerald-500/15 border-emerald-500/40 text-emerald-400',
          badgeText: 'Chưa ghi nhận dấu hiệu cần nghỉ'
        };
      case 'INSUFFICIENT_DATA':
      default:
        return {
          bg: 'bg-slate-800 border-slate-700 text-slate-300',
          badgeText: 'Chưa đủ dữ liệu'
        };
    }
  };

  const confidenceText = {
    LOW: 'Độ tin cậy: Thấp',
    MEDIUM: 'Độ tin cậy: Trung bình',
    HIGH: 'Độ tin cậy: Cao'
  }[restRecommendation.confidence];

  const steering = getSteeringStatus();
  const seat = getSeatStatus();
  const wearable = getWearableStatus();
  const recStyle = getRecLevelStyle(restRecommendation.level);

  return (
    <div className="p-5 rounded-2xl bg-[#132437] border border-slate-800 shadow-md space-y-5">
      {/* Header */}
      <div className="flex items-center justify-between gap-3">
        <div className="flex items-center gap-3">
          <div className="p-2.5 rounded-xl bg-cyan-500/10 border border-cyan-500/20 text-cyan-400">
            <Activity size={22} />
          </div>
          <div>
            <h3 className="text-base font-bold text-white">Tín hiệu hỗ trợ lái xe</h3>
            <p className="text-xs text-slate-400">Tổng hợp tín hiệu hành trình và thiết bị kết nối</p>
          </div>
        </div>

        <div className="px-3 py-1 rounded-full bg-[#08131F] border border-slate-700 text-xs font-mono font-semibold text-cyan-400">
          {restRecommendation.availableSourceCount}/4 nguồn
        </div>
      </div>

      {/* Part A: 4 Signals Grid */}
      <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
        {/* Signal 1: Continuous Driving Time */}
        <div className="p-3.5 rounded-xl bg-[#08131F] border border-slate-800 space-y-1">
          <div className="flex items-center gap-2 text-xs font-semibold text-slate-400">
            <Clock size={16} className="text-cyan-400" />
            <span>Thời gian lái liên tục</span>
          </div>
          <p className="text-sm font-bold text-white tracking-wide">
            {formatDrivingTime(signals.continuousDrivingMinutes)}
          </p>
          <p className="text-[11px] text-slate-400">
            {signals.continuousDrivingMinutes !== null 
              ? 'Bắt đầu hành trình gần nhất' 
              : 'Chưa ghi nhận thời gian bắt đầu'}
          </p>
        </div>

        {/* Signal 2: Steering interaction */}
        <div className="p-3.5 rounded-xl bg-[#08131F] border border-slate-800 space-y-1">
          <div className="flex items-center gap-2 text-xs font-semibold text-slate-400">
            <CircleDot size={16} className="text-cyan-400" />
            <span>Tương tác vô lăng</span>
          </div>
          <p className="text-sm font-bold text-white tracking-wide">
            {steering.title}
          </p>
          <p className="text-[11px] text-slate-400">
            {steering.sub}
          </p>
        </div>

        {/* Signal 3: Seat Sensor */}
        <div className="p-3.5 rounded-xl bg-[#08131F] border border-slate-800 space-y-1">
          <div className="flex items-center gap-2 text-xs font-semibold text-slate-400">
            <UserCheck size={16} className="text-cyan-400" />
            <span>Cảm biến ghế lái</span>
          </div>
          <p className="text-sm font-bold text-white tracking-wide">
            {seat.title}
          </p>
          <p className="text-[11px] text-slate-400">
            {seat.sub}
          </p>
        </div>

        {/* Signal 4: Wearable Device */}
        <div className="p-3.5 rounded-xl bg-[#08131F] border border-slate-800 space-y-1">
          <div className="flex items-center gap-2 text-xs font-semibold text-slate-400">
            <Watch size={16} className="text-cyan-400" />
            <span>Thiết bị wearable</span>
          </div>
          <p className="text-sm font-bold text-white tracking-wide">
            {wearable.title}
          </p>
          <p className="text-[11px] text-slate-400">
            {wearable.sub}
          </p>
        </div>
      </div>

      {/* Part B: Rest Recommendation */}
      <div className="p-4 rounded-xl bg-[#0B1726] border border-slate-800 space-y-3">
        <div className="flex items-center justify-between gap-2 flex-wrap">
          <span className="text-xs font-extrabold uppercase tracking-wider text-slate-400">
            ĐÁNH GIÁ NHU CẦU NGHỈ
          </span>
          <div className={`px-3 py-1 rounded-lg border text-xs font-bold ${recStyle.bg}`}>
            {recStyle.badgeText}
          </div>
        </div>

        <p className="text-sm text-slate-200 font-medium leading-relaxed">
          {restRecommendation.message}
        </p>

        <div className="flex items-center justify-between gap-2 text-xs text-slate-400 font-mono pt-1 border-t border-slate-800/60">
          <span className="font-semibold text-cyan-300">{confidenceText}</span>
          <span>Dựa trên {restRecommendation.availableSourceCount}/4 nguồn tín hiệu</span>
        </div>

        {/* Mandated Disclaimer */}
        <div className="p-2.5 rounded-lg bg-[#08131F] border border-slate-800/80 text-[11px] text-slate-400 flex items-start gap-2">
          <Info size={14} className="text-cyan-400 shrink-0 mt-0.5" />
          <span>
            Đây là đánh giá hỗ trợ dựa trên tín hiệu gián tiếp, không phải kết luận về trạng thái tài xế.
          </span>
        </div>
      </div>
    </div>
  );
};
