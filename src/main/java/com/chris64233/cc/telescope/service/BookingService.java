package com.chris64233.cc.telescope.service;

import com.chris64233.cc.telescope.domain.Proposal;
import com.chris64233.cc.telescope.domain.Reservation;
import com.chris64233.cc.telescope.domain.ReservationStatus;
import com.chris64233.cc.telescope.domain.Telescope;
import com.chris64233.cc.telescope.domain.WeatherRecovery;
import com.chris64233.cc.telescope.domain.WeatherRecoveryStatus;
import com.chris64233.cc.telescope.repository.OpportunityProposalRepository;
import com.chris64233.cc.telescope.repository.ProposalRepository;
import com.chris64233.cc.telescope.repository.ReservationRepository;
import com.chris64233.cc.telescope.repository.TelescopeRepository;
import com.chris64233.cc.telescope.repository.WeatherRecoveryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

@Service
public class BookingService {

    private final TelescopeRepository telescopeRepository;
    private final ProposalRepository proposalRepository;
    private final ReservationRepository reservationRepository;
    private final OpportunityProposalRepository opportunityProposalRepository;
    private final WeatherRecoveryRepository weatherRecoveryRepository;

    public BookingService(TelescopeRepository telescopeRepository,
                          ProposalRepository proposalRepository,
                          ReservationRepository reservationRepository,
                          OpportunityProposalRepository opportunityProposalRepository,
                          WeatherRecoveryRepository weatherRecoveryRepository) {
        this.telescopeRepository = telescopeRepository;
        this.proposalRepository = proposalRepository;
        this.reservationRepository = reservationRepository;
        this.opportunityProposalRepository = opportunityProposalRepository;
        this.weatherRecoveryRepository = weatherRecoveryRepository;
    }

    @Transactional
    public Telescope registerTelescope(String code, String name, long switchMinutes, Set<String> instruments) {
        if (switchMinutes < 0) {
            throw new BusinessRuleException("仪器切换准备时长不能为负数");
        }
        if (instruments == null || instruments.isEmpty()) {
            throw new BusinessRuleException("望远镜至少需要支持一台仪器");
        }
        telescopeRepository.findByCode(code).ifPresent(existing -> {
            throw new BusinessRuleException("望远镜编号已存在: " + code);
        });
        return telescopeRepository.save(new Telescope(code, name, switchMinutes, instruments));
    }

    @Transactional
    public Proposal registerProposal(String code, Set<String> allowedInstruments, long totalQuotaMinutes) {
        if (totalQuotaMinutes <= 0) {
            throw new BusinessRuleException("提案配额必须为正数");
        }
        if (allowedInstruments == null || allowedInstruments.isEmpty()) {
            throw new BusinessRuleException("提案至少需要允许一台仪器");
        }
        proposalRepository.findByCode(code).ifPresent(existing -> {
            throw new BusinessRuleException("提案编号已存在: " + code);
        });
        return proposalRepository.save(new Proposal(code, allowedInstruments, totalQuotaMinutes));
    }

    @Transactional
    public BookingOutcome book(String idempotencyKey, String proposalCode, String telescopeCode,
                               String instrument, Instant startTime, Instant endTime) {
        validateTimeRange(startTime, endTime);

        var replay = reservationRepository.findByIdempotencyKey(idempotencyKey);
        if (replay.isPresent()) {
            Reservation existing = replay.get();
            if (existing.matches(proposalCode, telescopeCode, instrument, startTime, endTime)) {
                return new BookingOutcome(existing, false);
            }
            throw new IdempotencyConflictException("幂等键已使用且内容不一致: " + idempotencyKey);
        }

        Telescope telescope = telescopeRepository.findByCodeForUpdate(telescopeCode)
                .orElseThrow(() -> new ResourceNotFoundException("望远镜不存在: " + telescopeCode));
        Proposal proposal = proposalRepository.findByCodeForUpdate(proposalCode)
                .orElseThrow(() -> new ResourceNotFoundException("提案不存在: " + proposalCode));

        if (!telescope.supports(instrument)) {
            throw new BusinessRuleException("望远镜 " + telescopeCode + " 不支持仪器 " + instrument);
        }
        if (!proposal.allows(instrument)) {
            throw new BusinessRuleException("提案 " + proposalCode + " 不允许使用仪器 " + instrument);
        }

        long durationMinutes = Duration.between(startTime, endTime).toMinutes();
        if (proposal.getRemainingQuotaMinutes() < durationMinutes) {
            throw new BusinessRuleException("提案 " + proposalCode + " 剩余配额不足，需要 "
                    + durationMinutes + " 分钟，剩余 " + proposal.getRemainingQuotaMinutes() + " 分钟");
        }

        checkSchedule(telescope, instrument, startTime, endTime);

        proposal.deduct(durationMinutes);
        Reservation reservation = reservationRepository.save(new Reservation(
                idempotencyKey, proposal, telescope, instrument, startTime, endTime, durationMinutes));
        return new BookingOutcome(reservation, true);
    }

