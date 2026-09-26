package com.chris64233.cc.telescope.service;

import com.chris64233.cc.telescope.domain.Proposal;
import com.chris64233.cc.telescope.domain.Reservation;
import com.chris64233.cc.telescope.domain.ReservationStatus;
import com.chris64233.cc.telescope.domain.Telescope;
import com.chris64233.cc.telescope.repository.ProposalRepository;
import com.chris64233.cc.telescope.repository.ReservationRepository;
import com.chris64233.cc.telescope.repository.TelescopeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

@Service
public class BookingService {

    private final TelescopeRepository telescopeRepository;
    private final ProposalRepository proposalRepository;
    private final ReservationRepository reservationRepository;

    public BookingService(TelescopeRepository telescopeRepository,
                          ProposalRepository proposalRepository,
                          ReservationRepository reservationRepository) {
        this.telescopeRepository = telescopeRepository;
        this.proposalRepository = proposalRepository;
        this.reservationRepository = reservationRepository;
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
        return registerProposal(code, allowedInstruments, totalQuotaMinutes, false, null, null);
    }

    /**
     * 登记提案。目标机会（ToO）提案需指定优先级（越大越高）与有效期截止时间（响应时限）。
     */
    @Transactional
    public Proposal registerProposal(String code, Set<String> allowedInstruments, long totalQuotaMinutes,
                                     boolean targetOpportunity, Integer priority, Instant validUntil) {
        if (totalQuotaMinutes <= 0) {
            throw new BusinessRuleException("提案配额必须为正数");
        }
        if (allowedInstruments == null || allowedInstruments.isEmpty()) {
            throw new BusinessRuleException("提案至少需要允许一台仪器");
        }
        if (targetOpportunity) {
            if (priority == null) {
                throw new BusinessRuleException("目标机会提案必须指定优先级");
            }
            if (validUntil == null) {
                throw new BusinessRuleException("目标机会提案必须指定响应时限（有效期截止时间）");
            }
        }
        proposalRepository.findByCode(code).ifPresent(existing -> {
            throw new BusinessRuleException("提案编号已存在: " + code);
        });
        return proposalRepository.save(new Proposal(code, allowedInstruments, totalQuotaMinutes,
                targetOpportunity, priority, validUntil));
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

        Reservation reservation = createActiveReservation(
                idempotencyKey, proposal, telescope, instrument, startTime, endTime, null);
        telescope.incrementScheduleVersion();
        return new BookingOutcome(reservation, true);
    }

    /**
     * 在已持有望远镜行锁与提案行锁的前提下，完成仪器/配额/日程校验、扣减配额并建立有效预订。
     * 供普通预订与抢占重排共用；rearrangedFrom 非空时该预订是重排产生的新预订。
     * 调用方负责按需推进日程版本。
     */
    Reservation createActiveReservation(String idempotencyKey, Proposal proposal, Telescope telescope,
                                        String instrument, Instant startTime, Instant endTime,
                                        Reservation rearrangedFrom) {
        if (!telescope.supports(instrument)) {
            throw new BusinessRuleException("望远镜 " + telescope.getCode() + " 不支持仪器 " + instrument);
        }
        if (!proposal.allows(instrument)) {
            throw new BusinessRuleException("提案 " + proposal.getCode() + " 不允许使用仪器 " + instrument);
        }

        long durationMinutes = Duration.between(startTime, endTime).toMinutes();
        if (proposal.getRemainingQuotaMinutes() < durationMinutes) {
            throw new BusinessRuleException("提案 " + proposal.getCode() + " 剩余配额不足，需要 "
                    + durationMinutes + " 分钟，剩余 " + proposal.getRemainingQuotaMinutes() + " 分钟");
        }

        checkSchedule(telescope, instrument, startTime, endTime);

        proposal.deduct(durationMinutes);
        return reservationRepository.save(new Reservation(
                idempotencyKey, proposal, telescope, instrument, startTime, endTime,
                durationMinutes, rearrangedFrom));
    }

