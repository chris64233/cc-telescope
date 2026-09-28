package com.chris64233.cc.telescope.service;

import com.chris64233.cc.telescope.domain.OpportunityProposal;
import com.chris64233.cc.telescope.domain.Proposal;
import com.chris64233.cc.telescope.domain.Reservation;
import com.chris64233.cc.telescope.domain.ReservationStatus;
import com.chris64233.cc.telescope.domain.Telescope;
import com.chris64233.cc.telescope.domain.WeatherAffectedRecord;
import com.chris64233.cc.telescope.domain.WeatherAffectedStatus;
import com.chris64233.cc.telescope.domain.WeatherEvent;
import com.chris64233.cc.telescope.repository.OpportunityProposalRepository;
import com.chris64233.cc.telescope.repository.ProposalRepository;
import com.chris64233.cc.telescope.repository.ReservationRepository;
import com.chris64233.cc.telescope.repository.TelescopeRepository;
import com.chris64233.cc.telescope.repository.WeatherAffectedRecordRepository;
import com.chris64233.cc.telescope.repository.WeatherEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 天气关闭与受影响观测的恢复排期。
 *
 * <p>锁顺序（与预订/抢占/重排/取消全局一致，消除跨望远镜死锁）：
 * <strong>望远镜行锁 → 天气事件行锁（仅范围调整/放弃）→ 配额账户行锁（按 类型+ID 全局排序）
 * → 预订行锁 → 受影响记录行锁</strong>。
 *
 * <p>关键不变量：
 * <ul>
 *     <li>关闭/范围调整只中断与关闭窗口重叠且<strong>尚未开始</strong>的 ACTIVE 观测；
 *     已完成或正在执行（已开始）的观测保持不变；</li>
 *     <li>分钟数的释放（关闭）与重新扣减（还原/恢复）都在持有对应账户行锁时进行，
 *     配合提案取消与恢复排期的行锁，并发下不会重复释放或重复消耗；</li>
 *     <li>恢复排期以幂等键保证幂等；新时段所有校验在任何状态/配额变更之前完成，
 *     校验失败整笔回滚，不留下部分占用。</li>
 * </ul>
 */
@Service
public class WeatherService {

    private final TelescopeRepository telescopeRepository;
    private final WeatherEventRepository weatherEventRepository;
    private final WeatherAffectedRecordRepository affectedRepository;
    private final ReservationRepository reservationRepository;
    private final ProposalRepository proposalRepository;
    private final OpportunityProposalRepository opportunityRepository;
    private final BookingService bookingService;

    public WeatherService(TelescopeRepository telescopeRepository,
                          WeatherEventRepository weatherEventRepository,
                          WeatherAffectedRecordRepository affectedRepository,
                          ReservationRepository reservationRepository,
                          ProposalRepository proposalRepository,
                          OpportunityProposalRepository opportunityRepository,
                          BookingService bookingService) {
        this.telescopeRepository = telescopeRepository;
        this.weatherEventRepository = weatherEventRepository;
        this.affectedRepository = affectedRepository;
        this.reservationRepository = reservationRepository;
        this.proposalRepository = proposalRepository;
        this.opportunityRepository = opportunityRepository;
        this.bookingService = bookingService;
    }

    /** 配额账户定位键：opportunity=true 表示机会提案账户，否则为普通提案账户。 */
    private record AccountKey(boolean opportunity, long id) {
    }

    // ---------------------------------------------------------------------
    // 天气关闭
    // ---------------------------------------------------------------------

    public record CloseOutcome(WeatherEvent event, boolean created, List<WeatherAffectedRecord> affected) {
    }

