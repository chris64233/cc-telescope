package com.chris64233.cc.telescope.service;

import com.chris64233.cc.telescope.domain.OpportunityProposal;
import com.chris64233.cc.telescope.domain.PreemptionRecord;
import com.chris64233.cc.telescope.domain.PreemptionSnapshot;
import com.chris64233.cc.telescope.domain.PreemptionStatus;
import com.chris64233.cc.telescope.domain.Proposal;
import com.chris64233.cc.telescope.domain.QuotaChange;
import com.chris64233.cc.telescope.domain.Reservation;
import com.chris64233.cc.telescope.domain.ReservationStatus;
import com.chris64233.cc.telescope.domain.ScheduleEntry;
import com.chris64233.cc.telescope.domain.Telescope;
import com.chris64233.cc.telescope.domain.WeatherRecovery;
import com.chris64233.cc.telescope.repository.OpportunityProposalRepository;
import com.chris64233.cc.telescope.repository.PreemptionRecordRepository;
import com.chris64233.cc.telescope.repository.ProposalRepository;
import com.chris64233.cc.telescope.repository.ReservationRepository;
import com.chris64233.cc.telescope.repository.TelescopeRepository;
import com.chris64233.cc.telescope.repository.WeatherRecoveryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 目标机会观测（Target of Opportunity）对普通观测计划的受控抢占。
 *
 * <p>锁顺序（所有写事务统一，避免死锁）：
 * 望远镜行锁 → 机会提案行锁 → 该望远镜全部有效预订行锁 → 涉及的普通提案行锁（按 ID 升序）。
 */
@Service
public class PreemptionService {

    private final TelescopeRepository telescopeRepository;
    private final OpportunityProposalRepository opportunityRepository;
    private final ProposalRepository proposalRepository;
    private final ReservationRepository reservationRepository;
    private final PreemptionRecordRepository preemptionRecordRepository;
    private final WeatherRecoveryRepository weatherRecoveryRepository;
    private final BookingService bookingService;

    public PreemptionService(TelescopeRepository telescopeRepository,
                             OpportunityProposalRepository opportunityRepository,
                             ProposalRepository proposalRepository,
                             ReservationRepository reservationRepository,
                             PreemptionRecordRepository preemptionRecordRepository,
                             WeatherRecoveryRepository weatherRecoveryRepository,
                             BookingService bookingService) {
        this.telescopeRepository = telescopeRepository;
        this.opportunityRepository = opportunityRepository;
        this.proposalRepository = proposalRepository;
        this.reservationRepository = reservationRepository;
        this.preemptionRecordRepository = preemptionRecordRepository;
        this.weatherRecoveryRepository = weatherRecoveryRepository;
        this.bookingService = bookingService;
    }

    // ---------------------------------------------------------------------
    // 机会提案登记
    // ---------------------------------------------------------------------

    @Transactional
    public OpportunityProposal registerOpportunity(String code, int priority, Instant responseDeadline,
                                                    Set<String> allowedInstruments, long totalQuotaMinutes) {
        if (totalQuotaMinutes <= 0) {
            throw new BusinessRuleException("机会提案配额必须为正数");
        }
        if (allowedInstruments == null || allowedInstruments.isEmpty()) {
            throw new BusinessRuleException("机会提案至少需要允许一台仪器");
        }
        if (responseDeadline == null || !responseDeadline.isAfter(Instant.now())) {
            throw new BusinessRuleException("响应时限必须晚于当前时间");
        }
        opportunityRepository.findByCode(code).ifPresent(existing -> {
            throw new BusinessRuleException("机会提案编号已存在: " + code);
        });
        return opportunityRepository.save(new OpportunityProposal(
                code, priority, responseDeadline, allowedInstruments, totalQuotaMinutes));
    }

    @Transactional(readOnly = true)
    public OpportunityProposal opportunityQuota(String code) {
        return opportunityRepository.findByCode(code)
                .orElseThrow(() -> new ResourceNotFoundException("机会提案不存在: " + code));
    }

    // ---------------------------------------------------------------------
    // 试算
    // ---------------------------------------------------------------------

