package com.chris64233.cc.telescope.service;

import com.chris64233.cc.telescope.domain.Reservation;
import com.chris64233.cc.telescope.domain.ReservationStatus;
import com.chris64233.cc.telescope.domain.Telescope;
import com.chris64233.cc.telescope.domain.WeatherAffectedSnapshot;
import com.chris64233.cc.telescope.domain.WeatherEvent;
import com.chris64233.cc.telescope.domain.WeatherRecovery;
import com.chris64233.cc.telescope.domain.WeatherRecoveryStatus;
import com.chris64233.cc.telescope.repository.OpportunityProposalRepository;
import com.chris64233.cc.telescope.repository.ProposalRepository;
import com.chris64233.cc.telescope.repository.ReservationRepository;
import com.chris64233.cc.telescope.repository.TelescopeRepository;
import com.chris64233.cc.telescope.repository.WeatherEventRepository;
import com.chris64233.cc.telescope.repository.WeatherRecoveryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 天气关闭与受影响观测的恢复排期。
 *
 * <p><b>资源模型</b>：关闭窗口只阻断<strong>尚未开始</strong>的有效（ACTIVE）预订——已完成/正在执行的
 * 观测保持不变。被阻断预订不退还分钟到提案的可消费配额，而是冻结为一条「可恢复分钟资格」
 * （{@link WeatherRecovery}），保留原归属与优先级；恢复排期只消耗资格分钟、不动账户配额，
 * 因此范围调整、取消与恢复排期并发时分钟数至多释放/消耗一次。
 *
 * <p><b>锁顺序（所有写事务统一，避免跨望远镜死锁）</b>：
 * 望远镜行 → 配额账户行（普通/机会，按 类型+ID 全局排序）→ 恢复资格行（按 ID 升序）→ 预订行（按 ID 升序）。
 * 关闭登记不触碰账户行；取消天气阻断预订与恢复排期走完整链路。
 */
@Service
public class WeatherService {

    private final TelescopeRepository telescopeRepository;
    private final WeatherEventRepository weatherEventRepository;
    private final WeatherRecoveryRepository weatherRecoveryRepository;
    private final ReservationRepository reservationRepository;
    private final ProposalRepository proposalRepository;
    private final OpportunityProposalRepository opportunityProposalRepository;
    private final BookingService bookingService;

    public WeatherService(TelescopeRepository telescopeRepository,
                          WeatherEventRepository weatherEventRepository,
                          WeatherRecoveryRepository weatherRecoveryRepository,
                          ReservationRepository reservationRepository,
                          ProposalRepository proposalRepository,
                          OpportunityProposalRepository opportunityProposalRepository,
                          BookingService bookingService) {
        this.telescopeRepository = telescopeRepository;
        this.weatherEventRepository = weatherEventRepository;
        this.weatherRecoveryRepository = weatherRecoveryRepository;
        this.reservationRepository = reservationRepository;
        this.proposalRepository = proposalRepository;
        this.opportunityProposalRepository = opportunityProposalRepository;
        this.bookingService = bookingService;
    }

    // ---------------------------------------------------------------------
    // 关闭事件登记
    // ---------------------------------------------------------------------

    /** 登记结果：{@code created=false} 为业务号重放。 */
    public record DeclareOutcome(WeatherEvent event, boolean created, int blockedCount) {
    }