    /**
     * 登记天气关闭事件并原子中断受影响的未开始观测。
     *
     * @param created false 表示相同业务号的重放（返回原事件，不重复释放）
     */
    @Transactional
    public CloseOutcome close(String businessKey, String telescopeCode, String reason,
                              Instant startTime, Instant endTime) {
        validateTimeRange(startTime, endTime);
        Instant now = Instant.now();

        // 望远镜行锁：使该望远镜上的日程变更全局串行化
        Telescope telescope = telescopeRepository.findByCodeForUpdate(telescopeCode)
                .orElseThrow(() -> new ResourceNotFoundException("望远镜不存在: " + telescopeCode));

        // 幂等检查置于望远镜行锁之后：并发同业务号在此串行化，只生效一次
        var replay = weatherEventRepository.findByBusinessKey(businessKey);
        if (replay.isPresent()) {
            WeatherEvent existing = replay.get();
            return new CloseOutcome(existing, false, replayOrConflict(existing, telescopeCode, startTime, endTime));
        }

        List<Reservation> toInterrupt = lockAndLoadInterruptible(telescope, startTime, endTime, now);

        long versionBefore = telescope.getScheduleVersion();
        long versionAfter = toInterrupt.isEmpty() ? versionBefore : telescope.bumpScheduleVersion();

        WeatherEvent event = new WeatherEvent(businessKey, telescope,
                reason == null || reason.isBlank() ? "天气关闭" : reason,
                startTime, endTime, versionBefore, versionAfter, now);
        WeatherEvent saved = weatherEventRepository.saveAndFlush(event);

        for (Reservation r : toInterrupt) {
            refund(r);
            r.markWeatherCancelled(saved, now);
            affectedRepository.save(new WeatherAffectedRecord(saved, r,
                    r.isOpportunityReservation() ? "OPPORTUNITY" : "NORMAL", r.getOwnerCode(),
                    r.getPriority(), r.getInstrument(), r.getStartTime(), r.getEndTime(),
                    r.getDurationMinutes(), now));
        }
        affectedRepository.flush();
        return new CloseOutcome(saved, true, affectedRepository.findDetailedByEventKey(businessKey));
    }

    // ---------------------------------------------------------------------
    // 天气范围调整（扩大 / 缩小）
    // ---------------------------------------------------------------------

    /**
     * 在原天气事件业务号上调整关闭窗口。扩大窗口会中断新覆盖到的未开始观测；
     * 缩小窗口会尝试还原不再受影响的观测（需原时段仍可用、配额充足），无法还原的保持中断。
     */
    @Transactional
    public CloseOutcome adjustWindow(String businessKey, String telescopeCode,
                                     Instant newStart, Instant newEnd) {
        validateTimeRange(newStart, newEnd);
        Instant now = Instant.now();

        Telescope telescope = telescopeRepository.findByCodeForUpdate(telescopeCode)
                .orElseThrow(() -> new ResourceNotFoundException("望远镜不存在: " + telescopeCode));

        WeatherEvent event = weatherEventRepository.findByBusinessKeyForUpdate(businessKey)
                .orElseThrow(() -> new ResourceNotFoundException("天气关闭事件不存在: " + businessKey));
        if (!event.getTelescope().getCode().equals(telescopeCode)) {
            throw new IdempotencyConflictException("天气关闭业务号与望远镜不匹配: " + businessKey);
        }
        if (event.getStartTime().equals(newStart) && event.getEndTime().equals(newEnd)) {
            // 相同窗口重放：幂等返回，不重复释放/消耗
            return new CloseOutcome(event, false,
                    affectedRepository.findDetailedByEventKey(businessKey));
        }

        boolean changed = false;

        // 扩大：中断新窗口覆盖且当前仍 ACTIVE 的未开始观测
        List<Reservation> interrupted = lockAndLoadInterruptible(telescope, newStart, newEnd, now);
        for (Reservation r : interrupted) {
            refund(r);
            r.markWeatherCancelled(event, now);
            affectedRepository.save(new WeatherAffectedRecord(event, r,
                    r.isOpportunityReservation() ? "OPPORTUNITY" : "NORMAL", r.getOwnerCode(),
                    r.getPriority(), r.getInstrument(), r.getStartTime(), r.getEndTime(),
                    r.getDurationMinutes(), now));
        }
        if (!interrupted.isEmpty()) {
            changed = true;
        }

        // 缩小：还原新窗口不再覆盖、且仍待恢复（AFFECTED）的观测。
        // 先用标量定位候选与其账户，按规范 账户 → 受影响记录行 的顺序加锁，避免与恢复排期互锁。
        List<Object[]> affectedScalars = affectedRepository
                .findScalarsByEventAndStatus(event, WeatherAffectedStatus.AFFECTED);
        record AffectedScalar(long affectedId, boolean opportunityOwner, long accountId,
                              Instant os, Instant oe) {
        }
        List<AffectedScalar> outside = affectedScalars.stream()
                .map(row -> new AffectedScalar(((Number) row[0]).longValue(),
                        Boolean.TRUE.equals(row[1]), ((Number) row[2]).longValue(),
                        (Instant) row[3], (Instant) row[4]))
                .filter(v -> !overlaps(v.os(), v.oe(), newStart, newEnd))
                .toList();
        if (!outside.isEmpty()) {
            outside.stream()
                    .map(v -> new AccountKey(v.opportunityOwner(), v.accountId()))
                    .distinct()
                    .sorted(Comparator.comparing(AccountKey::opportunity).thenComparing(AccountKey::id))
                    .forEach(this::lockAccount);

            List<Long> candidateIds = outside.stream().map(AffectedScalar::affectedId).toList();
            List<WeatherAffectedRecord> locked = affectedRepository
                    .findByEventAndStatusForUpdate(event, WeatherAffectedStatus.AFFECTED).stream()
                    .filter(a -> candidateIds.contains(a.getId()))
                    .toList();
            changed |= restoreRecords(telescope, locked, now);
        }

        long versionAfter = changed ? telescope.bumpScheduleVersion() : telescope.getScheduleVersion();
        event.applyWindow(newStart, newEnd, versionAfter, now);
        weatherEventRepository.saveAndFlush(event);
        return new CloseOutcome(event, true, affectedRepository.findDetailedByEventKey(businessKey));
    }

