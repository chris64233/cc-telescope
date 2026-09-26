package com.chris64233.cc.telescope;

import com.chris64233.cc.telescope.domain.PreemptionStatus;
import com.chris64233.cc.telescope.domain.Reservation;
import com.chris64233.cc.telescope.domain.ReservationStatus;
import com.chris64233.cc.telescope.repository.PreemptionRepository;
import com.chris64233.cc.telescope.repository.ProposalRepository;
import com.chris64233.cc.telescope.repository.ReservationRepository;
import com.chris64233.cc.telescope.repository.TelescopeRepository;
import com.chris64233.cc.telescope.service.BookingService;
import com.chris64233.cc.telescope.service.BusinessRuleException;
import com.chris64233.cc.telescope.service.IdempotencyConflictException;
import com.chris64233.cc.telescope.service.PreemptionService;
import com.chris64233.cc.telescope.service.ScheduleConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class PreemptionServiceTest {

    private static final Instant BASE = Instant.parse("2030-01-01T00:00:00Z");
    private static final Instant VALID_UNTIL = Instant.parse("2031-01-01T00:00:00Z");

    @Autowired
    private PreemptionService preemptionService;
    @Autowired
    private BookingService bookingService;
    @Autowired
    private ReservationRepository reservationRepository;
    @Autowired
    private ProposalRepository proposalRepository;
    @Autowired
    private TelescopeRepository telescopeRepository;
    @Autowired
    private PreemptionRepository preemptionRepository;
    @Autowired
    private TestDatabaseCleaner cleaner;

    @BeforeEach
    void setUp() {
        cleaner.clean();
        bookingService.registerTelescope("T1", "山顶望远镜", 30, Set.of("CAM", "SPEC"));
        bookingService.registerProposal("P1", Set.of("CAM", "SPEC"), 600);
        bookingService.registerProposal("TOO", Set.of("CAM"), 120, true, 10, VALID_UNTIL);
    }

    private static Instant at(int hour, int minute) {
        return BASE.plusSeconds((hour * 60L + minute) * 60L);
    }

    private static Instant atDay2(int hour, int minute) {
        return BASE.plusSeconds(24 * 3600L + (hour * 60L + minute) * 60L);
    }

    /** 日程 JSON 快照中的预订标识键，避免与时长等数字字段误匹配。 */
    private static String scheduleEntryId(long reservationId) {
        return "\"reservationId\":" + reservationId;
    }

    @Test
    void previewListsAffectedReservationsAndNeighborSwitchGaps() {
        // 合法日程：SPEC(9:00-10:00) → CAM(10:30-11:00) → SPEC(11:30-12:00)，切换间隔均满足
        bookingService.book("a", "P1", "T1", "SPEC", at(9, 0), at(10, 0));
        bookingService.book("b", "P1", "T1", "CAM", at(10, 30), at(11, 0));
        bookingService.book("c", "P1", "T1", "SPEC", at(11, 30), at(12, 0));

        // 抢占窗口 10:15-11:15 覆盖 CAM 预订，但与前后 SPEC 之间各只剩 15 分钟切换时间
        var preview = preemptionService.preview("TOO", "T1", "CAM", at(10, 15), at(11, 15));

        assertThat(preview.feasible()).isFalse();
        assertThat(preview.affectedReservations()).hasSize(1);
        assertThat(preview.affectedReservations().get(0).proposalCode()).isEqualTo("P1");
        // 前序 SPEC 距窗口开始 15 分钟，需 30 分钟
        assertThat(preview.switchGapBefore().neighborInstrument()).isEqualTo("SPEC");
        assertThat(preview.switchGapBefore().requiredMinutes()).isEqualTo(30);
        assertThat(preview.switchGapBefore().availableMinutes()).isEqualTo(15);
        assertThat(preview.switchGapBefore().ok()).isFalse();
        // 后序 SPEC 距窗口结束 15 分钟
        assertThat(preview.switchGapAfter().neighborInstrument()).isEqualTo("SPEC");
        assertThat(preview.switchGapAfter().availableMinutes()).isEqualTo(15);
        assertThat(preview.reasons()).anyMatch(r -> r.startsWith("SWITCH_GAP_INSUFFICIENT"));
    }

    @Test
    void previewSameInstrumentNeighborIsFeasible() {
        bookingService.book("a", "P1", "T1", "CAM", at(9, 0), at(10, 0));
        bookingService.book("b", "P1", "T1", "CAM", at(10, 0), at(11, 0));

        var preview = preemptionService.preview("TOO", "T1", "CAM", at(10, 0), at(10, 30));

        assertThat(preview.feasible()).isTrue();
        assertThat(preview.reasons()).isEmpty();
        // 被覆盖预订不算相邻；窗口外前序同仪器无需切换时间
        assertThat(preview.switchGapBefore().neighborReservationId()).isNotNull();
        assertThat(preview.switchGapBefore().requiredMinutes()).isZero();
        assertThat(preview.switchGapBefore().ok()).isTrue();
        assertThat(preview.switchGapAfter()).isNull();
    }

    @Test
    void nonTargetOpportunityProposalCannotPreempt() {
        bookingService.book("a", "P1", "T1", "CAM", at(10, 0), at(11, 0));

        assertThatThrownBy(() -> preemptionService.preview("P1", "T1", "CAM", at(10, 0), at(10, 30)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("不是目标机会提案");
    }

    @Test
    void confirmPreemptsRefundsQuotaCreatesBookingAndSnapshots() {
        var victim1 = bookingService.book("a", "P1", "T1", "CAM", at(10, 0), at(10, 30)).reservation();
        var victim2 = bookingService.book("b", "P1", "T1", "CAM", at(10, 30), at(11, 0)).reservation();
        assertThat(bookingService.quota("P1").getRemainingQuotaMinutes()).isEqualTo(540);
        assertThat(bookingService.quota("TOO").getRemainingQuotaMinutes()).isEqualTo(120);

        var preemption = preemptionService.confirm("BK-1", "TOO", "T1", "CAM",
                at(10, 0), at(11, 0));

        assertThat(preemption.getStatus()).isEqualTo(PreemptionStatus.CONFIRMED);
        // 两笔普通预订建立后版本为 2，抢占成功推进到 3
        assertThat(preemption.getScheduleVersionBefore()).isEqualTo(2);
        assertThat(preemption.getScheduleVersionAfter()).isEqualTo(3);
        assertThat(preemption.getScheduleBeforeJson())
                .contains(scheduleEntryId(victim1.getId()))
                .contains(scheduleEntryId(victim2.getId()));
        assertThat(preemption.getScheduleAfterJson())
                .doesNotContain(scheduleEntryId(victim1.getId()))
                .doesNotContain(scheduleEntryId(victim2.getId()))
                .contains(scheduleEntryId(preemption.getNewReservation().getId()));
        assertThat(preemption.getItems()).hasSize(2);
        assertThat(preemption.getItems()).extracting(i -> i.getReservationId())
                .containsExactly(victim1.getId(), victim2.getId());
        // 被抢占预订进入待重排并归还各自配额
        assertThat(reservationRepository.findById(victim1.getId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.PENDING_REARRANGE);
        assertThat(bookingService.quota("P1").getRemainingQuotaMinutes()).isEqualTo(600);
        // ToO 配额按新预订时长扣减
        assertThat(bookingService.quota("TOO").getRemainingQuotaMinutes()).isEqualTo(60);
        // 日程只剩新预订
        assertThat(bookingService.schedule("T1")).hasSize(1);
        assertThat(bookingService.schedule("T1").get(0).getId())
                .isEqualTo(preemption.getNewReservation().getId());
        assertThat(preemptionService.pendingRearrangements("P1")).hasSize(2);
    }

    @Test
    void confirmIsIdempotentByBusinessKey() {
        bookingService.book("a", "P1", "T1", "CAM", at(10, 0), at(11, 0));

        var first = preemptionService.confirm("BK-IDEM", "TOO", "T1", "CAM",
                at(10, 0), at(10, 30));
        var replay = preemptionService.confirm("BK-IDEM", "TOO", "T1", "CAM",
                at(10, 0), at(10, 30));

        assertThat(replay.getId()).isEqualTo(first.getId());
        assertThat(preemptionRepository.count()).isEqualTo(1);
        // 配额只扣一次、只归还一次
        assertThat(bookingService.quota("TOO").getRemainingQuotaMinutes()).isEqualTo(90);
        assertThat(bookingService.quota("P1").getRemainingQuotaMinutes()).isEqualTo(600);

        assertThatThrownBy(() -> preemptionService.confirm("BK-IDEM", "TOO", "T1", "CAM",
                at(10, 0), at(10, 45)))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void higherPriorityTooReservationIsNotPreemptableAndScheduleStaysUnchanged() {
        bookingService.registerProposal("TOO-HI", Set.of("CAM"), 300, true, 20, VALID_UNTIL);
        bookingService.book("hi", "TOO-HI", "T1", "CAM", at(10, 0), at(11, 0));
        bookingService.book("lo", "P1", "T1", "CAM", at(11, 0), at(12, 0));
        long quotaHiBefore = bookingService.quota("TOO-HI").getRemainingQuotaMinutes();

        var preemption = preemptionService.confirm("BK-NP", "TOO", "T1", "CAM",
                at(10, 30), at(11, 30));

        assertThat(preemption.getStatus()).isEqualTo(PreemptionStatus.REJECTED);
        assertThat(preemption.getReasons()).extracting(r -> r.getReason())
                .anyMatch(r -> r.startsWith("NON_PREEMPTABLE_RESERVATION"));
        // 原日程、版本与所有配额完全不变（两笔预订建立后版本为 2，拒绝时不推进）
        assertThat(bookingService.schedule("T1")).hasSize(2);
        assertThat(preemption.getScheduleVersionBefore()).isEqualTo(2);
        assertThat(preemption.getScheduleVersionAfter()).isEqualTo(2);
        assertThat(preemption.getScheduleAfterJson()).isEqualTo(preemption.getScheduleBeforeJson());
        assertThat(bookingService.quota("TOO").getRemainingQuotaMinutes()).isEqualTo(120);
        assertThat(bookingService.quota("TOO-HI").getRemainingQuotaMinutes()).isEqualTo(quotaHiBefore);
        assertThat(preemption.getNewReservation()).isNull();
        assertThat(preemptionService.findPreemption("BK-NP").getStatus())
                .isEqualTo(PreemptionStatus.REJECTED);
    }

    @Test
    void lowerPriorityTooReservationCanBePreempted() {
        bookingService.registerProposal("TOO-LO", Set.of("CAM"), 300, true, 5, VALID_UNTIL);
        bookingService.book("lo", "TOO-LO", "T1", "CAM", at(10, 0), at(11, 0));

        var preemption = preemptionService.confirm("BK-LO", "TOO", "T1", "CAM",
                at(10, 0), at(11, 0));

        assertThat(preemption.getStatus()).isEqualTo(PreemptionStatus.CONFIRMED);
        // 被抢占的低优先级 ToO 同样归还分钟数
        assertThat(bookingService.quota("TOO-LO").getRemainingQuotaMinutes()).isEqualTo(300);
        assertThat(preemptionService.pendingRearrangements("TOO-LO")).hasSize(1);
    }

    @Test
    void incompatibleInstrumentRejectsWithoutChanges() {
        bookingService.registerProposal("TOO-RADIO", Set.of("RADIO", "CAM"), 120, true, 10, VALID_UNTIL);
        bookingService.book("a", "P1", "T1", "CAM", at(10, 0), at(11, 0));

        // 望远镜 T1 不支持 RADIO
        var byTelescope = preemptionService.confirm("BK-INST-1", "TOO-RADIO", "T1", "RADIO",
                at(10, 0), at(10, 30));
        assertThat(byTelescope.getStatus()).isEqualTo(PreemptionStatus.REJECTED);
        assertThat(byTelescope.getReasons()).extracting(r -> r.getReason())
                .anyMatch(r -> r.startsWith("INSTRUMENT_NOT_SUPPORTED"));

        // ToO 提案只允许 CAM，不允许 SPEC（T1 支持 SPEC）
        var byProposal = preemptionService.confirm("BK-INST-2", "TOO", "T1", "SPEC",
                at(10, 0), at(10, 30));
        assertThat(byProposal.getStatus()).isEqualTo(PreemptionStatus.REJECTED);
        assertThat(byProposal.getReasons()).extracting(r -> r.getReason())
                .anyMatch(r -> r.startsWith("INSTRUMENT_NOT_ALLOWED"));

        assertThat(bookingService.schedule("T1")).hasSize(1);
        assertThat(bookingService.quota("TOO").getRemainingQuotaMinutes()).isEqualTo(120);
        assertThat(bookingService.quota("P1").getRemainingQuotaMinutes()).isEqualTo(540);
    }

    @Test
    void insufficientTooQuotaRejectsWithoutChanges() {
        bookingService.book("a", "P1", "T1", "CAM", at(10, 0), at(11, 0));

        var preemption = preemptionService.confirm("BK-Q", "TOO", "T1", "CAM",
                at(10, 0), at(12, 30));

        assertThat(preemption.getStatus()).isEqualTo(PreemptionStatus.REJECTED);
        assertThat(preemption.getReasons()).extracting(r -> r.getReason())
                .anyMatch(r -> r.startsWith("QUOTA_INSUFFICIENT"));
        assertThat(bookingService.schedule("T1")).hasSize(1);
        assertThat(bookingService.quota("TOO").getRemainingQuotaMinutes()).isEqualTo(120);
        assertThat(bookingService.quota("P1").getRemainingQuotaMinutes()).isEqualTo(540);
    }

    @Test
    void responseDeadlinePassedRejectsWithoutChanges() {
        bookingService.registerProposal("TOO-LATE", Set.of("CAM"), 300, true, 10,
                Instant.parse("2030-01-01T09:30:00Z"));
        bookingService.book("a", "P1", "T1", "CAM", at(10, 0), at(11, 0));

        var preemption = preemptionService.confirm("BK-DL", "TOO-LATE", "T1", "CAM",
                at(10, 0), at(11, 0));

        assertThat(preemption.getStatus()).isEqualTo(PreemptionStatus.REJECTED);
        assertThat(preemption.getReasons()).extracting(r -> r.getReason())
                .anyMatch(r -> r.startsWith("RESPONSE_DEADLINE_PASSED"));
        assertThat(bookingService.schedule("T1")).hasSize(1);
        assertThat(bookingService.quota("TOO-LATE").getRemainingQuotaMinutes()).isEqualTo(300);
        assertThat(bookingService.quota("P1").getRemainingQuotaMinutes()).isEqualTo(540);
    }

    @Test
    void rearrangeConsumesQuotaAgainWithinValidityWindow() {
        var victim = bookingService.book("a", "P1", "T1", "CAM", at(10, 0), at(11, 0)).reservation();
        preemptionService.confirm("BK-R", "TOO", "T1", "CAM", at(10, 0), at(11, 0));
        assertThat(bookingService.quota("P1").getRemainingQuotaMinutes()).isEqualTo(600);

        var outcome = preemptionService.rearrange(victim.getId(), "rr-1", at(14, 0), at(15, 0));

        assertThat(outcome.created()).isTrue();
        assertThat(outcome.reservation().getRearrangedFrom().getId()).isEqualTo(victim.getId());
        // 重排成功才再次扣减
        assertThat(bookingService.quota("P1").getRemainingQuotaMinutes()).isEqualTo(540);
        var original = reservationRepository.findById(victim.getId()).orElseThrow();
        assertThat(original.getStatus()).isEqualTo(ReservationStatus.PREEMPTED);
        assertThat(original.getRearrangedTo().getId()).isEqualTo(outcome.reservation().getId());
        assertThat(preemptionService.pendingRearrangements("P1")).isEmpty();

        var replay = preemptionService.rearrange(victim.getId(), "rr-1", at(14, 0), at(15, 0));
        assertThat(replay.created()).isFalse();
        assertThat(replay.reservation().getId()).isEqualTo(outcome.reservation().getId());
        assertThat(bookingService.quota("P1").getRemainingQuotaMinutes()).isEqualTo(540);
    }

    @Test
    void rearrangeRejectsWrongDurationExpiredWindowAndWrongState() {
        var victim = bookingService.book("a", "P1", "T1", "CAM", at(10, 0), at(11, 0)).reservation();
        preemptionService.confirm("BK-R2", "TOO", "T1", "CAM", at(10, 0), at(11, 0));

        // 时长不一致
        assertThatThrownBy(() -> preemptionService.rearrange(victim.getId(), "rr-x",
                at(14, 0), at(14, 30)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("时长必须与原预订一致");
        // 超出提案剩余有效期（P1 为普通提案无窗口；改用 ToO 自己被抢占的场景）
        bookingService.registerProposal("TOO-LO2", Set.of("CAM"), 300, true, 5,
                Instant.parse("2030-01-01T16:00:00Z"));
        var tooVictim = bookingService.book("b", "TOO-LO2", "T1", "CAM", at(12, 0), at(13, 0)).reservation();
        preemptionService.confirm("BK-R3", "TOO", "T1", "CAM", at(12, 0), at(13, 0));
        assertThatThrownBy(() -> preemptionService.rearrange(tooVictim.getId(), "rr-y",
                at(15, 30), at(16, 30)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("剩余有效期");

        // 非待重排状态不能重排
        var active = bookingService.book("c", "P1", "T1", "CAM", at(20, 0), at(21, 0)).reservation();
        assertThatThrownBy(() -> preemptionService.rearrange(active.getId(), "rr-z",
                at(22, 0), at(23, 0)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("不在待重排状态");

        // 被拒绝的尝试都不扣减；P1 仅剩活跃预订 c 占用的 60 分钟
        assertThat(bookingService.quota("P1").getRemainingQuotaMinutes()).isEqualTo(540);
    }

    @Test
    void cancelPendingReservationDoesNotRefundTwice() {
        var victim = bookingService.book("a", "P1", "T1", "CAM", at(10, 0), at(11, 0)).reservation();
        preemptionService.confirm("BK-C", "TOO", "T1", "CAM", at(10, 0), at(11, 0));
        assertThat(bookingService.quota("P1").getRemainingQuotaMinutes()).isEqualTo(600);

        var cancelled = bookingService.cancel(victim.getId());
        assertThat(cancelled.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(bookingService.quota("P1").getRemainingQuotaMinutes()).isEqualTo(600);
        assertThat(preemptionService.pendingRearrangements("P1")).isEmpty();

        // 重复取消仍然幂等
        bookingService.cancel(victim.getId());
        assertThat(bookingService.quota("P1").getRemainingQuotaMinutes()).isEqualTo(600);
    }

    @Test
    void concurrentCancelAndPreemptionNeverDoubleRefund() throws Exception {
        bookingService.registerProposal("TOO-BIG", Set.of("CAM"), 100_000, true, 10, VALID_UNTIL);
        int iterations = 12;
        var executor = Executors.newFixedThreadPool(8);
        try {
            for (int i = 0; i < iterations; i++) {
                final int index = i;
                final String key = "cp-" + i;
                Reservation victim = bookingService.book(key + "-b", "P1", "T1", "CAM",
                        at(index, 0), at(index, 30)).reservation();
                long victimId = victim.getId();
                CountDownLatch start = new CountDownLatch(1);

                Future<?> cancelFuture = executor.submit(() -> {
                    start.await();
                    try {
                        bookingService.cancel(victimId);
                    } catch (RuntimeException ignored) {
                        // 与抢占串行后可能因状态变化失败，忽略
                    }
                    return null;
                });
                Future<?> preemptFuture = executor.submit(() -> {
                    start.await();
                    try {
                        preemptionService.confirm(key + "-k", "TOO-BIG", "T1", "CAM",
                                at(index, 0), at(index, 30));
                    } catch (DataIntegrityViolationException ignored) {
                        // 业务号唯一约束竞争
                    }
                    return null;
                });
                start.countDown();
                cancelFuture.get();
                preemptFuture.get();
            }
        } finally {
            executor.shutdown();
        }

        // 每个被抢占/取消的预订都只归还一次 30 分钟：P1 初始 600，无任何仍占用的普通预订
        assertThat(bookingService.quota("P1").getRemainingQuotaMinutes()).isEqualTo(600);
        // 12 轮都落了一笔 ToO 预订（取消先到时，抢占在空出的时段成交），共 360 分钟、无重复消费
        assertThat(preemptionRepository.count()).isEqualTo(iterations);
        assertThat(100_000 - bookingService.quota("TOO-BIG").getRemainingQuotaMinutes())
                .isEqualTo(iterations * 30L);
    }

    @Test
    void concurrentConfirmWithSameBusinessKeyAppliesExactlyOnce() throws Exception {
        var victim = bookingService.book("a", "P1", "T1", "CAM", at(10, 0), at(11, 0)).reservation();
        int threads = 8;
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                try {
                    var p = preemptionService.confirm("BK-RACE", "TOO", "T1", "CAM",
                            at(10, 0), at(11, 0));
                    if (p.getStatus() == PreemptionStatus.CONFIRMED) {
                        successes.incrementAndGet();
                    }
                } catch (DataIntegrityViolationException | IdempotencyConflictException e) {
                    conflicts.incrementAndGet();
                }
                return null;
            });
        }
        runConcurrently(tasks);

        assertThat(successes.get() + conflicts.get()).isEqualTo(threads);
        // 重放返回同一 CONFIRMED 记录也算成功；关键是只落库一次、配额只扣一次
        assertThat(successes.get()).isGreaterThanOrEqualTo(1);
        assertThat(preemptionRepository.count()).isEqualTo(1);
        assertThat(bookingService.quota("P1").getRemainingQuotaMinutes()).isEqualTo(600);
        assertThat(bookingService.quota("TOO").getRemainingQuotaMinutes()).isEqualTo(60);
        assertThat(reservationRepository.findById(victim.getId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.PENDING_REARRANGE);
    }

    @Test
    void concurrentRearrangeAndCancelProduceExactlyOneOutcome() throws Exception {
        bookingService.registerProposal("TOO-BIG2", Set.of("CAM"), 100_000, true, 10, VALID_UNTIL);
        int iterations = 12;
        var executor = Executors.newFixedThreadPool(8);
        try {
            for (int i = 0; i < iterations; i++) {
                final int index = i;
                final String key = "rc-" + i;
                var victim = bookingService.book(key + "-b", "P1", "T1", "CAM",
                        at(index, 0), at(index, 30)).reservation();
                preemptionService.confirm(key + "-k", "TOO-BIG2", "T1", "CAM",
                        at(index, 0), at(index, 30));
                long victimId = victim.getId();
                CountDownLatch start = new CountDownLatch(1);

                Future<?> rearrangeFuture = executor.submit(() -> {
                    start.await();
                    try {
                        preemptionService.rearrange(victimId, key + "-rr",
                                atDay2(index, 0), atDay2(index, 30));
                    } catch (RuntimeException ignored) {
                        // 取消先到时重排失败
                    }
                    return null;
                });
                Future<?> cancelFuture = executor.submit(() -> {
                    start.await();
                    try {
                        bookingService.cancel(victimId);
                    } catch (RuntimeException ignored) {
                        // 重排先到时取消失败
                    }
                    return null;
                });
                start.countDown();
                rearrangeFuture.get();
                cancelFuture.get();
            }
        } finally {
            executor.shutdown();
        }

        long rearranged = reservationRepository.findAll().stream()
                .filter(r -> r.getStatus() == ReservationStatus.PREEMPTED).count();
        long cancelled = reservationRepository.findAll().stream()
                .filter(r -> r.getStatus() == ReservationStatus.CANCELLED
                        && r.getRearrangedTo() == null && r.getRearrangedFrom() == null).count();
        // 每轮恰有一种结局：要么重排成功（PREEMPTED + 新预订扣 30），要么取消（CANCELLED，不扣）
        assertThat(rearranged + cancelled).isEqualTo(iterations);
        long newActiveFromRearrange = reservationRepository.findAll().stream()
                .filter(r -> r.getRearrangedFrom() != null && r.getStatus() == ReservationStatus.ACTIVE)
                .count();
        assertThat(newActiveFromRearrange).isEqualTo(rearranged);
        // P1 已用 = 30 * 重排成功数，绝无重复消费
        assertThat(600 - bookingService.quota("P1").getRemainingQuotaMinutes())
                .isEqualTo(rearranged * 30);
    }

    @Test
    void concurrentRearrangementsIntoSameSlotAllowOnlyOneWinner() throws Exception {
        var victim1 = bookingService.book("a", "P1", "T1", "CAM", at(10, 0), at(10, 30)).reservation();
        var victim2 = bookingService.book("b", "P1", "T1", "CAM", at(10, 30), at(11, 0)).reservation();
        preemptionService.confirm("BK-S", "TOO", "T1", "CAM", at(10, 0), at(11, 0));

        AtomicInteger successes = new AtomicInteger();
        List<Callable<Void>> tasks = new ArrayList<>();
        for (long rawId : List.of(victim1.getId(), victim2.getId())) {
            final long id = rawId;
            tasks.add(() -> {
                try {
                    preemptionService.rearrange(id, "rr-slot-" + id, at(14, 0), at(14, 30));
                    successes.incrementAndGet();
                } catch (ScheduleConflictException expected) {
                    // 同一新时段只能成交一笔
                }
                return null;
            });
        }
        runConcurrently(tasks);

        assertThat(successes.get()).isEqualTo(1);
        // 只有一笔重排成功才扣 30 分钟
        assertThat(600 - bookingService.quota("P1").getRemainingQuotaMinutes()).isEqualTo(30);
    }

    private static void runConcurrently(List<Callable<Void>> tasks) throws Exception {
        var executor = Executors.newFixedThreadPool(tasks.size());
        try {
            List<Future<Void>> futures = new ArrayList<>();
            for (Callable<Void> task : tasks) {
                futures.add(executor.submit(task));
            }
            for (Future<Void> future : futures) {
                future.get();
            }
        } finally {
            executor.shutdown();
        }
    }
}