    /**
     * 原子登记天气关闭事件：一次性标记窗口内全部尚未开始的有效预订、释放日程，
     * 并为每条预订建立可恢复分钟资格；已完成/正在执行的观测不变。
     */
    @Transactional
    public DeclareOutcome declare(String businessKey, String telescopeCode,
                                  Instant windowStart, Instant windowEnd) {
        validateWindow(windowStart, windowEnd);
        Instant now = Instant.now();

        // 规范加锁顺序：先望远镜行，同望远镜上的全部日程写事务在此串行
        Telescope telescope = telescopeRepository.findByCodeForUpdate(telescopeCode)
                .orElseThrow(() -> new ResourceNotFoundException("望远镜不存在: " + telescopeCode));

        var replay = weatherEventRepository.findByBusinessKey(businessKey);
        if (replay.isPresent()) {
            WeatherEvent existing = replay.get();
            if (sameWindow(existing, telescopeCode, windowStart, windowEnd)) {
                int blocked = (int) existing.getAffectedReservations().stream()
                        .filter(s -> "BLOCKED".equals(s.getAction())).count();
                return new DeclareOutcome(existing, false, blocked);
            }
            throw new IdempotencyConflictException("天气事件业务号已使用且内容不一致: " + businessKey);
        }

        // 首次即加锁加载全部有效预订行（在望远镜行锁之后）
        List<Reservation> active = reservationRepository
                .findActiveByTelescopeForUpdate(telescope, ReservationStatus.ACTIVE);

        List<Reservation> toBlock = active.stream()
                .filter(r -> overlaps(r, windowStart, windowEnd))
                .filter(r -> now.isBefore(r.getStartTime()))
                .sorted(Comparator.comparing(Reservation::getId))
                .toList();

        // 先持久化事件取得标识，再建立资格（资格持有事件外键）
        WeatherEvent event = weatherEventRepository.saveAndFlush(
                new WeatherEvent(businessKey, telescopeCode, windowStart, windowEnd, List.of(), now));
        List<WeatherAffectedSnapshot> snapshots = new ArrayList<>();
        for (Reservation r : toBlock) {
            r.markWeatherBlocked(event, now);
            weatherRecoveryRepository.save(new WeatherRecovery(event, r, now));
            snapshots.add(WeatherAffectedSnapshot.blocked(r, now));
        }
        event.getAffectedReservations().addAll(snapshots);

        return new DeclareOutcome(event, true, toBlock.size());
    }

    // ---------------------------------------------------------------------
    // 范围调整（扩大 / 缩小）
    // ---------------------------------------------------------------------

    /** 范围调整结果：新阻断数与恢复回日程数；{@code created=false} 为相同范围重放。 */
    public record AdjustOutcome(WeatherEvent event, boolean created, int blockedCount, int restoredCount) {
    }