    /**
     * 锁定候选预订的配额账户并首次加锁加载预订，返回与窗口重叠且<strong>尚未开始</strong>
     * （startTime &gt;= now）的 ACTIVE 预订。已开始或已结束（正在执行/已完成）的预订不触碰。
     * 账户按 (类型, ID) 全局排序加锁。
     */
    private List<Reservation> lockAndLoadInterruptible(Telescope telescope, Instant windowStart,
                                                       Instant windowEnd, Instant now) {
        List<Object[]> scalars = reservationRepository
                .findScalarsByTelescopeAndStatus(telescope, ReservationStatus.ACTIVE);
        List<Long> candidateIds = scalars.stream()
                .map(row -> new Scalar(((Number) row[0]).longValue(), Boolean.TRUE.equals(row[1]),
                        ((Number) row[2]).longValue(), (Instant) row[3], (Instant) row[4]))
                .filter(v -> overlaps(v.s(), v.e(), windowStart, windowEnd) && !v.s().isBefore(now))
                .map(Scalar::id)
                .toList();
        if (candidateIds.isEmpty()) {
            return List.of();
        }

        // 账户按 (类型, ID) 全局排序后一次性加锁（标量，不把预订实体加载进一级缓存）
        scalars.stream()
                .map(row -> new Scalar(((Number) row[0]).longValue(), Boolean.TRUE.equals(row[1]),
                        ((Number) row[2]).longValue(), (Instant) row[3], (Instant) row[4]))
                .filter(v -> candidateIds.contains(v.id()))
                .map(v -> new AccountKey(v.opportunity(), v.accountId()))
                .distinct()
                .sorted(Comparator.comparing(AccountKey::opportunity).thenComparing(AccountKey::id))
                .forEach(this::lockAccount);

        // 首次即加锁加载 ACTIVE 预订行，读到最新状态后再判定一次
        return reservationRepository
                .findByTelescopeAndStatusForUpdate(telescope, ReservationStatus.ACTIVE).stream()
                .filter(r -> overlaps(r.getStartTime(), r.getEndTime(), windowStart, windowEnd)
                        && !r.getStartTime().isBefore(now))
                .sorted(Comparator.comparing(Reservation::getStartTime).thenComparing(Reservation::getId))
                .toList();
    }

    private record Scalar(long id, boolean opportunity, long accountId, Instant s, Instant e) {
    }

    /** 释放预订占用的分钟数到其配额账户（调用方须已持有该账户行锁）。 */
    private void refund(Reservation r) {
        if (r.isOpportunityReservation()) {
            r.getOpportunity().refund(r.getDurationMinutes());
        } else {
            r.getProposal().refund(r.getDurationMinutes());
        }
    }

