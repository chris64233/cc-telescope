package com.chris64233.cc.telescope.service;

import com.chris64233.cc.telescope.domain.Preemption;
import com.chris64233.cc.telescope.domain.PreemptionItem;
import com.chris64233.cc.telescope.domain.Proposal;
import com.chris64233.cc.telescope.domain.Reservation;
import com.chris64233.cc.telescope.domain.ReservationStatus;
import com.chris64233.cc.telescope.domain.Telescope;
import com.chris64233.cc.telescope.repository.PreemptionRepository;
import com.chris64233.cc.telescope.repository.ProposalRepository;
import com.chris64233.cc.telescope.repository.ReservationRepository;
import com.chris64233.cc.telescope.repository.TelescopeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 目标机会（ToO）受控抢占服务。
 *
 * <p>锁顺序与普通预订/取消保持一致：望远镜行锁 → 提案行锁（按 ID 排序）→ 预订行锁（按 ID 排序），
 * 因此取消、抢占确认与重排并发时不会形成死锁，也不会重复归还或重复消费分钟数。
 */
@Service
public class PreemptionService {

    private static final String NEW_RESERVATION_KEY_PREFIX = "preemption:";

    private final TelescopeRepository telescopeRepository;
    private final ProposalRepository proposalRepository;
    private final ReservationRepository reservationRepository;
    private final PreemptionRepository preemptionRepository;
    private final BookingService bookingService;
    private final ObjectMapper objectMapper;

    public PreemptionService(TelescopeRepository telescopeRepository,
                             ProposalRepository proposalRepository,
                             ReservationRepository reservationRepository,
                             PreemptionRepository preemptionRepository,
                             BookingService bookingService,
                             ObjectMapper objectMapper) {
        this.telescopeRepository = telescopeRepository;
        this.proposalRepository = proposalRepository;
        this.reservationRepository = reservationRepository;
        this.preemptionRepository = preemptionRepository;
        this.bookingService = bookingService;
        this.objectMapper = objectMapper;
    }

    /**
     * 抢占预览（只读，不落库）：计算被覆盖的可抢占普通预订，以及与前、后相邻预订之间
     * 需要/可用的仪器切换时间和全部冲突原因。
     */
    @Transactional(readOnly = true)
    public PreemptionPreview preview(String proposalCode, String telescopeCode, String instrument,
                                     Instant startTime, Instant endTime) {
        BookingService.validateTimeRange(startTime, endTime);
        Telescope telescope = telescopeRepository.findByCode(telescopeCode)
                .orElseThrow(() -> new ResourceNotFoundException("望远镜不存在: " + telescopeCode));
        Proposal proposal = requireTooProposal(proposalCode);

        Impact impact = computeImpact(telescope, proposal, instrument, startTime, endTime);
        return new PreemptionPreview(impact.reasons().isEmpty(), impact.reasons(),
                impact.affected().stream().map(AffectedReservation::from).toList(),
                SwitchGap.from(impact.gapBefore()), SwitchGap.from(impact.gapAfter()));
    }