    /**
     * 幂等调整关闭窗口范围。扩大时阻断新增重叠的、尚未开始的有效预订；
     * 缩小时把仍未消耗恢复资格（BLOCKED）且落出新窗口的预订恢复回日程——
     * 已部分/全部恢复、已放弃的预订不回退（其分钟已消耗或作废，避免重复释放）。
     * 恢复回日程时若原时段已被其他有效预订占用，则该预订保持阻断（可继续恢复排期到其他时段）。
     */
    @Transactional
    public AdjustOutcome adjust(String businessKey, Instant newStart, Instant newEnd) {
        validateWindow(newStart, newEnd);
        Instant now = Instant.now();

        // 望远镜行必须先于事件行锁定（统一锁序），用标量定位望远镜编号避免实体进一级缓存
        String telescopeCode = weatherEventRepository.findTelescopeCodeByBusinessKey(businessKey)
                .orElseThrow(() -> new ResourceNotFoundException("天气事件不存在: " + businessKey));
        Telescope telescope = telescopeRepository.findByCodeForUpdate(telescopeCode)
                .orElseThrow(() -> new ResourceNotFoundException("望远镜不存在: " + telescopeCode));

        WeatherEvent event = weatherEventRepository.findByBusinessKeyForUpdate(businessKey)
                .orElseThrow(() -> new ResourceNotFoundException("天气事件不存在: " + businessKey));
        if (event.getWindowStart().equals(newStart) && event.getWindowEnd().equals(newEnd)) {
            return new AdjustOutcome(event, false, 0, 0);
        }

        // 锁定本事件全部恢复资格代，筛出当前仍 BLOCKED 的一代（标量取其原预订 ID）
        List<WeatherRecovery> recoveries = weatherRecoveryRepository.findByEventIdForUpdate(event.getId());
        List<Long> blockedReservationIds = weatherRecoveryRepository
                .findOriginalReservationIds(event.getId(), WeatherRecoveryStatus.BLOCKED);

        // 统一按 ID 加锁相关预订行：全部有效预订 + 当前被阻断的原预订（首次加载即加锁）
        List<Reservation> active = reservationRepository
                .findActiveByTelescopeForUpdate(telescope, ReservationStatus.ACTIVE);
        Set<Long> ids = new LinkedHashSet<>(blockedReservationIds);
        active.forEach(r -> ids.add(r.getId()));
        Map<Long, Reservation> byId = new HashMap<>();
        reservationRepository.findByIdInForUpdate(ids).forEach(r -> byId.put(r.getId(), r));

        // 扩大：与新窗口重叠、与旧窗口不重叠、尚未开始的有效预订
        List<Reservation> newlyBlocked = active.stream()
                .filter(r -> overlaps(r, newStart, newEnd))
                .filter(r -> !overlaps(r, event.getWindowStart(), event.getWindowEnd()))
                .filter(r -> now.isBefore(r.getStartTime()))
                .sorted(Comparator.comparing(Reservation::getId))
                .toList();

        // 缩小：当前 BLOCKED 且落出新窗口的资格，候选恢复回日程（资格行已锁、预订行在 byId 中）
        Map<Long, WeatherRecovery> blockedRecoveryByReservation = new HashMap<>();
        for (WeatherRecovery w : recoveries) {
            if (w.getStatus() == WeatherRecoveryStatus.BLOCKED) {
                blockedRecoveryByReservation.put(w.getOriginalReservation().getId(), w);
            }
        }
        List<WeatherRecovery> restoreCandidates = blockedReservationIds.stream()
                .map(blockedRecoveryByReservation::get)
                .filter(w -> !overlaps(w.getOriginalReservation(), newStart, newEnd))
                // 原时段已经开始/结束的预订无法再观测，不恢复回日程（仍可恢复排期到新时段）
                .filter(w -> now.isBefore(w.getOriginalReservation().getStartTime()))
                .sorted(Comparator.comparing(WeatherRecovery::getId))
                .toList();

        // 恢复后的有效日程 = 现有有效预订（剔除新阻断）+ 逐个通过校验的恢复候选
        List<Reservation> working = new ArrayList<>(active.stream()
                .filter(r -> newlyBlocked.stream().noneMatch(b -> b.getId().equals(r.getId())))
                .toList());
        List<WeatherRecovery> restored = new ArrayList<>();
        for (WeatherRecovery w : restoreCandidates) {
            Reservation r = byId.get(w.getOriginalReservation().getId());
            try {
                bookingService.validateAgainstSchedule(
                        telescope, r.getInstrument(), r.getStartTime(), r.getEndTime(),
                        working.stream().filter(o -> !o.getId().equals(r.getId())).toList());
            } catch (ScheduleConflictException conflict) {
                // 原时段已被占用/不满足切换：保持阻断，资格不回退（仍可恢复排期到其他时段）
                continue;
            }
            w.restore(now);
            r.unmarkWeatherBlocked(now);
            restored.add(w);
            working.add(r);
        }

        List<WeatherAffectedSnapshot> blockedSnapshots = new ArrayList<>();
        for (Reservation r : newlyBlocked) {
            r.markWeatherBlocked(event, now);
            weatherRecoveryRepository.save(new WeatherRecovery(event, r, now));
            blockedSnapshots.add(WeatherAffectedSnapshot.blocked(r, now));
        }
        List<WeatherAffectedSnapshot> restoredSnapshots = restored.stream()
                .map(w -> WeatherAffectedSnapshot.restored(byId.get(w.getOriginalReservation().getId()), now))
                .toList();

        event.applyAdjustment(newStart, newEnd, blockedSnapshots, restoredSnapshots, now);

        return new AdjustOutcome(event, true, newlyBlocked.size(), restored.size());
    }