    /**
     * 缩小窗口时还原受影响观测：重新扣减配额、把原预订恢复为 ACTIVE（需原时段仍空闲）。
     * 任一候选取回失败（原时段已到/配额不足/时段冲突）则跳过、保持 AFFECTED。
     * 调用方须已按规范锁定相关账户。返回是否发生了实际还原。
     */
    private boolean restoreRecords(Telescope telescope,
                                   List<WeatherAffectedRecord> candidates, Instant now) {
        List<Reservation> actives = new ArrayList<>(reservationRepository
                .findByTelescopeAndStatusForUpdate(telescope, ReservationStatus.ACTIVE));

        boolean any = false;
        for (WeatherAffectedRecord a : candidates) {
            Reservation original = a.getOriginalReservation();
            // 已有部分恢复排期占用了分钟：原时段不能整段放回（否则重复消耗），保持待恢复
            if (!a.getRecoveryBookings().isEmpty()) {
                continue;
            }
            // 原时段已到/已过，不能再放回日程
            if (!original.getStartTime().isAfter(now)) {
                continue;
            }
            long minutes = original.getDurationMinutes();
            if (a.isOpportunityOwner()) {
                OpportunityProposal opportunity = original.getOpportunity();
                if (opportunity.getRemainingQuotaMinutes() < minutes) {
                    continue;
                }
            } else {
                Proposal proposal = original.getProposal();
                if (proposal.getRemainingQuotaMinutes() < minutes) {
                    continue;
                }
            }
            // 原时段是否仍空闲（与现有 ACTIVE 预订无冲突且满足切换间隔）
            try {
                bookingService.validateAgainstSchedule(telescope, original.getInstrument(),
                        original.getStartTime(), original.getEndTime(), actives);
            } catch (RuntimeException conflict) {
                continue;
            }
            // 通过：重新扣减、恢复有效、凭证失效
            if (a.isOpportunityOwner()) {
                original.getOpportunity().deduct(minutes);
            } else {
                original.getProposal().deduct(minutes);
            }
            original.restoreFromWeather(now);
            a.markRestored(now);
            actives.add(original);
            any = true;
        }
        return any;
    }

    // ---------------------------------------------------------------------
    // 恢复排期
    // ---------------------------------------------------------------------

    public record RecoveryOutcome(Reservation newReservation, boolean created,
                                  WeatherAffectedRecord affected) {
    }