    /** 试算结果中与一侧相邻预订的切换时间影响。 */
    public record SwitchImpact(
            Long neighborReservationId,
            String neighborInstrument,
            long requiredSwitchMinutes,
            long availableGapMinutes,
            boolean feasible) {
    }

    /** 一次抢占试算结果（不落库）。 */
    public record PreemptionPlan(
            String opportunityCode,
            int opportunityPriority,
            String telescopeCode,
            String instrument,
            Instant startTime,
            Instant endTime,
            long durationMinutes,
            boolean feasible,
            List<String> conflictReasons,
            List<PreemptionSnapshot> affectedReservations,
            SwitchImpact switchBefore,
            SwitchImpact switchAfter,
            long opportunityRemainingBefore,
            long scheduleVersionBefore) {
    }

    @Transactional(readOnly = true)
    public PreemptionPlan plan(String opportunityCode, String telescopeCode, String instrument,
                               Instant startTime, Instant endTime) {
        validateTimeRange(startTime, endTime);
        Telescope telescope = telescopeRepository.findByCode(telescopeCode)
                .orElseThrow(() -> new ResourceNotFoundException("望远镜不存在: " + telescopeCode));
        OpportunityProposal opportunity = opportunityRepository.findByCode(opportunityCode)
                .orElseThrow(() -> new ResourceNotFoundException("机会提案不存在: " + opportunityCode));

        List<Reservation> active = reservationRepository
                .findByTelescopeAndStatusOrderByStartTimeAscIdAsc(telescope, ReservationStatus.ACTIVE);

        Evaluation evaluation = evaluate(telescope, opportunity, instrument,
                startTime, endTime, active, Instant.now());
        return new PreemptionPlan(opportunity.getCode(), opportunity.getPriority(), telescope.getCode(),
                instrument, startTime, endTime, evaluation.durationMinutes(), evaluation.feasible(),
                evaluation.reasons(), evaluation.snapshots(), evaluation.before(), evaluation.after(),
                opportunity.getRemainingQuotaMinutes(), telescope.getScheduleVersion());
    }

    // ---------------------------------------------------------------------
    // 确认抢占
    // ---------------------------------------------------------------------

    @Transactional
    public PreemptionRecord confirm(String businessKey, String opportunityCode, String telescopeCode,
                                    String instrument, Instant startTime, Instant endTime) {
        return confirmOutcome(businessKey, opportunityCode, telescopeCode, instrument, startTime, endTime)
                .record();
    }

    /** 确认抢占结果：{@code created=false} 表示业务号重放或被拒绝（首次拒绝也会落库）。 */
    public record ConfirmOutcome(PreemptionRecord record, boolean created) {
    }