    // ---------------------------------------------------------------------
    // 恢复排期
    // ---------------------------------------------------------------------

    /** 恢复排期结果：{@code created=false} 为幂等键重放。 */
    public record RecoveryOutcome(Reservation newReservation, boolean created, WeatherRecovery recovery) {
    }

    /**
     * 为天气阻断的原预订申请新时段。新预订继承原归属与优先级，<strong>只消耗可恢复资格分钟，
     * 不扣减提案配额</strong>；新时长不得超过剩余可恢复分钟。任何校验失败整体回滚，不留部分占用。
     * 可多次部分恢复；最后一次耗尽资格时原预订进入 RECOVERED 终态。
     */
    @Transactional
    public RecoveryOutcome recover(Long reservationId, String idempotencyKey,
                                   String telescopeCode, String instrument,
                                   Instant startTime, Instant endTime) {
        validateWindow(startTime, endTime);

        var replay = reservationRepository.findByIdempotencyKey(idempotencyKey);
        if (replay.isPresent()) {
            Reservation existing = replay.get();
            if (existing.matches(existing.getOwnerCode(), telescopeCode, instrument, startTime, endTime)) {
                // 重放为只读路径：用非加锁详情查询取最新一代资格（不改变任何状态）
                List<WeatherRecovery> generations =
                        weatherRecoveryRepository.findDetailedByReservationId(reservationId);
                WeatherRecovery latest = generations.isEmpty() ? null : generations.get(generations.size() - 1);
                return new RecoveryOutcome(existing, false, latest);
            }
            throw new IdempotencyConflictException("幂等键已使用且内容不一致: " + idempotencyKey);
        }

        // 锁序：目标望远镜行 → 配额账户行（恢复虽不扣配额，仍按统一顺序加锁，与取消/抢占一致）
        Telescope telescope = telescopeRepository.findByCodeForUpdate(telescopeCode)
                .orElseThrow(() -> new ResourceNotFoundException("望远镜不存在: " + telescopeCode));

        // 已持望远镜行锁，范围调整无法并发移动窗口；新时段不得落入任何已登记的关闭窗口
        List<WeatherEvent> eventsOnTelescope =
                weatherEventRepository.findByTelescopeCodeOrderByIdAsc(telescopeCode);
        for (WeatherEvent e : eventsOnTelescope) {
            if (startTime.isBefore(e.getWindowEnd()) && endTime.isAfter(e.getWindowStart())) {
                throw new ScheduleConflictException("新时段与天气关闭窗口 [" + e.getWindowStart()
                        + ", " + e.getWindowEnd() + ")（事件 " + e.getBusinessKey() + "）重叠");
            }
        }

        Long normalAccountId = reservationRepository.findProposalIdById(reservationId);
        Long opportunityAccountId = reservationRepository.findOpportunityIdById(reservationId);
        if (normalAccountId == null && opportunityAccountId == null) {
            throw new ResourceNotFoundException("预订不存在: " + reservationId);
        }
        boolean opportunityAccount = opportunityAccountId != null;
        if (opportunityAccount) {
            opportunityProposalRepository.findByIdForUpdate(opportunityAccountId);
        } else {
            proposalRepository.findByIdForUpdate(normalAccountId);
        }

        // 恢复资格行先于预订行锁定；同一预订可能有多代资格，最新一代（ID 最大）才是当前有效的
        List<WeatherRecovery> generations =
                weatherRecoveryRepository.findAllByReservationIdForUpdate(reservationId);
        if (generations.isEmpty()) {
            throw new WeatherRecoveryNotAllowedException(
                    "预订没有天气恢复记录，不能恢复排期: " + reservationId);
        }
        WeatherRecovery recovery = generations.get(generations.size() - 1);

        Reservation original = reservationRepository.findByIdForUpdate(reservationId)
                .orElseThrow(() -> new ResourceNotFoundException("预订不存在: " + reservationId));
        if (original.getStatus() == ReservationStatus.RECOVERED) {
            // 并发落败：恢复已由同/他请求完成，幂等返回收尾的新预订（不重复消耗分钟）
            Reservation already = reservationRepository.findById(original.getRecoveredToId())
                    .orElseThrow(() -> new ResourceNotFoundException("恢复后的预订不存在"));
            return new RecoveryOutcome(already, false, recovery);
        }
        if (original.getStatus() != ReservationStatus.WEATHER_BLOCKED) {
            throw new WeatherRecoveryNotAllowedException(
                    "预订不是天气阻断状态，不能恢复排期，当前状态: " + original.getStatus());
        }
        if (recovery.getStatus() != WeatherRecoveryStatus.BLOCKED
                && recovery.getStatus() != WeatherRecoveryStatus.PARTIALLY_RECOVERED) {
            throw new WeatherRecoveryNotAllowedException(
                    "恢复资格当前状态不允许恢复排期: " + recovery.getStatus());
        }
        // 恢复必须回到关闭事件所在（即原预订所在）望远镜：保证统一锁序，也符合天气恢复语义
        String originalTelescopeCode = original.getTelescope().getCode();
        if (!originalTelescopeCode.equals(telescopeCode)) {
            throw new BusinessRuleException("恢复排期必须在原望远镜 " + originalTelescopeCode
                    + " 上进行，收到: " + telescopeCode);
        }

        String effectiveInstrument = instrument != null ? instrument : original.getInstrument();
        if (!telescope.supports(effectiveInstrument)) {
            throw new BusinessRuleException("望远镜 " + telescopeCode + " 不支持仪器 " + effectiveInstrument);
        }
        if (opportunityAccount) {
            if (!original.getOpportunity().allows(effectiveInstrument)) {
                throw new BusinessRuleException("机会提案 " + original.getOwnerCode()
                        + " 不允许使用仪器 " + effectiveInstrument);
            }
        } else if (!original.getProposal().allows(effectiveInstrument)) {
            throw new BusinessRuleException("提案 " + original.getOwnerCode()
                    + " 不允许使用仪器 " + effectiveInstrument);
        }

        long durationMinutes = Duration.between(startTime, endTime).toMinutes();
        if (durationMinutes > recovery.getRemainingRecoverableMinutes()) {
            throw new BusinessRuleException("剩余可恢复分钟不足：需要 " + durationMinutes + " 分钟，剩余 "
                    + recovery.getRemainingRecoverableMinutes() + " 分钟");
        }

        // 新时段全部校验通过前不写入任何数据：重叠/切换失败整体回滚，无部分占用
        List<Reservation> active = reservationRepository
                .findActiveByTelescopeForUpdate(telescope, ReservationStatus.ACTIVE);
        bookingService.validateAgainstSchedule(
                telescope, effectiveInstrument, startTime, endTime, active);

        Reservation rebooked;
        Instant now = Instant.now();
        if (opportunityAccount) {
            // 继承原机会预订的归属与优先级；资格分钟已在阻断时冻结，这里不再扣机会配额
            rebooked = new Reservation(
                    idempotencyKey, null, original.getOpportunity(), original.getPriority(),
                    telescope, effectiveInstrument, startTime, endTime, durationMinutes);
        } else {
            rebooked = new Reservation(
                    idempotencyKey, original.getProposal(), telescope, effectiveInstrument,
                    startTime, endTime, durationMinutes);
        }
        // 标记本预订由恢复资格出资：日后取消/抢占时分钟退回资格而非重复退还配额
        rebooked.markFundedByWeatherRecovery(recovery);
        rebooked = reservationRepository.save(rebooked);

        recovery.consume(durationMinutes, rebooked.getId(), now);
        if (recovery.getStatus() == WeatherRecoveryStatus.RECOVERED) {
            original.markRecovered(rebooked.getId(), now);
        }
        return new RecoveryOutcome(rebooked, true, recovery);
    }

