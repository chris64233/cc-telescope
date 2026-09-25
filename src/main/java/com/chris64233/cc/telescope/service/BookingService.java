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

    @Transactional
    public Reservation cancel(Long reservationId) {
        Reservation reservation = reservationRepository.findByIdForUpdate(reservationId)
                .orElseThrow(() -> new ResourceNotFoundException("预订不存在: " + reservationId));
        if (reservation.getStatus() == ReservationStatus.CANCELLED) {
            return reservation;
        }
        Instant now = Instant.now();
        if (!now.isBefore(reservation.getStartTime())) {
            throw new CancellationNotAllowedException("预订已开始，不能取消");
        }
        Proposal proposal = proposalRepository.findByIdForUpdate(reservation.getProposal().getId())
                .orElseThrow(() -> new ResourceNotFoundException("提案不存在"));
        proposal.refund(reservation.getDurationMinutes());
        reservation.cancel(now);
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