    /**
     * 确认抢占（以业务号幂等）。
     *
     * <p>可行时一次性：取消全部被覆盖的可抢占预订（进入待重排并归还各自配额）、
     * 扣减目标机会提案配额并建立新预订、推进日程版本并保存前后日程/配额/快照。
     * 存在任何冲突原因时保存 REJECTED 记录，原日程与配额完全不变。
     */
    @Transactional
    public Preemption confirm(String businessKey, String proposalCode, String telescopeCode,
                              String instrument, Instant startTime, Instant endTime) {
        if (businessKey == null || businessKey.isBlank()) {
            throw new BusinessRuleException("抢占业务号不能为空");
        }
        BookingService.validateTimeRange(startTime, endTime);

        Preemption replay = preemptionRepository.findByBusinessKey(businessKey).orElse(null);
        if (replay != null) {
            Proposal replayProposal = proposalRepository.findByCode(proposalCode)
                    .orElseThrow(() -> new ResourceNotFoundException("提案不存在: " + proposalCode));
            Telescope replayTelescope = telescopeRepository.findByCode(telescopeCode)
                    .orElseThrow(() -> new ResourceNotFoundException("望远镜不存在: " + telescopeCode));
            if (!replay.sameRequest(replayProposal, replayTelescope, instrument, startTime, endTime)) {
                throw new IdempotencyConflictException("抢占业务号已使用且内容不一致: " + businessKey);
            }
            return replay;
        }

        Telescope telescope = telescopeRepository.findByCodeForUpdate(telescopeCode)
                .orElseThrow(() -> new ResourceNotFoundException("望远镜不存在: " + telescopeCode));

        // 望远镜行锁已串行化该望远镜上的全部日程变更，因此这里在无提案/预订锁时收集
        // 候选预订是安全的；收集后先把全部相关提案（ToO + 被覆盖预订的属主）按 ID
        // 全局排序加锁，避免两个望远镜上的抢占/取消交叉持锁形成死锁。
        Proposal tooProbe = proposalRepository.findByCode(proposalCode)
                .orElseThrow(() -> new ResourceNotFoundException("提案不存在: " + proposalCode));
        if (!tooProbe.isTargetOpportunity()) {
            throw new BusinessRuleException("提案 " + proposalCode
                    + " 不是目标机会提案，不能发起抢占");
        }
        List<Reservation> candidates = reservationRepository
                .findByTelescopeAndStatusAndStartTimeLessThanAndEndTimeGreaterThan(
                        telescope, ReservationStatus.ACTIVE, endTime, startTime);
        Set<Long> proposalIds = new TreeSet<>();
        proposalIds.add(tooProbe.getId());
        for (Reservation candidate : candidates) {
            proposalIds.add(candidate.getProposal().getId());
        }
        Map<Long, Proposal> lockedProposals = new LinkedHashMap<>();
        for (Long proposalId : proposalIds) {
            Proposal locked = proposalRepository.findByIdForUpdate(proposalId)
                    .orElseThrow(() -> new ResourceNotFoundException("提案不存在: " + proposalId));
            lockedProposals.put(locked.getId(), locked);
        }
        Proposal proposal = lockedProposals.get(tooProbe.getId());

        long durationMinutes = Duration.between(startTime, endTime).toMinutes();
        Preemption preemption = new Preemption(businessKey, proposal, telescope, instrument,
                startTime, endTime, durationMinutes, Instant.now());

        Impact impact = computeImpact(telescope, proposal, instrument,
                startTime, endTime);
        if (!impact.reasons().isEmpty()) {
            preemption.reject(telescope.getScheduleVersion(),
                    snapshotSchedule(telescope), List.copyOf(impact.reasons()), Instant.now());
            return preemptionRepository.save(preemption);
        }

        long versionBefore = telescope.getScheduleVersion();
        String scheduleBeforeJson = snapshotSchedule(telescope);
        long tooQuotaBefore = proposal.getRemainingQuotaMinutes();

        // 预订按 ID 顺序加锁（受影响列表已按开始时间、ID 排序），与取消/重排的加锁顺序一致。
        List<Reservation> lockedAffected = new ArrayList<>();
        for (Reservation candidate : impact.affected()) {
            Reservation locked = reservationRepository.findByIdForUpdate(candidate.getId())
                    .orElseThrow(() -> new ResourceNotFoundException("预订不存在: " + candidate.getId()));
            if (locked.getStatus() == ReservationStatus.ACTIVE) {
                lockedAffected.add(locked);
            }
        }

        Instant now = Instant.now();
        List<PreemptionItem> items = lockedAffected.stream().map(PreemptionItem::new).toList();
        for (Reservation reservation : lockedAffected) {
            lockedProposals.get(reservation.getProposal().getId())
                    .refund(reservation.getDurationMinutes());
            reservation.markPendingRearrange(now);
        }

        Reservation newReservation = bookingService.createActiveReservation(
                NEW_RESERVATION_KEY_PREFIX + businessKey, proposal, telescope, instrument,
                startTime, endTime, null);
        telescope.incrementScheduleVersion();

        String scheduleAfterJson = snapshotSchedule(telescope);
        preemption.confirm(versionBefore, telescope.getScheduleVersion(),
                scheduleBeforeJson, scheduleAfterJson,
                tooQuotaBefore, proposal.getRemainingQuotaMinutes(),
                new ArrayList<>(items), newReservation, now);
        Preemption saved = preemptionRepository.save(preemption);
        for (Reservation reservation : lockedAffected) {
            reservation.assignPreemption(saved);
        }
        return saved;
    }