    @Transactional
    public ConfirmOutcome confirmOutcome(String businessKey, String opportunityCode, String telescopeCode,
                                         String instrument, Instant startTime, Instant endTime) {
        validateTimeRange(startTime, endTime);

        Instant now = Instant.now();

        // 规范加锁顺序：望远镜行锁 → 全部相关配额账户行锁（按 类型+ID 全局排序）→ 预订行锁。
        Telescope telescope = telescopeRepository.findByCodeForUpdate(telescopeCode)
                .orElseThrow(() -> new ResourceNotFoundException("望远镜不存在: " + telescopeCode));

        // 幂等检查放在望远镜行锁之后：并发同业务号在锁上串行化，只落库一次
        var replay = preemptionRecordRepository.findByBusinessKey(businessKey);
        if (replay.isPresent()) {
            return new ConfirmOutcome(replayOrConflict(replay.get(), opportunityCode, telescopeCode,
                    instrument, startTime, endTime), false);
        }

        // 申请方机会提案的 ID（标量，不加载实体）；随后在账户加锁阶段统一按序锁定
        Long applicantOpportunityId = opportunityRepository.findIdByCode(opportunityCode)
                .orElseThrow(() -> new ResourceNotFoundException("机会提案不存在: " + opportunityCode));

        // 加锁前用标量快照定位将被覆盖的预订及其配额账户，避免实体进入一级缓存
        List<Object[]> scalars = reservationRepository
                .findOverlapScalars(telescope, ReservationStatus.ACTIVE);
        record OverlapScalar(long reservationId, boolean opportunityAccount, long accountId,
                             Instant s, Instant e, Long fundedWeatherRecoveryId) {
        }
        List<OverlapScalar> allActive = scalars.stream()
                .map(row -> new OverlapScalar(((Number) row[0]).longValue(),
                        Boolean.TRUE.equals(row[1]), ((Number) row[2]).longValue(),
                        (Instant) row[3], (Instant) row[4],
                        row[5] == null ? null : ((Number) row[5]).longValue()))
                .toList();
        List<OverlapScalar> overlapScalars = allActive.stream()
                .filter(v -> v.s().isBefore(endTime) && v.e().isAfter(startTime))
                .toList();

        // 账户集合：申请方机会账户 + 各被覆盖预订的退款账户（由天气恢复资格出资的预订不退配额，
        // 其分钟退回资格，故不锁定/变更其配额账户），按 (类型, ID) 全局排序后一次性加锁
        List<AccountKey> accountKeys = new java.util.ArrayList<>();
        accountKeys.add(new AccountKey(true, applicantOpportunityId));
        for (OverlapScalar v : overlapScalars) {
            if (v.fundedWeatherRecoveryId() == null) {
                accountKeys.add(new AccountKey(v.opportunityAccount(), v.accountId()));
            }
        }
        accountKeys.stream().distinct()
                .sorted(Comparator.comparing(AccountKey::opportunity).thenComparing(AccountKey::id))
                .forEach(this::lockAccount);

        // 被覆盖、由天气恢复资格出资的预订：在预订行锁之前先锁其恢复资格（统一锁序），
        // 抢占时把分钟退回资格而非退还配额。
        List<Long> fundedRecoveryIds = overlapScalars.stream()
                .map(OverlapScalar::fundedWeatherRecoveryId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
        for (Long recoveryId : fundedRecoveryIds) {
            weatherRecoveryRepository.findByIdForUpdate(recoveryId)
                    .orElseThrow(() -> new ResourceNotFoundException("天气恢复资格不存在: " + recoveryId));
        }

        OpportunityProposal opportunity = opportunityRepository.findById(applicantOpportunityId)
                .orElseThrow(() -> new ResourceNotFoundException("机会提案不存在: " + opportunityCode));

        // 账户锁定后再首次加锁加载有效预订行（实体首次即加锁，读到最新状态）
        List<Reservation> active = reservationRepository
                .findActiveByTelescopeForUpdate(telescope, ReservationStatus.ACTIVE);

        Evaluation evaluation = evaluate(telescope, opportunity, instrument,
                startTime, endTime, active, now);

        List<ScheduleEntry> scheduleBefore = active.stream().map(ScheduleEntry::from).toList();

        if (!evaluation.feasible()) {
            PreemptionRecord rejected = new PreemptionRecord(businessKey, opportunity.getCode(),
                    telescope.getCode(), instrument, startTime, endTime, evaluation.durationMinutes(),
                    PreemptionStatus.REJECTED, telescope.getScheduleVersion(), telescope.getScheduleVersion(),
                    evaluation.before() == null ? 0 : evaluation.before().requiredSwitchMinutes(),
                    evaluation.after() == null ? 0 : evaluation.after().requiredSwitchMinutes(),
                    null, evaluation.snapshots(), List.of(), evaluation.reasons(),
                    scheduleBefore, scheduleBefore, now);
            return new ConfirmOutcome(preemptionRecordRepository.saveAndFlush(rejected), true);
        }

        List<Reservation> overlapped = active.stream()
                .filter(r -> r.getStartTime().isBefore(endTime) && r.getEndTime().isAfter(startTime))
                .toList();

        List<QuotaChange> quotaChanges = new ArrayList<>();
        // 由恢复资格出资的新预订被抢占取消后，若其原预订已进入 RECOVERED 终态，需要在统一顺序下
        // 额外锁定并重开原预订行（同望远镜、按 ID 顺序），收集于此。
        Set<Long> extraReservationIds = new java.util.TreeSet<>();

        for (Reservation reservation : overlapped) {
            WeatherRecovery funded = reservation.getFundedByWeatherRecovery();
            if (funded != null) {
                // 恢复资格出资的预订：不退配额，分钟退回资格；该预订不进入待重排，直接取消
                boolean reopened = funded.releaseBack(reservation.getDurationMinutes(),
                        reservation.getId(), now);
                quotaChanges.add(new QuotaChange(
                        reservation.isOpportunityReservation() ? "OPPORTUNITY" : "NORMAL",
                        reservation.getOwnerCode(), reservation.getDurationMinutes(),
                        "RETURN_WEATHER_RECOVERY"));
                reservation.markPreemptedAndCancelled(opportunity, businessKey, now);
                if (reopened) {
                    extraReservationIds.add(funded.getOriginalReservation().getId());
                }
            } else if (reservation.isOpportunityReservation()) {
                reservation.getOpportunity().refund(reservation.getDurationMinutes());
                quotaChanges.add(new QuotaChange("OPPORTUNITY", reservation.getOwnerCode(),
                        reservation.getDurationMinutes(), "REFUND_PREEMPTED"));
                // 机会预订不进入待重排，直接取消
                reservation.markPreemptedAndCancelled(opportunity, businessKey, now);
            } else {
                reservation.getProposal().refund(reservation.getDurationMinutes());
                quotaChanges.add(new QuotaChange("NORMAL", reservation.getOwnerCode(),
                        reservation.getDurationMinutes(), "REFUND_PREEMPTED"));
                reservation.markPreempted(opportunity, businessKey, now);
            }
        }

        // 统一顺序锁定并重开因全额恢复而处于 RECOVERED 的原预订（其天气事件关联仍保留）
        if (!extraReservationIds.isEmpty()) {
            List<Reservation> extra = reservationRepository.findByIdInForUpdate(extraReservationIds);
            for (Reservation original : extra) {
                if (original.getStatus() == ReservationStatus.RECOVERED) {
                    original.reopenFromRecovered(now);
                }
            }
        }

        opportunity.deduct(evaluation.durationMinutes());
        quotaChanges.add(new QuotaChange("OPPORTUNITY", opportunity.getCode(),
                -evaluation.durationMinutes(), "CONSUME_OPPORTUNITY"));

        Reservation opportunityReservation = reservationRepository.save(new Reservation(
                businessKey, null, opportunity, opportunity.getPriority(), telescope, instrument,
                startTime, endTime, evaluation.durationMinutes()));

        long versionBefore = telescope.getScheduleVersion();
        long versionAfter = telescope.bumpScheduleVersion();

        List<ScheduleEntry> scheduleAfter = new ArrayList<>(scheduleBefore.stream()
                .filter(e -> overlapped.stream().noneMatch(r -> r.getId().equals(e.getReservationId())))
                .toList());
        scheduleAfter.add(ScheduleEntry.from(opportunityReservation));
        scheduleAfter.sort(Comparator.comparing(ScheduleEntry::getStartTime)
                .thenComparing(ScheduleEntry::getReservationId));

        PreemptionRecord confirmed = new PreemptionRecord(businessKey, opportunity.getCode(),
                telescope.getCode(), instrument, startTime, endTime, evaluation.durationMinutes(),
                PreemptionStatus.CONFIRMED, versionBefore, versionAfter,
                evaluation.before() == null ? 0 : evaluation.before().requiredSwitchMinutes(),
                evaluation.after() == null ? 0 : evaluation.after().requiredSwitchMinutes(),
                opportunityReservation.getId(), evaluation.snapshots(), quotaChanges, List.of(),
                scheduleBefore, scheduleAfter, now);
        return new ConfirmOutcome(preemptionRecordRepository.saveAndFlush(confirmed), true);
    }

    /** 配额账户定位键：opportunity=true 表示机会提案账户，否则为普通提案账户。 */
    private record AccountKey(boolean opportunity, Long id) {
    }

    private void lockAccount(AccountKey key) {
        if (key.opportunity()) {
            opportunityRepository.findByIdForUpdate(key.id())
                    .orElseThrow(() -> new ResourceNotFoundException("机会提案不存在: " + key.id()));
        } else {
            proposalRepository.findByIdForUpdate(key.id())
                    .orElseThrow(() -> new ResourceNotFoundException("提案不存在: " + key.id()));
        }
    }

    private PreemptionRecord replayOrConflict(PreemptionRecord existing, String opportunityCode,
                                              String telescopeCode, String instrument,
                                              Instant startTime, Instant endTime) {
        if (existing.getOpportunityCode().equals(opportunityCode)
                && existing.getTelescopeCode().equals(telescopeCode)
                && existing.getInstrument().equals(instrument)
                && existing.getRequestedStart().equals(startTime)
                && existing.getRequestedEnd().equals(endTime)) {
            return existing;
        }
        throw new IdempotencyConflictException("抢占业务号已使用且申请内容不一致: " + existing.getBusinessKey());
    }

    // ---------------------------------------------------------------------
    // 待重排任务
    // ---------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Reservation> pendingReschedules(String opportunityCode) {
        if (opportunityRepository.findByCode(opportunityCode).isEmpty()) {
            throw new ResourceNotFoundException("机会提案不存在: " + opportunityCode);
        }
        return reservationRepository.findPendingForReschedule(
                ReservationStatus.PENDING_RESCHEDULE, opportunityCode);
    }