    /**
     * 受影响提案使用原优先级与剩余可恢复分钟数申请新时段。
     * 幂等：相同幂等键重放返回原预订；内容不一致返回 409。
     * 新时段任何校验失败都整笔回滚，不扣减可恢复分钟数/配额、不建立预订。
     */
    @Transactional
    public RecoveryOutcome recover(Long affectedId, String idempotencyKey, String telescopeCode,
                                   String instrument, Instant startTime, Instant endTime) {
        validateTimeRange(startTime, endTime);

        // 目标望远镜行锁（最先获取，使该望远镜上的日程写全局串行化）
        Telescope telescope = telescopeRepository.findByCodeForUpdate(telescopeCode)
                .orElseThrow(() -> new ResourceNotFoundException("望远镜不存在: " + telescopeCode));

        // 幂等检查放在望远镜行锁之后：并发同键在此串行化，只建立一次新预订、只扣一次分钟数
        var replay = reservationRepository.findByIdempotencyKey(idempotencyKey);
        if (replay.isPresent()) {
            Reservation existing = replay.get();
            String requestedInstrument = instrument != null ? instrument : existing.getInstrument();
            if (existing.getTelescope().getCode().equals(telescopeCode)
                    && existing.getInstrument().equals(requestedInstrument)
                    && existing.getStartTime().equals(startTime)
                    && existing.getEndTime().equals(endTime)) {
                WeatherAffectedRecord link = affectedRepository.findByRecoveryBookingId(existing.getId())
                        .filter(a -> a.getId().equals(affectedId))
                        .orElseThrow(() -> new IdempotencyConflictException(
                                "幂等键已被无关预订占用: " + idempotencyKey));
                return new RecoveryOutcome(existing, false, link);
            }
            throw new IdempotencyConflictException("幂等键已使用且内容不一致: " + idempotencyKey);
        }

        // 标量定位受影响记录的配额账户（不把预订实体加载进一级缓存），账户先于受影响记录加锁
        List<Object[]> accountScalars = affectedRepository.findAccountScalarById(affectedId);
        if (accountScalars.isEmpty()) {
            throw new ResourceNotFoundException("天气受影响记录不存在: " + affectedId);
        }
        Object[] accountScalar = accountScalars.get(0);
        boolean opportunityOwner = Boolean.TRUE.equals(accountScalar[0]);
        long accountId = ((Number) accountScalar[1]).longValue();
        lockAccount(new AccountKey(opportunityOwner, accountId));

        // 受影响记录首次即加锁加载（含原预订）
        WeatherAffectedRecord affected = affectedRepository.findByIdForUpdate(affectedId)
                .orElseThrow(() -> new ResourceNotFoundException("天气受影响记录不存在: " + affectedId));

        if (affected.getStatus() != WeatherAffectedStatus.AFFECTED) {
            throw new RescheduleNotAllowedException(
                    "受影响记录当前状态不允许恢复排期: " + affected.getStatus());
        }

        Reservation original = affected.getOriginalReservation();
        long durationMinutes = Duration.between(startTime, endTime).toMinutes();

        if (durationMinutes > affected.getRecoverableMinutes()) {
            throw new BusinessRuleException("恢复时长超过剩余可恢复分钟数：需要 " + durationMinutes
                    + " 分钟，剩余可恢复 " + affected.getRecoverableMinutes() + " 分钟");
        }

        String effectiveInstrument = instrument != null ? instrument : affected.getInstrument();
        if (!telescope.supports(effectiveInstrument)) {
            throw new BusinessRuleException("望远镜 " + telescopeCode + " 不支持仪器 " + effectiveInstrument);
        }

        // 仪器允许、（机会）响应时限与配额沿用原账户规则
        if (affected.isOpportunityOwner()) {
            OpportunityProposal opportunity = original.getOpportunity();
            if (!opportunity.allows(effectiveInstrument)) {
                throw new BusinessRuleException("机会提案 " + opportunity.getCode()
                        + " 不允许使用仪器 " + effectiveInstrument);
            }
            if (endTime.isAfter(opportunity.getResponseDeadline())) {
                throw new RescheduleNotAllowedException(
                        "新时段结束时间超过机会提案的响应时限: " + opportunity.getResponseDeadline());
            }
            if (opportunity.getRemainingQuotaMinutes() < durationMinutes) {
                throw new BusinessRuleException("机会提案 " + opportunity.getCode() + " 剩余配额不足，需要 "
                        + durationMinutes + " 分钟，剩余 " + opportunity.getRemainingQuotaMinutes() + " 分钟");
            }
        } else {
            Proposal proposal = original.getProposal();
            if (!proposal.allows(effectiveInstrument)) {
                throw new BusinessRuleException("提案 " + proposal.getCode()
                        + " 不允许使用仪器 " + effectiveInstrument);
            }
            if (proposal.getRemainingQuotaMinutes() < durationMinutes) {
                throw new BusinessRuleException("提案 " + proposal.getCode() + " 剩余配额不足，需要 "
                        + durationMinutes + " 分钟，剩余 " + proposal.getRemainingQuotaMinutes() + " 分钟");
            }
        }

        // 日程校验（重叠/仪器切换）：通过后才做任何状态与配额变更
        List<Reservation> active = reservationRepository
                .findActiveByTelescopeForUpdate(telescope, ReservationStatus.ACTIVE);
        bookingService.validateAgainstSchedule(telescope, effectiveInstrument, startTime, endTime, active);

        // —— 全部校验通过，开始提交副作用（同一事务，任一异常整体回滚）——
        Instant now = Instant.now();
        Reservation rebooked;
        if (affected.isOpportunityOwner()) {
            OpportunityProposal opportunity = original.getOpportunity();
            opportunity.deduct(durationMinutes);
            rebooked = reservationRepository.save(new Reservation(
                    idempotencyKey, null, opportunity, affected.getPriority(), telescope,
                    effectiveInstrument, startTime, endTime, durationMinutes));
        } else {
            Proposal proposal = original.getProposal();
            proposal.deduct(durationMinutes);
            rebooked = reservationRepository.save(new Reservation(
                    idempotencyKey, proposal, telescope, effectiveInstrument,
                    startTime, endTime, durationMinutes));
        }

        affected.applyRecovery(rebooked.getId(), durationMinutes, now);
        if (affected.getStatus() == WeatherAffectedStatus.RECOVERED) {
            original.markWeatherRecovered(rebooked.getId(), now);
        }
        return new RecoveryOutcome(rebooked, true, affected);
    }

