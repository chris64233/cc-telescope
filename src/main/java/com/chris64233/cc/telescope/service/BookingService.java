package com.chris64233.cc.telescope.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import com.chris64233.cc.telescope.domain.BookingStatus;
import com.chris64233.cc.telescope.domain.Instrument;
import com.chris64233.cc.telescope.domain.ObservationBooking;
import com.chris64233.cc.telescope.domain.Proposal;
import com.chris64233.cc.telescope.domain.Telescope;
import com.chris64233.cc.telescope.dto.BookingRequest;
import com.chris64233.cc.telescope.dto.BookingResponse;
import com.chris64233.cc.telescope.dto.QuotaResponse;
import com.chris64233.cc.telescope.dto.ScheduleResponse;
import com.chris64233.cc.telescope.repo.InstrumentRepository;
import com.chris64233.cc.telescope.repo.ObservationBookingRepository;
import com.chris64233.cc.telescope.repo.ProposalRepository;
import com.chris64233.cc.telescope.repo.TelescopeRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BookingService {

    private final ProposalRepository proposalRepository;
    private final TelescopeRepository telescopeRepository;
    private final InstrumentRepository instrumentRepository;
    private final ObservationBookingRepository bookingRepository;
    private final EntityManager entityManager;
    private final Clock clock;

    public BookingService(ProposalRepository proposalRepository,
                          TelescopeRepository telescopeRepository,
                          InstrumentRepository instrumentRepository,
                          ObservationBookingRepository bookingRepository,
                          EntityManager entityManager,
                          Clock clock) {
        this.proposalRepository = proposalRepository;
        this.telescopeRepository = telescopeRepository;
        this.instrumentRepository = instrumentRepository;
        this.bookingRepository = bookingRepository;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    /**
     * 创建观测预订。幂等键重放时返回原结果；键相同但内容不同返回冲突。
     *
     * 加锁顺序固定为 提案行 -> 望远镜行，保证并发时无死锁；
     * 配额扣减与排程冲突检测处于同一事务，杜绝超卖与时间冲突。
     */
    @Transactional
    public BookingResponse book(BookingRequest request) {
        if (request.idempotencyKey() != null && !request.idempotencyKey().isBlank()) {
            var existing = bookingRepository.findByIdempotencyKey(request.idempotencyKey());
            if (existing.isPresent()) {
                return replayOrConflict(existing.get(), request);
            }
        }

        Proposal lockedProposal = proposalRepository.findByProposalNoForUpdate(request.proposalNo())
                .orElseThrow(() -> new NotFoundException("提案不存在: " + request.proposalNo()));
        Telescope telescope = telescopeRepository.findByCode(request.telescopeCode())
                .orElseThrow(() -> new NotFoundException("望远镜不存在: " + request.telescopeCode()));
        Instrument instrument = instrumentRepository.findByName(request.instrument())
                .orElseThrow(() -> new NotFoundException("仪器不存在: " + request.instrument()));

        validateWindow(request.startAt(), request.endAt());
        int durationMinutes = (int) Duration.between(request.startAt(), request.endAt()).toMinutes();

        if (!telescope.supports(instrument)) {
            throw new ConflictException("望远镜 " + telescope.getCode()
                    + " 不支持仪器 " + instrument.getName());
        }
        if (!lockedProposal.allows(instrument)) {
            throw new ConflictException("提案 " + lockedProposal.getProposalNo()
                    + " 不允许使用仪器 " + instrument.getName());
        }

        // 固定加锁顺序：先提案后望远镜，避免死锁。
        entityManager.lock(telescope, LockModeType.PESSIMISTIC_WRITE);

        lockedProposal.consume(durationMinutes);

        List<ObservationBooking> schedule = bookingRepository
                .findByTelescopeIdAndStatusOrderByStartAtAscIdAsc(
                        telescope.getId(), BookingStatus.BOOKED);
        assertNoScheduleConflict(schedule, telescope.getSwitchOverMinutes(),
                request.startAt(), request.endAt(), instrument.getName());

        ObservationBooking booking = new ObservationBooking(
                lockedProposal, telescope, instrument,
                request.startAt(), request.endAt(), durationMinutes,
                normalizeKey(request.idempotencyKey()));

        try {
            ObservationBooking saved = bookingRepository.saveAndFlush(booking);
            return toBookingResponse(saved);
        } catch (DataIntegrityViolationException e) {
            // 并发下幂等键唯一约束兜底：重放原预订或按冲突处理。
            if (request.idempotencyKey() != null && !request.idempotencyKey().isBlank()) {
                ObservationBooking raced = bookingRepository
                        .findByIdempotencyKey(request.idempotencyKey()).orElseThrow();
                return replayOrConflict(raced, request);
            }
            throw new ConflictException("预订冲突，请重试");
        }
    }

    /**
     * 取消未开始的预订并归还配额；已开始的预订不得取消。
     * 预订状态与配额归还处于同一事务，重复取消不会再次归还。
     */
    @Transactional
    public void cancel(Long bookingId) {
        ObservationBooking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new NotFoundException("预订不存在: " + bookingId));

        if (booking.isCancelled()) {
            throw new ConflictException("预订已取消: " + bookingId);
        }
        Instant now = Instant.now(clock);
        if (!booking.getStartAt().isAfter(now)) {
            throw new ConflictException("观测已开始，不得取消: " + bookingId);
        }

        Proposal proposal = entityManager.find(
                Proposal.class, booking.getProposal().getId(), LockModeType.PESSIMISTIC_WRITE);
        entityManager.lock(booking.getTelescope(), LockModeType.PESSIMISTIC_WRITE);

        booking.cancel();
        proposal.refund(booking.getDurationMinutes());
        bookingRepository.saveAndFlush(booking);
    }

    @Transactional(readOnly = true)
    public ScheduleResponse getSchedule(String telescopeCode) {
        Telescope telescope = telescopeRepository.findByCode(telescopeCode)
                .orElseThrow(() -> new NotFoundException("望远镜不存在: " + telescopeCode));
        List<BookingResponse> bookings = bookingRepository
                .findByTelescopeIdAndStatusOrderByStartAtAscIdAsc(
                        telescope.getId(), BookingStatus.BOOKED)
                .stream()
                .map(this::toBookingResponse)
                .toList();
        return new ScheduleResponse(telescope.getCode(),
                telescope.getSwitchOverMinutes(), bookings);
    }

    @Transactional(readOnly = true)
    public QuotaResponse getQuota(String proposalNo) {
        Proposal proposal = proposalRepository.findByProposalNo(proposalNo)
                .orElseThrow(() -> new NotFoundException("提案不存在: " + proposalNo));
        return new QuotaResponse(proposal.getProposalNo(), proposal.getTotalMinutes(),
                proposal.getUsedMinutes(), proposal.getRemainingMinutes());
    }

    private BookingResponse replayOrConflict(ObservationBooking existing, BookingRequest request) {
        if (matches(existing, request)) {
            return toBookingResponse(existing);
        }
        throw new ConflictException("幂等键 " + request.idempotencyKey()
                + " 已用于内容不同的预订");
    }

    private boolean matches(ObservationBooking booking, BookingRequest request) {
        return Objects.equals(booking.getProposal().getProposalNo(), request.proposalNo())
                && Objects.equals(booking.getTelescope().getCode(), request.telescopeCode())
                && Objects.equals(booking.getInstrument().getName(), request.instrument())
                && Objects.equals(booking.getStartAt(), request.startAt())
                && Objects.equals(booking.getEndAt(), request.endAt());
    }

    /**
     * 校验新区间与已有预订的排程关系。区间为左闭右开：
     * 时间重叠或首尾相接均视为占用；不同仪器的相邻预订之间还必须留出
     * 至少 {@code switchOverMinutes} 分钟的切换准备时长，相同仪器可以首尾相接。
     */
    private void assertNoScheduleConflict(List<ObservationBooking> schedule,
                                          int switchOverMinutes,
                                          Instant startAt, Instant endAt,
                                          String newInstrument) {
        long gapSeconds = switchOverMinutes * 60L;
        for (ObservationBooking existing : schedule) {
            // 左闭右开：首尾相接（端点相等）不算重叠。
            boolean overlap = startAt.isBefore(existing.getEndAt())
                    && endAt.isAfter(existing.getStartAt());
            if (overlap) {
                throw new ConflictException("望远镜时间与预订 #"
                        + existing.getId() + " 冲突");
            }

            // 相同仪器可以首尾相接；切换时长为 0 时也无需间隔。
            boolean differentInstrument = !newInstrument.equals(existing.getInstrument().getName());
            if (!differentInstrument || gapSeconds <= 0) {
                continue;
            }

            if (!startAt.isBefore(existing.getEndAt())) {
                // 已有预订位于新预订之前：新开始时间至少晚于其结束时间一个切换间隔。
                if (startAt.isBefore(existing.getEndAt().plusSeconds(gapSeconds))) {
                    throw gapConflict(existing, switchOverMinutes);
                }
            } else {
                // 已有预订位于新预订之后：其开始时间至少晚于新结束时间一个切换间隔。
                if (existing.getStartAt().isBefore(endAt.plusSeconds(gapSeconds))) {
                    throw gapConflict(existing, switchOverMinutes);
                }
            }
        }
    }

    private ConflictException gapConflict(ObservationBooking existing, int switchOverMinutes) {
        return new ConflictException("与预订 #" + existing.getId()
                + " 使用不同仪器，至少需要 " + switchOverMinutes + " 分钟切换间隔");
    }

    private void validateWindow(Instant startAt, Instant endAt) {
        if (!endAt.isAfter(startAt)) {
            throw new ConflictException("结束时间必须晚于起始时间");
        }
        long seconds = Duration.between(startAt, endAt).toSeconds();
        if (seconds % 60 != 0) {
            throw new ConflictException("预订时长必须为整数分钟");
        }
    }

    private String normalizeKey(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        return key;
    }

    private BookingResponse toBookingResponse(ObservationBooking booking) {
        return new BookingResponse(
                booking.getId(),
                booking.getProposal().getProposalNo(),
                booking.getTelescope().getCode(),
                booking.getInstrument().getName(),
                booking.getStartAt(),
                booking.getEndAt(),
                booking.getDurationMinutes(),
                booking.getStatus().name(),
                booking.getIdempotencyKey());
    }
}