    /**
     * 重排待重排预订：在提案剩余有效期内为原预订寻找新时段，重排成功才再次扣减配额。
     * 新时段时长必须与原预订一致；原预订随之进入终态。幂等键防止重复重放。
     */
    @Transactional
    public BookingService.BookingOutcome rearrange(Long reservationId, String idempotencyKey,
                                                   Instant startTime, Instant endTime) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BusinessRuleException("幂等键不能为空");
        }
        BookingService.validateTimeRange(startTime, endTime);

        var replay = reservationRepository.findByIdempotencyKey(idempotencyKey);
        if (replay.isPresent()) {
            Reservation existing = replay.get();
            if (existing.getRearrangedFrom() != null
                    && existing.getRearrangedFrom().getId().equals(reservationId)
                    && existing.getStartTime().equals(startTime)
                    && existing.getEndTime().equals(endTime)) {
                return new BookingService.BookingOutcome(existing, false);
            }
            throw new IdempotencyConflictException("幂等键已使用且内容不一致: " + idempotencyKey);
        }

        // 先取路由信息（不加载实体），再按 望远镜 → 提案 → 预订 的顺序加锁，
        // 避免持久化上下文中的过期状态与抢占/取消并发时导致重复扣减或错误重排。
        var routing = reservationRepository.findRoutingById(reservationId)
                .orElseThrow(() -> new ResourceNotFoundException("预订不存在: " + reservationId));
        Telescope telescope = telescopeRepository.findByCodeForUpdate(routing.getTelescopeCode())
                .orElseThrow(() -> new ResourceNotFoundException("望远镜不存在"));
        Proposal proposal = proposalRepository.findByIdForUpdate(routing.getProposalId())
                .orElseThrow(() -> new ResourceNotFoundException("提案不存在"));
        Reservation original = reservationRepository.findByIdForUpdate(reservationId)
                .orElseThrow(() -> new ResourceNotFoundException("预订不存在: " + reservationId));

        if (original.getStatus() != ReservationStatus.PENDING_REARRANGE) {
            throw new BusinessRuleException("预订 " + reservationId + " 不在待重排状态，当前状态为 "
                    + original.getStatus());
        }
        long durationMinutes = Duration.between(startTime, endTime).toMinutes();
        if (durationMinutes != original.getDurationMinutes()) {
            throw new BusinessRuleException("重排时段时长必须与原预订一致，需要 "
                    + original.getDurationMinutes() + " 分钟，实际 " + durationMinutes + " 分钟");
        }
        if (proposal.getValidUntil() != null && endTime.isAfter(proposal.getValidUntil())) {
            throw new BusinessRuleException("重排时段超出提案 " + proposal.getCode()
                    + " 的剩余有效期（" + proposal.getValidUntil() + "）");
        }
        if (!telescope.supports(original.getInstrument())
                || !proposal.allows(original.getInstrument())) {
            throw new BusinessRuleException("重排使用的仪器不再可用: " + original.getInstrument());
        }

        Reservation rearranged = bookingService.createActiveReservation(
                idempotencyKey, proposal, telescope, original.getInstrument(),
                startTime, endTime, original);
        original.markPreemptedResolved(rearranged);
        telescope.incrementScheduleVersion();
        return new BookingService.BookingOutcome(rearranged, true);
    }

    @Transactional(readOnly = true)
    public Preemption findPreemption(String businessKey) {
        return preemptionRepository.findByBusinessKey(businessKey)
                .orElseThrow(() -> new ResourceNotFoundException("抢占业务号不存在: " + businessKey));
    }

    @Transactional(readOnly = true)
    public List<Reservation> pendingRearrangements(String proposalCode) {
        Proposal proposal = proposalRepository.findByCode(proposalCode)
                .orElseThrow(() -> new ResourceNotFoundException("提案不存在: " + proposalCode));
        return reservationRepository.findByStatusAndProposal_CodeOrderByPreemptedAtAscIdAsc(
                ReservationStatus.PENDING_REARRANGE, proposal.getCode());
    }

    private Proposal requireTooProposal(String proposalCode) {
        Proposal proposal = proposalRepository.findByCode(proposalCode)
                .orElseThrow(() -> new ResourceNotFoundException("提案不存在: " + proposalCode));
        if (!proposal.isTargetOpportunity()) {
            throw new BusinessRuleException("提案 " + proposalCode + " 不是目标机会提案，不能发起抢占");
        }
        return proposal;
    }

    /**
     * 计算抢占影响。返回全部被覆盖的可抢占预订（按开始时间、ID 稳定排序）、
     * 与窗口外前/后相邻有效预订的切换时间信息，以及按顺序累积的冲突原因。
     */
    private Impact computeImpact(Telescope telescope, Proposal tooProposal, String instrument,
                                 Instant startTime, Instant endTime) {
        List<String> reasons = new ArrayList<>();

        if (!telescope.supports(instrument)) {
            reasons.add("INSTRUMENT_NOT_SUPPORTED:望远镜 " + telescope.getCode()
                    + " 不支持仪器 " + instrument);
        }
        if (!tooProposal.allows(instrument)) {
            reasons.add("INSTRUMENT_NOT_ALLOWED:目标机会提案 " + tooProposal.getCode()
                    + " 不允许使用仪器 " + instrument);
        }
        if (tooProposal.getValidUntil() != null && !startTime.isBefore(tooProposal.getValidUntil())) {
            reasons.add("RESPONSE_DEADLINE_PASSED:抢占期望区间开始时间 " + startTime
                    + " 不早于目标机会提案 " + tooProposal.getCode() + " 的响应时限 "
                    + tooProposal.getValidUntil());
        }
        long durationMinutes = Duration.between(startTime, endTime).toMinutes();
        if (tooProposal.getRemainingQuotaMinutes() < durationMinutes) {
            reasons.add("QUOTA_INSUFFICIENT:目标机会提案 " + tooProposal.getCode()
                    + " 配额不足，需要 " + durationMinutes + " 分钟，剩余 "
                    + tooProposal.getRemainingQuotaMinutes() + " 分钟");
        }

        List<Reservation> overlapping = new ArrayList<>(
                reservationRepository.findByTelescopeAndStatusAndStartTimeLessThanAndEndTimeGreaterThan(
                        telescope, ReservationStatus.ACTIVE, endTime, startTime));
        overlapping.sort(Comparator.comparing(Reservation::getStartTime).thenComparing(Reservation::getId));

        List<Reservation> affected = new ArrayList<>();
        for (Reservation reservation : overlapping) {
            if (isPreemptable(reservation, tooProposal)) {
                affected.add(reservation);
            } else {
                Proposal owner = reservation.getProposal();
                reasons.add("NON_PREEMPTABLE_RESERVATION:预订 " + reservation.getId()
                        + " 属于不可抢占的目标机会提案 " + owner.getCode() + "（优先级 "
                        + owner.getPriority() + "，抢占方优先级 " + tooProposal.getPriority() + "）");
            }
        }

        long switchMinutes = telescope.getSwitchMinutes();
        NeighborGap gapBefore = reservationRepository
                .findFirstByTelescopeAndStatusAndEndTimeLessThanEqualOrderByEndTimeDescIdDesc(
                        telescope, ReservationStatus.ACTIVE, startTime)
                .map(previous -> new NeighborGap(previous,
                        gapOf(previous.getInstrument(), instrument, previous.getEndTime(), startTime,
                                switchMinutes)))
                .orElse(null);
        NeighborGap gapAfter = reservationRepository
                .findFirstByTelescopeAndStatusAndStartTimeGreaterThanEqualOrderByStartTimeAscIdAsc(
                        telescope, ReservationStatus.ACTIVE, endTime)
                .map(next -> new NeighborGap(next,
                        gapOf(instrument, next.getInstrument(), endTime, next.getStartTime(),
                                switchMinutes)))
                .orElse(null);
        if (gapBefore != null && !gapBefore.gap().ok()) {
            reasons.add("SWITCH_GAP_INSUFFICIENT:与前序预订 " + gapBefore.reservation().getId()
                    + "（仪器 " + gapBefore.reservation().getInstrument() + "）之间仅有 "
                    + gapBefore.gap().availableMinutes() + " 分钟，需要 "
                    + gapBefore.gap().requiredMinutes() + " 分钟切换时间");
        }
        if (gapAfter != null && !gapAfter.gap().ok()) {
            reasons.add("SWITCH_GAP_INSUFFICIENT:与后序预订 " + gapAfter.reservation().getId()
                    + "（仪器 " + gapAfter.reservation().getInstrument() + "）之间仅有 "
                    + gapAfter.gap().availableMinutes() + " 分钟，需要 "
                    + gapAfter.gap().requiredMinutes() + " 分钟切换时间");
        }

        return new Impact(affected, gapBefore, gapAfter, reasons);
    }

    /**
     * 普通提案预订一律可抢占；目标机会预订仅在其优先级严格低于抢占方时可抢占。
     */
    private boolean isPreemptable(Reservation reservation, Proposal tooProposal) {
        Proposal owner = reservation.getProposal();
        if (!owner.isTargetOpportunity()) {
            return true;
        }
        return owner.getPriority() < tooProposal.getPriority();
    }

    private static Gap gapOf(String beforeInstrument, String afterInstrument,
                             Instant gapStart, Instant gapEnd, long switchMinutes) {
        if (beforeInstrument.equals(afterInstrument)) {
            return new Gap(0, Duration.between(gapStart, gapEnd).toMinutes(), true);
        }
        long available = Duration.between(gapStart, gapEnd).toMinutes();
        return new Gap(switchMinutes, available, available >= switchMinutes);
    }

    private String snapshotSchedule(Telescope telescope) {
        List<ScheduleEntry> entries = reservationRepository
                .findByTelescopeAndStatusOrderByStartTimeAscIdAsc(telescope, ReservationStatus.ACTIVE)
                .stream()
                .map(ScheduleEntry::from)
                .toList();
        try {
            return objectMapper.writeValueAsString(entries);
        } catch (JacksonException exception) {
            throw new IllegalStateException("日程快照序列化失败", exception);
        }
    }

    /** 抢占影响的内部计算结果。 */
    private record Impact(List<Reservation> affected, NeighborGap gapBefore, NeighborGap gapAfter,
                          List<String> reasons) {
    }

    private record NeighborGap(Reservation reservation, Gap gap) {
    }

    private record Gap(long requiredMinutes, long availableMinutes, boolean ok) {
    }

    /** 有效日程快照条目（抢占前后保存为 JSON）。 */
    public record ScheduleEntry(Long reservationId, String proposalCode, String telescopeCode,
                                String instrument, Instant startTime, Instant endTime,
                                long durationMinutes) {

        public static ScheduleEntry from(Reservation reservation) {
            return new ScheduleEntry(reservation.getId(), reservation.getProposal().getCode(),
                    reservation.getTelescope().getCode(), reservation.getInstrument(),
                    reservation.getStartTime(), reservation.getEndTime(),
                    reservation.getDurationMinutes());
        }
    }

    /** 预览返回的单条被影响预订信息。 */
    public record AffectedReservation(Long reservationId, String proposalCode, String instrument,
                                      Instant startTime, Instant endTime, long durationMinutes) {

        public static AffectedReservation from(Reservation reservation) {
            return new AffectedReservation(reservation.getId(), reservation.getProposal().getCode(),
                    reservation.getInstrument(), reservation.getStartTime(), reservation.getEndTime(),
                    reservation.getDurationMinutes());
        }
    }

    /** 与前/后相邻预订之间的切换时间计算结果；无相邻预订时为 null。 */
    public record SwitchGap(Long neighborReservationId, String neighborInstrument,
                            long requiredMinutes, long availableMinutes, boolean ok) {

        static SwitchGap from(NeighborGap neighborGap) {
            if (neighborGap == null) {
                return null;
            }
            return new SwitchGap(neighborGap.reservation().getId(),
                    neighborGap.reservation().getInstrument(),
                    neighborGap.gap().requiredMinutes(),
                    neighborGap.gap().availableMinutes(),
                    neighborGap.gap().ok());
        }
    }

    public record PreemptionPreview(boolean feasible, List<String> reasons,
                                    List<AffectedReservation> affectedReservations,
                                    SwitchGap switchGapBefore, SwitchGap switchGapAfter) {
    }
}