    /** 放弃待恢复任务：可恢复分钟数清零，不涉及配额（关闭时已释放）。 */
    @Transactional
    public AffectedView abandon(Long affectedId) {
        // 标量定位望远镜与事件（不把实体无锁加载进一级缓存），按规范 望远镜 → 事件 → 受影响记录 加锁
        List<Object[]> refs = affectedRepository.findLockRefScalarById(affectedId);
        if (refs.isEmpty()) {
            throw new ResourceNotFoundException("天气受影响记录不存在: " + affectedId);
        }
        Object[] ref = refs.get(0);
        String telescopeCode = (String) ref[0];
        long eventId = ((Number) ref[1]).longValue();
        telescopeRepository.findByCodeForUpdate(telescopeCode);
        weatherEventRepository.findByIdForUpdate(eventId);

        WeatherAffectedRecord affected = affectedRepository.findByIdForUpdate(affectedId)
                .orElseThrow(() -> new ResourceNotFoundException("天气受影响记录不存在: " + affectedId));
        if (affected.getStatus() == WeatherAffectedStatus.AFFECTED) {
            affected.abandon(Instant.now());
        }
        // 已放弃/已还原/已恢复：幂等返回，不重复处理
        affectedRepository.flush();
        WeatherAffectedRecord reloaded = affectedRepository.findDetailedById(affectedId).orElseThrow();
        return new AffectedView(reloaded, loadNewReservations(reloaded));
    }

    // ---------------------------------------------------------------------
    // 查询
    // ---------------------------------------------------------------------

    @Transactional(readOnly = true)
    public WeatherEvent findEvent(String businessKey) {
        return weatherEventRepository.findByBusinessKey(businessKey)
                .orElseThrow(() -> new ResourceNotFoundException("天气关闭事件不存在: " + businessKey));
    }

    @Transactional(readOnly = true)
    public List<WeatherAffectedRecord> affectedByEvent(String businessKey) {
        if (weatherEventRepository.findByBusinessKey(businessKey).isEmpty()) {
            throw new ResourceNotFoundException("天气关闭事件不存在: " + businessKey);
        }
        return affectedRepository.findDetailedByEventKey(businessKey);
    }

    @Transactional(readOnly = true)
    public WeatherAffectedRecord findAffected(Long affectedId) {
        return affectedRepository.findDetailedById(affectedId)
                .orElseThrow(() -> new ResourceNotFoundException("天气受影响记录不存在: " + affectedId));
    }

    /** 受影响记录查询视图：在事务内解析出关联的原预订、天气事件与历次恢复的新预订。 */
    public record AffectedView(WeatherAffectedRecord affected, List<Reservation> newReservations) {
    }

    @Transactional(readOnly = true)
    public AffectedView affectedView(Long affectedId) {
        WeatherAffectedRecord affected = findAffected(affectedId);
        return new AffectedView(affected, loadNewReservations(affected));
    }

    @Transactional(readOnly = true)
    public List<AffectedView> affectedViewsByEvent(String businessKey) {
        return affectedByEvent(businessKey).stream()
                .map(a -> new AffectedView(a, loadNewReservations(a)))
                .toList();
    }

    private List<Reservation> loadNewReservations(WeatherAffectedRecord affected) {
        List<Long> ids = affected.getRecoveryBookings().stream()
                .map(b -> b.getNewReservationId()).toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        return reservationRepository.findDetailedByIds(ids).stream()
                .sorted(Comparator.comparing(Reservation::getId))
                .toList();
    }

    // ---------------------------------------------------------------------
    // 内部
    // ---------------------------------------------------------------------

    private void lockAccount(AccountKey key) {
        if (key.opportunity()) {
            opportunityRepository.findByIdForUpdate(key.id())
                    .orElseThrow(() -> new ResourceNotFoundException("机会提案不存在: " + key.id()));
        } else {
            proposalRepository.findByIdForUpdate(key.id())
                    .orElseThrow(() -> new ResourceNotFoundException("提案不存在: " + key.id()));
        }
    }

    private List<WeatherAffectedRecord> replayOrConflict(WeatherEvent existing, String telescopeCode,
                                                        Instant startTime, Instant endTime) {
        if (!existing.getTelescope().getCode().equals(telescopeCode)) {
            throw new IdempotencyConflictException("天气关闭业务号已用于另一台望远镜: " + existing.getBusinessKey());
        }
        if (!existing.getStartTime().equals(startTime) || !existing.getEndTime().equals(endTime)) {
            throw new IdempotencyConflictException(
                    "天气关闭业务号已使用且窗口不同，请使用范围调整接口: " + existing.getBusinessKey());
        }
        return affectedRepository.findDetailedByEventKey(existing.getBusinessKey());
    }

    private boolean overlaps(Instant s, Instant e, Instant ws, Instant we) {
        return s.isBefore(we) && e.isAfter(ws);
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