    private void checkSchedule(Telescope telescope, String instrument, Instant startTime, Instant endTime) {
        List<Reservation> active = reservationRepository
                .findByTelescopeAndStatusOrderByStartTimeAscIdAsc(telescope, ReservationStatus.ACTIVE);
        validateAgainstSchedule(telescope, instrument, startTime, endTime, active);
    }

    /**
     * 针对给定的有效预订集合校验新时段：不得与任一预订重叠，且与前、后相邻预订使用不同仪器时
     * 必须满足切换准备时长。调用方须已持有望远镜行级悲观锁。
     */
    void validateAgainstSchedule(Telescope telescope, String instrument, Instant startTime, Instant endTime,
                                 List<Reservation> active) {
        boolean overlaps = active.stream().anyMatch(r ->
                r.getStartTime().isBefore(endTime) && r.getEndTime().isAfter(startTime));
        if (overlaps) {
            throw new ScheduleConflictException("该时段与望远镜 " + telescope.getCode() + " 上的已有预订重叠");
        }

        Duration switchTime = Duration.ofMinutes(telescope.getSwitchMinutes());
        active.stream()
                .filter(r -> !r.getEndTime().isAfter(startTime))
                .max(Comparator.comparing(Reservation::getEndTime).thenComparing(Reservation::getId))
                .ifPresent(predecessor -> requireSwitchGap(predecessor.getInstrument(), instrument,
                        predecessor.getEndTime(), startTime, switchTime));
        active.stream()
                .filter(r -> !r.getStartTime().isBefore(endTime))
                .min(Comparator.comparing(Reservation::getStartTime).thenComparing(Reservation::getId))
                .ifPresent(successor -> requireSwitchGap(instrument, successor.getInstrument(),
                        endTime, successor.getStartTime(), switchTime));
    }

    private void requireSwitchGap(String beforeInstrument, String afterInstrument,
                                  Instant gapStart, Instant gapEnd, Duration switchTime) {
        if (beforeInstrument.equals(afterInstrument)) {
            return;
        }
        if (gapStart.plus(switchTime).isAfter(gapEnd)) {
            throw new ScheduleConflictException("相邻预订使用不同仪器，需至少留出 "
                    + switchTime.toMinutes() + " 分钟切换时长");
        }
    }

    @Transactional
    public Reservation cancel(Long reservationId) {
        // 规范加锁顺序：望远镜行锁 → 配额账户行锁 → 预订行锁（与抢占/重排一致，消除跨望远镜死锁）。
        // 望远镜编号与账户都用投影读取，避免把预订实体无锁加载进一级缓存而使后续 FOR UPDATE 读到陈旧状态。
        String telescopeCode = reservationRepository.findTelescopeCodeById(reservationId);
        if (telescopeCode == null) {
            throw new ResourceNotFoundException("预订不存在: " + reservationId);
        }
        telescopeRepository.findByCodeForUpdate(telescopeCode);

        // 用单列标量定位账户类型（不加载预订实体进一级缓存）；两个账户 ID 皆空表示预订不存在
        Long normalAccountId = reservationRepository.findProposalIdById(reservationId);
        Long opportunityAccountId = reservationRepository.findOpportunityIdById(reservationId);
        boolean opportunityAccount;
        Long accountId;
        if (opportunityAccountId != null) {
            opportunityAccount = true;
            accountId = opportunityAccountId;
        } else if (normalAccountId != null) {
            opportunityAccount = false;
            accountId = normalAccountId;
        } else {
            throw new ResourceNotFoundException("预订不存在: " + reservationId);
        }

        Long fundedRecoveryId = reservationRepository.findFundedRecoveryIdById(reservationId);
        if (fundedRecoveryId != null) {
            // 由天气恢复资格出资的（新）预订：不触碰配额账户，取消时把分钟退回资格。
            return cancelRecoveryFundedReservation(reservationId, fundedRecoveryId);
        }

        if (opportunityAccount) {
            opportunityProposalRepository.findByIdForUpdate(accountId);
        } else {
            proposalRepository.findByIdForUpdate(accountId);
        }

        // 天气恢复资格行按统一顺序在预订行之前锁定（无资格时为空，无副作用）
        List<WeatherRecovery> weatherGenerations =
                weatherRecoveryRepository.findAllByReservationIdForUpdate(reservationId);

        // 预订实体的首次加载即加行锁，状态判断与退款在锁内完成
        Reservation reservation = reservationRepository.findByIdForUpdate(reservationId)
                .orElseThrow(() -> new ResourceNotFoundException("预订不存在: " + reservationId));
        Instant now = Instant.now();
        switch (reservation.getStatus()) {
            case CANCELLED, RESCHEDULED, RECOVERED -> {
                // 重复取消/已重排/已恢复：幂等返回，不再触碰配额
                return reservation;
            }
            case PENDING_RESCHEDULE -> {
                // 抢占时配额已归还；放弃待重排任务不再归还
                reservation.abandonPending(now);
                return reservation;
            }
            case WEATHER_BLOCKED -> {
                // 放弃天气阻断预订：仅把尚未消耗的可恢复分钟一次性退还配额账户，
                // 已恢复消耗的分钟不退还（对应新预订仍占用日程）。资格行已锁，不会与恢复排期/范围调整重复释放。
                WeatherRecovery latest = weatherGenerations.isEmpty()
                        ? null
                        : weatherGenerations.get(weatherGenerations.size() - 1);
                long refundMinutes = latest == null ? 0 : latest.getRemainingRecoverableMinutes();
                if (refundMinutes > 0) {
                    if (opportunityAccount) {
                        reservation.getOpportunity().refund(refundMinutes);
                    } else {
                        reservation.getProposal().refund(refundMinutes);
                    }
                    latest.forfeit(now);
                }
                reservation.abandonWeatherBlocked(now);
                return reservation;
            }
            case ACTIVE -> {
                if (!now.isBefore(reservation.getStartTime())) {
                    throw new CancellationNotAllowedException("预订已开始，不能取消");
                }
                if (opportunityAccount) {
                    reservation.getOpportunity().refund(reservation.getDurationMinutes());
                } else {
                    reservation.getProposal().refund(reservation.getDurationMinutes());
                }
                reservation.cancel(now);
                return reservation;
            }
            default -> throw new IllegalStateException("未知预订状态: " + reservation.getStatus());
        }
    }