    // ---------------------------------------------------------------------
    // 查询
    // ---------------------------------------------------------------------

    /** 恢复资格及其历次恢复建立的新预订（已立即加载，供事务外序列化）。 */
    public record RecoveryView(WeatherRecovery recovery, List<Reservation> recoveredReservations) {
    }

    @Transactional(readOnly = true)
    public WeatherEvent findEvent(String businessKey) {
        return weatherEventRepository.findByBusinessKey(businessKey)
                .orElseThrow(() -> new ResourceNotFoundException("天气事件不存在: " + businessKey));
    }

    @Transactional(readOnly = true)
    public List<WeatherEvent> eventsByTelescope(String telescopeCode) {
        if (telescopeRepository.findByCode(telescopeCode).isEmpty()) {
            throw new ResourceNotFoundException("望远镜不存在: " + telescopeCode);
        }
        return weatherEventRepository.findByTelescopeCodeOrderByIdAsc(telescopeCode);
    }

    /** 某关闭事件下全部恢复资格（含已恢复/作废/撤销），关联原预订与历次新预订。 */
    @Transactional(readOnly = true)
    public List<RecoveryView> recoveriesByEvent(String businessKey) {
        WeatherEvent event = findEvent(businessKey);
        return assembleViews(weatherRecoveryRepository.findDetailedByEventId(event.getId()));
    }