    /** 日程冲突与前后相邻仪器切换时间检查。 */
    void checkSchedule(Telescope telescope, String instrument, Instant startTime, Instant endTime) {
        boolean overlaps = reservationRepository.existsByTelescopeAndStatusAndStartTimeLessThanAndEndTimeGreaterThan(
                telescope, ReservationStatus.ACTIVE, endTime, startTime);
        if (overlaps) {
            throw new ScheduleConflictException("该时段与望远镜 " + telescope.getCode() + " 上的已有预订重叠");
        }

        Duration switchTime = Duration.ofMinutes(telescope.getSwitchMinutes());
        reservationRepository
                .findFirstByTelescopeAndStatusAndEndTimeLessThanEqualOrderByEndTimeDescIdDesc(
                        telescope, ReservationStatus.ACTIVE, startTime)
                .ifPresent(predecessor -> requireSwitchGap(predecessor.getInstrument(), instrument,
                        predecessor.getEndTime(), startTime, switchTime));
        reservationRepository
                .findFirstByTelescopeAndStatusAndStartTimeGreaterThanEqualOrderByStartTimeAscIdAsc(
                        telescope, ReservationStatus.ACTIVE, endTime)
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

    /**
     * 取消预订。锁获取顺序与抢占/重排保持一致（望远镜 → 提案 → 预订），避免并发死锁。
     * 待重排预订的配额已在抢占时归还，取消只改变状态、不重复归还。
     */
    @Transactional
    public Reservation cancel(Long reservationId) {
        // 先取路由信息（不加载实体），再按 望远镜 → 提案 → 预订 的顺序加锁。
        // 若在加锁前把预订实体读进持久化上下文，行锁后的加锁查询会返回加锁前的过期状态，
        // 与抢占并发时可能重复归还分钟数。
        var routing = reservationRepository.findRoutingById(reservationId)
                .orElseThrow(() -> new ResourceNotFoundException("预订不存在: " + reservationId));
        Telescope telescope = telescopeRepository.findByCodeForUpdate(routing.getTelescopeCode())
                .orElseThrow(() -> new ResourceNotFoundException("望远镜不存在"));
        Proposal proposal = proposalRepository.findByIdForUpdate(routing.getProposalId())
                .orElseThrow(() -> new ResourceNotFoundException("提案不存在"));
        Reservation reservation = reservationRepository.findByIdForUpdate(reservationId)
                .orElseThrow(() -> new ResourceNotFoundException("预订不存在: " + reservationId));
        if (reservation.getStatus() == ReservationStatus.CANCELLED) {
            return reservation;
        }
        if (reservation.getStatus() == ReservationStatus.PREEMPTED) {
            throw new CancellationNotAllowedException("预订已因抢占重排结束，不能取消");
        }
        Instant now = Instant.now();
        if (!now.isBefore(reservation.getStartTime())) {
            throw new CancellationNotAllowedException("预订已开始，不能取消");
        }
        if (reservation.getStatus() == ReservationStatus.PENDING_REARRANGE) {
            // 配额在抢占时已归还；待重排期间放弃重排即取消，不能再次归还分钟数。
            reservation.cancel(now);
            return reservation;
        }
        proposal.refund(reservation.getDurationMinutes());
        reservation.cancel(now);
        telescope.incrementScheduleVersion();
        return reservation;
    }

    @Transactional(readOnly = true)
    public List<Reservation> schedule(String telescopeCode) {
        Telescope telescope = telescopeRepository.findByCode(telescopeCode)
                .orElseThrow(() -> new ResourceNotFoundException("望远镜不存在: " + telescopeCode));
        return reservationRepository.findByTelescopeAndStatusOrderByStartTimeAscIdAsc(
                telescope, ReservationStatus.ACTIVE);
    }

    @Transactional(readOnly = true)
    public Proposal quota(String proposalCode) {
        return proposalRepository.findByCode(proposalCode)
                .orElseThrow(() -> new ResourceNotFoundException("提案不存在: " + proposalCode));
    }

    @Transactional(readOnly = true)
    public Reservation findReservation(Long reservationId) {
        return reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ResourceNotFoundException("预订不存在: " + reservationId));
    }

    static void validateTimeRange(Instant startTime, Instant endTime) {
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