    // ---------------------------------------------------------------------
    // 重排
    // ---------------------------------------------------------------------

    public record RescheduleOutcome(Reservation newReservation, boolean created) {
    }

    /**
     * 将被抢占的待重排预订安排到新时段。仅重排成功才再次扣减配额；
     * 重排失败（时间冲突/配额不足/超过有效期）不改变任何状态与配额。
     */
    @Transactional
    public RescheduleOutcome reschedule(Long reservationId, String idempotencyKey,
                                        String telescopeCode, String instrument,
                                        Instant startTime, Instant endTime) {
        validateTimeRange(startTime, endTime);

        var replay = reservationRepository.findByIdempotencyKey(idempotencyKey);
        if (replay.isPresent()) {
            Reservation existing = replay.get();
            if (existing.matches(ownerCodeOf(existing), telescopeCode, instrument, startTime, endTime)) {
                return new RescheduleOutcome(existing, false);
            }
            throw new IdempotencyConflictException("幂等键已使用且内容不一致: " + idempotencyKey);
        }

        // 规范加锁顺序：目标望远镜行锁 → 提案配额账户行锁 → 待重排预订行锁（首次即加锁加载）。
        // 待重排预订已不占原望远镜日程，故只需锁其行以串行化同一预订的并发重排。
        Telescope telescope = telescopeRepository.findByCodeForUpdate(telescopeCode)
                .orElseThrow(() -> new ResourceNotFoundException("望远镜不存在: " + telescopeCode));

        // 用单列标量定位账户类型（不加载预订实体进一级缓存），随后按规范在预订行之前锁定账户
        Long normalAccountId = reservationRepository.findProposalIdById(reservationId);
        Long opportunityAccountId = reservationRepository.findOpportunityIdById(reservationId);
        if (normalAccountId == null && opportunityAccountId == null) {
            throw new ResourceNotFoundException("预订不存在: " + reservationId);
        }
        if (opportunityAccountId != null) {
            // 先加锁再加载实体，下面状态检查会给出明确错误
            opportunityRepository.findByIdForUpdate(opportunityAccountId);
        } else {
            proposalRepository.findByIdForUpdate(normalAccountId);
        }

        Reservation pending = reservationRepository.findByIdForUpdate(reservationId)
                .orElseThrow(() -> new ResourceNotFoundException("预订不存在: " + reservationId));

        if (pending.getStatus() == ReservationStatus.RESCHEDULED) {
            Reservation already = reservationRepository.findById(pending.getRescheduledToId())
                    .orElseThrow(() -> new ResourceNotFoundException("重排后的预订不存在"));
            return new RescheduleOutcome(already, false);
        }
        if (pending.getStatus() != ReservationStatus.PENDING_RESCHEDULE) {
            throw new RescheduleNotAllowedException(
                    "预订不是待重排状态，不能重排，当前状态: " + pending.getStatus());
        }
        if (pending.isOpportunityReservation()) {
            throw new RescheduleNotAllowedException("机会预订不参与重排");
        }

        OpportunityProposal preemptedBy = pending.getPreemptedBy();
        Instant now = Instant.now();
        if (preemptedBy.deadlineReached(now)) {
            throw new RescheduleNotAllowedException("抢占提案响应时限已到，不能再重排");
        }
        if (endTime.isAfter(preemptedBy.getResponseDeadline())) {
            throw new RescheduleNotAllowedException("新时段结束时间超过抢占提案的响应时限: "
                    + preemptedBy.getResponseDeadline());
        }

        String effectiveInstrument = instrument != null ? instrument : pending.getInstrument();
        if (!telescope.supports(effectiveInstrument)) {
            throw new BusinessRuleException("望远镜 " + telescopeCode + " 不支持仪器 " + effectiveInstrument);
        }

        // 提案账户已加锁并在持久化上下文中
        Proposal proposal = pending.getProposal();
        if (!proposal.allows(effectiveInstrument)) {
            throw new BusinessRuleException("提案 " + proposal.getCode() + " 不允许使用仪器 " + effectiveInstrument);
        }

        long durationMinutes = Duration.between(startTime, endTime).toMinutes();
        if (proposal.getRemainingQuotaMinutes() < durationMinutes) {
            throw new BusinessRuleException("提案 " + proposal.getCode() + " 剩余配额不足，需要 "
                    + durationMinutes + " 分钟，剩余 " + proposal.getRemainingQuotaMinutes() + " 分钟");
        }

        List<Reservation> active = reservationRepository
                .findActiveByTelescopeForUpdate(telescope, ReservationStatus.ACTIVE);
        bookingService.validateAgainstSchedule(telescope, effectiveInstrument, startTime, endTime, active);

        proposal.deduct(durationMinutes);
        Reservation rebooked = reservationRepository.save(new Reservation(
                idempotencyKey, proposal, telescope, effectiveInstrument, startTime, endTime, durationMinutes));
        pending.markRescheduled(rebooked.getId(), now);
        return new RescheduleOutcome(rebooked, true);
    }