    @Transactional(readOnly = true)
    public RecoveryView findRecovery(Long reservationId) {
        List<WeatherRecovery> generations =
                weatherRecoveryRepository.findDetailedByReservationId(reservationId);
        if (generations.isEmpty()) {
            throw new ResourceNotFoundException("预订不存在天气恢复记录: " + reservationId);
        }
        return assembleViews(List.of(generations.get(generations.size() - 1))).get(0);
    }

    /** 当前仍可恢复排期（BLOCKED / PARTIALLY_RECOVERED）的全部资格，关联历次新预订。 */
    @Transactional(readOnly = true)
    public List<RecoveryView> recoverableReservations() {
        List<WeatherRecovery> all = new ArrayList<>();
        all.addAll(weatherRecoveryRepository.findDetailedByStatus(WeatherRecoveryStatus.BLOCKED));
        all.addAll(weatherRecoveryRepository.findDetailedByStatus(WeatherRecoveryStatus.PARTIALLY_RECOVERED));
        return assembleViews(all);
    }

    private List<RecoveryView> assembleViews(List<WeatherRecovery> recoveries) {
        Set<Long> newIds = recoveries.stream()
                .flatMap(w -> w.getRecoveredReservationIds().stream())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Map<Long, Reservation> newById = newIds.isEmpty()
                ? Map.of()
                : reservationRepository.findDetailedByIdIn(newIds).stream()
                        .collect(java.util.stream.Collectors.toMap(Reservation::getId, r -> r));
        List<RecoveryView> views = new ArrayList<>();
        for (WeatherRecovery w : recoveries) {
            List<Reservation> news = w.getRecoveredReservationIds().stream()
                    .map(newById::get)
                    .toList();
            views.add(new RecoveryView(w, news));
        }
        return views;
    }

    // ---------------------------------------------------------------------

    private static boolean overlaps(Reservation r, Instant start, Instant end) {
        return r.getStartTime().isBefore(end) && r.getEndTime().isAfter(start);
    }

    private static boolean sameWindow(WeatherEvent event, String telescopeCode,
                                      Instant start, Instant end) {
        return event.getTelescopeCode().equals(telescopeCode)
                && event.getWindowStart().equals(start)
                && event.getWindowEnd().equals(end);
    }

    private void validateWindow(Instant start, Instant end) {
        if (start == null || end == null || !start.isBefore(end)) {
            throw new BusinessRuleException("关闭窗口无效：开始时间必须早于结束时间");
        }
        Duration duration = Duration.between(start, end);
        if (!Duration.ofMinutes(duration.toMinutes()).equals(duration)) {
            throw new BusinessRuleException("关闭窗口需对齐到整分钟");
        }
    }
}