    /**
     * 取消一笔由天气恢复资格出资的有效预订：分钟退回恢复资格（不退还配额，避免重复释放）；
     * 若退回使资格从 RECOVERED 终态重开，则把原预订恢复为天气阻断状态。
     * 调用方已持有望远镜行锁；锁序：恢复资格行 → 新预订行 →（如需）原预订行。
     */
    private Reservation cancelRecoveryFundedReservation(Long reservationId, Long fundedRecoveryId) {
        Instant now = Instant.now();
        WeatherRecovery recovery = weatherRecoveryRepository.findByIdForUpdate(fundedRecoveryId)
                .orElseThrow(() -> new ResourceNotFoundException("天气恢复资格不存在: " + fundedRecoveryId));
        Reservation reservation = reservationRepository.findByIdForUpdate(reservationId)
                .orElseThrow(() -> new ResourceNotFoundException("预订不存在: " + reservationId));
        if (reservation.getStatus() == ReservationStatus.CANCELLED) {
            return reservation;
        }
        if (!now.isBefore(reservation.getStartTime())) {
            throw new CancellationNotAllowedException("预订已开始，不能取消");
        }
        boolean reopened = recovery.releaseBack(reservation.getDurationMinutes(), reservationId, now);
        reservation.cancel(now);
        if (reopened) {
            Reservation original = reservationRepository
                    .findByIdForUpdate(recovery.getOriginalReservation().getId())
                    .orElseThrow(() -> new ResourceNotFoundException("原预订不存在"));
            if (original.getStatus() == ReservationStatus.RECOVERED) {
                original.reopenFromRecovered(now);
            }
        }
        return reservation;
    }

    @Transactional(readOnly = true)
    public List<Reservation> schedule(String telescopeCode) {
        Telescope telescope = telescopeRepository.findByCode(telescopeCode)
                .orElseThrow(() -> new ResourceNotFoundException("望远镜不存在: " + telescopeCode));
        return reservationRepository.findScheduleForRead(telescope, ReservationStatus.ACTIVE);
    }

    @Transactional(readOnly = true)
    public Proposal quota(String proposalCode) {
        return proposalRepository.findByCode(proposalCode)
                .orElseThrow(() -> new ResourceNotFoundException("提案不存在: " + proposalCode));
    }

    @Transactional(readOnly = true)
    public Reservation findReservation(Long reservationId) {
        return reservationRepository.findDetailedById(reservationId)
                .orElseThrow(() -> new ResourceNotFoundException("预订不存在: " + reservationId));
    }

    private void validateTimeRange(Instant startTime, Instant endTime) {
        if (startTime == null || endTime == null || !startTime.isBefore(endTime)) {
            throw new BusinessRuleException("起止时间无效：开始时间必须早于结束时间");
        }
        Duration duration = Duration.between(startTime, endTime);
        if (!Duration.ofMinutes(duration.toMinutes()).equals(duration)) {
            throw new BusinessRuleException("起止时间需对齐到整分钟");
        }
    }

    public record BookingOutcome(Reservation reservation, boolean created) {
    }
}