    private static String ownerCodeOf(Reservation reservation) {
        return reservation.getOwnerCode();
    }

    // ---------------------------------------------------------------------
    // 抢占记录查询
    // ---------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PreemptionRecord findRecord(String businessKey) {
        return preemptionRecordRepository.findByBusinessKey(businessKey)
                .orElseThrow(() -> new ResourceNotFoundException("抢占记录不存在: " + businessKey));
    }

    @Transactional(readOnly = true)
    public List<PreemptionRecord> recordsByOpportunity(String opportunityCode) {
        if (opportunityRepository.findByCode(opportunityCode).isEmpty()) {
            throw new ResourceNotFoundException("机会提案不存在: " + opportunityCode);
        }
        return preemptionRecordRepository.findByOpportunityCodeOrderByIdAsc(opportunityCode);
    }

    // ---------------------------------------------------------------------
    // 评估逻辑（试算与确认共用）
    // ---------------------------------------------------------------------

    private record Evaluation(
            long durationMinutes,
            boolean feasible,
            List<String> reasons,
            List<PreemptionSnapshot> snapshots,
            SwitchImpact before,
            SwitchImpact after) {
    }

    private Evaluation evaluate(Telescope telescope, OpportunityProposal opportunity, String instrument,
                                Instant startTime, Instant endTime, List<Reservation> active, Instant now) {
        long durationMinutes = Duration.between(startTime, endTime).toMinutes();
        List<String> reasons = new ArrayList<>();

        if (!telescope.supports(instrument)) {
            reasons.add("望远镜 " + telescope.getCode() + " 不支持仪器 " + instrument);
        }
        if (!opportunity.allows(instrument)) {
            reasons.add("机会提案 " + opportunity.getCode() + " 不允许使用仪器 " + instrument);
        }
        if (opportunity.deadlineReached(now)) {
            reasons.add("机会提案响应时限已到: " + opportunity.getResponseDeadline());
        } else if (endTime.isAfter(opportunity.getResponseDeadline())) {
            reasons.add("期望区间结束时间 " + endTime + " 晚于响应时限 " + opportunity.getResponseDeadline());
        }
        if (opportunity.getRemainingQuotaMinutes() < durationMinutes) {
            reasons.add("机会提案配额不足：需要 " + durationMinutes + " 分钟，剩余 "
                    + opportunity.getRemainingQuotaMinutes() + " 分钟");
        }

        List<Reservation> overlapped = active.stream()
                .filter(r -> r.getStartTime().isBefore(endTime) && r.getEndTime().isAfter(startTime))
                .sorted(Comparator.comparing(Reservation::getStartTime).thenComparing(Reservation::getId))
                .toList();

        List<PreemptionSnapshot> snapshots = new ArrayList<>();
        for (Reservation r : overlapped) {
            boolean preemptable = !r.isOpportunityReservation()
                    || opportunity.getPriority() > r.getPriority();
            if (r.isOpportunityReservation() && !preemptable) {
                reasons.add("预订 " + r.getId() + " 属于机会提案 " + r.getOwnerCode()
                        + "（优先级 " + r.getPriority() + "），优先级不低于本提案（"
                        + opportunity.getPriority() + "），不可抢占");
            }
            snapshots.add(new PreemptionSnapshot(r.getId(),
                    r.isOpportunityReservation() ? "OPPORTUNITY" : "NORMAL",
                    r.getOwnerCode(), r.getInstrument(), r.getStartTime(), r.getEndTime(),
                    r.getDurationMinutes(), r.getStatus().name(), preemptable, r.getPriority()));
        }
        if (snapshots.stream().anyMatch(s -> !s.isPreemptable())) {
            reasons.add("存在不可抢占的预订，抢占必须一次性覆盖全部被覆盖预订");
        }

        List<Reservation> remaining = active.stream().filter(r -> overlapped.stream()
                .noneMatch(o -> o.getId().equals(r.getId()))).toList();
        SwitchImpact before = switchImpact(
                remaining.stream()
                        .filter(r -> !r.getEndTime().isAfter(startTime))
                        .max(Comparator.comparing(Reservation::getEndTime).thenComparing(Reservation::getId))
                        .orElse(null),
                instrument, telescope.getSwitchMinutes(), true, startTime);
        SwitchImpact after = switchImpact(
                remaining.stream()
                        .filter(r -> !r.getStartTime().isBefore(endTime))
                        .min(Comparator.comparing(Reservation::getStartTime).thenComparing(Reservation::getId))
                        .orElse(null),
                instrument, telescope.getSwitchMinutes(), false, endTime);
        if (before != null && !before.feasible()) {
            reasons.add("期望区间与前序预订 " + before.neighborReservationId() + "（仪器 "
                    + before.neighborInstrument() + "）之间仅 " + before.availableGapMinutes()
                    + " 分钟空闲，切换至少需要 " + before.requiredSwitchMinutes() + " 分钟");
        }
        if (after != null && !after.feasible()) {
            reasons.add("期望区间与后序预订 " + after.neighborReservationId() + "（仪器 "
                    + after.neighborInstrument() + "）之间仅 " + after.availableGapMinutes()
                    + " 分钟空闲，切换至少需要 " + after.requiredSwitchMinutes() + " 分钟");
        }

        return new Evaluation(durationMinutes, reasons.isEmpty(), List.copyOf(reasons),
                List.copyOf(snapshots), before, after);
    }

    private SwitchImpact switchImpact(Reservation neighbor, String requestedInstrument,
                                      long switchMinutes, boolean predecessor, Instant boundary) {
        if (neighbor == null) {
            return null;
        }
        boolean sameInstrument = neighbor.getInstrument().equals(requestedInstrument);
        long required = sameInstrument ? 0 : switchMinutes;
        Instant gapStart = predecessor ? neighbor.getEndTime() : boundary;
        Instant gapEnd = predecessor ? boundary : neighbor.getStartTime();
        long available = Duration.between(gapStart, gapEnd).toMinutes();
        return new SwitchImpact(neighbor.getId(), neighbor.getInstrument(), required, available,
                available >= required);
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
}
