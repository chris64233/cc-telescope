package com.chris64233.cc.telescope;

import com.chris64233.cc.telescope.domain.Reservation;
import com.chris64233.cc.telescope.domain.ReservationStatus;
import com.chris64233.cc.telescope.repository.OpportunityProposalRepository;
import com.chris64233.cc.telescope.repository.PreemptionRecordRepository;
import com.chris64233.cc.telescope.repository.ProposalRepository;
import com.chris64233.cc.telescope.repository.ReservationRepository;
import com.chris64233.cc.telescope.repository.TelescopeRepository;
import com.chris64233.cc.telescope.service.BookingService;
import com.chris64233.cc.telescope.service.BusinessRuleException;
import com.chris64233.cc.telescope.service.IdempotencyConflictException;
import com.chris64233.cc.telescope.service.PreemptionService;
import com.chris64233.cc.telescope.service.RescheduleNotAllowedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

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
    private static final Instant DEADLINE = Instant.parse("2030-01-02T00:00:00Z");

    @Autowired
    private BookingService bookingService;
    @Autowired
    private PreemptionService preemptionService;
    @Autowired
    private ReservationRepository reservationRepository;
    @Autowired
    private ProposalRepository proposalRepository;
    @Autowired
    private TelescopeRepository telescopeRepository;
    @Autowired
    private OpportunityProposalRepository opportunityRepository;
    @Autowired
    private PreemptionRecordRepository preemptionRecordRepository;
    @Autowired
    private com.chris64233.cc.telescope.repository.WeatherAffectedRecordRepository weatherAffectedRepository;
    @Autowired
    private com.chris64233.cc.telescope.repository.WeatherEventRepository weatherEventRepository;

    @BeforeEach
    void setUp() {
        weatherAffectedRepository.deleteAll();
        preemptionRecordRepository.deleteAll();
        reservationRepository.deleteAll();
        weatherEventRepository.deleteAll();
        opportunityRepository.deleteAll();
        proposalRepository.deleteAll();
        telescopeRepository.deleteAll();

        bookingService.registerTelescope("T1", "山顶望远镜", 30, Set.of("CAM", "SPEC"));
        bookingService.registerProposal("P1", Set.of("CAM", "SPEC"), 600);
        bookingService.registerProposal("P2", Set.of("CAM", "SPEC"), 600);
        preemptionService.registerOpportunity("OP-HI", 10, DEADLINE, Set.of("CAM", "SPEC"), 300);
    }

    private static Instant at(int hour, int minute) {
        return BASE.plusSeconds((hour * 60L + minute) * 60L);
    }

    private Reservation book(String key, String proposal, String instrument,
                             Instant start, Instant end) {
        return bookingService.book(key, proposal, "T1", instrument, start, end).reservation();
    }

    // ------------------------------------------------------------------
    // 试算
    // ------------------------------------------------------------------

    @Test
    void planComputesAffectedReservationsAndSwitchTimes() {
        book("k1", "P1", "CAM", at(9, 0), at(10, 0));
        book("k2", "P1", "CAM", at(10, 0), at(11, 0));
        book("k3", "P1", "SPEC", at(13, 0), at(14, 0));

        var plan = preemptionService.plan("OP-HI", "T1", "CAM", at(10, 30), at(12, 0));

        assertThat(plan.feasible()).isTrue();
        assertThat(plan.conflictReasons()).isEmpty();
        assertThat(plan.affectedReservations()).hasSize(1);
        var affected = plan.affectedReservations().get(0);
        assertThat(affected.getReservationId()).isNotNull();
        assertThat(affected.getOwnerType()).isEqualTo("NORMAL");
        assertThat(affected.getOwnerCode()).isEqualTo("P1");
        assertThat(affected.isPreemptable()).isTrue();
        // 前邻 09:00-10:00 CAM 同仪器：无需切换
        assertThat(plan.switchBefore().neighborReservationId()).isNotNull();
        assertThat(plan.switchBefore().requiredSwitchMinutes()).isZero();
        assertThat(plan.switchBefore().availableGapMinutes()).isEqualTo(30);
        assertThat(plan.switchBefore().feasible()).isTrue();
        // 后邻 13:00 SPEC：CAM→SPEC 需要 30 分钟，可用 60 分钟
        assertThat(plan.switchAfter().neighborReservationId()).isNotNull();
        assertThat(plan.switchAfter().requiredSwitchMinutes()).isEqualTo(30);
        assertThat(plan.switchAfter().availableGapMinutes()).isEqualTo(60);
        assertThat(plan.switchAfter().feasible()).isTrue();
    }

    @Test
    void planDetectsInsufficientSwitchGap() {
        book("k1", "P1", "CAM", at(10, 0), at(11, 0));
        book("k2", "P1", "SPEC", at(11, 30), at(12, 0));

        // 覆盖 10-11 后，新区间 10:30-11:15 与后邻 SPEC 11:30 之间只有 15 分钟
        var plan = preemptionService.plan("OP-HI", "T1", "CAM", at(10, 30), at(11, 15));

        assertThat(plan.feasible()).isFalse();
        assertThat(plan.conflictReasons()).anyMatch(r -> r.contains("切换至少需要"));
        assertThat(plan.switchAfter().feasible()).isFalse();
    }

    // ------------------------------------------------------------------
    // 确认抢占：成功路径
    // ------------------------------------------------------------------

    @Test
    void confirmPreemptsNormalReservationsRefundsQuotaAndBumpsVersion() {
        var normal = book("k1", "P1", "CAM", at(10, 0), at(11, 0));

        var record = preemptionService.confirm("BK-1", "OP-HI", "T1", "CAM", at(10, 30), at(11, 30));

        assertThat(record.getStatus().name()).isEqualTo("CONFIRMED");
        assertThat(record.getScheduleVersionBefore()).isZero();
        assertThat(record.getScheduleVersionAfter()).isEqualTo(1);
        assertThat(record.getOpportunityReservationId()).isNotNull();

        var preempted = bookingService.findReservation(normal.getId());
        assertThat(preempted.getStatus()).isEqualTo(ReservationStatus.PENDING_RESCHEDULE);
        assertThat(preempted.getPreemptedBy().getCode()).isEqualTo("OP-HI");
        assertThat(preempted.getPreemptedByBusinessKey()).isEqualTo("BK-1");

        // 普通提案归还 60 分钟，机会提案消费 60 分钟
        assertThat(bookingService.quota("P1").getRemainingQuotaMinutes()).isEqualTo(600);
        assertThat(preemptionService.opportunityQuota("OP-HI").getRemainingQuotaMinutes()).isEqualTo(240);

        // 日程只剩机会预订
        var schedule = bookingService.schedule("T1");
        assertThat(schedule).hasSize(1);
        assertThat(schedule.get(0).getId()).isEqualTo(record.getOpportunityReservationId());
        assertThat(schedule.get(0).isOpportunityReservation()).isTrue();
        assertThat(schedule.get(0).getPriority()).isEqualTo(10);

        // 配额变化与前后日程快照
        assertThat(record.getQuotaChanges()).hasSize(2);
        assertThat(record.getQuotaChanges()).anySatisfy(c -> {
            assertThat(c.getOwnerType()).isEqualTo("NORMAL");
            assertThat(c.getOwnerCode()).isEqualTo("P1");
            assertThat(c.getDeltaMinutes()).isEqualTo(60);
            assertThat(c.getReason()).isEqualTo("REFUND_PREEMPTED");
        });
        assertThat(record.getQuotaChanges()).anySatisfy(c -> {
            assertThat(c.getOwnerType()).isEqualTo("OPPORTUNITY");
            assertThat(c.getDeltaMinutes()).isEqualTo(-60);
            assertThat(c.getReason()).isEqualTo("CONSUME_OPPORTUNITY");
        });
        assertThat(record.getScheduleBefore()).hasSize(1);
        assertThat(record.getScheduleAfter()).hasSize(1);
        assertThat(record.getScheduleAfter().get(0).getOwnerType()).isEqualTo("OPPORTUNITY");
    }

    @Test
    void confirmCancelsAllCoveredReservationsAtOnceAcrossProposals() {
        var r1 = book("k1", "P1", "CAM", at(10, 0), at(11, 0));
        var r2 = book("k2", "P2", "CAM", at(11, 0), at(12, 0));

        var record = preemptionService.confirm("BK-2", "OP-HI", "T1", "CAM", at(10, 30), at(12, 0));

        assertThat(record.getStatus().name()).isEqualTo("CONFIRMED");
        assertThat(record.getAffectedReservations()).hasSize(2);
        assertThat(reservationRepository.findById(r1.getId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.PENDING_RESCHEDULE);
        assertThat(reservationRepository.findById(r2.getId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.PENDING_RESCHEDULE);
        assertThat(bookingService.quota("P1").getRemainingQuotaMinutes()).isEqualTo(600);
        assertThat(bookingService.quota("P2").getRemainingQuotaMinutes()).isEqualTo(600);
        assertThat(preemptionService.opportunityQuota("OP-HI").getRemainingQuotaMinutes()).isEqualTo(210);
        assertThat(bookingService.schedule("T1")).hasSize(1);
    }

    @Test
    void confirmOnFreeWindowCreatesOpportunityReservationWithoutPreemption() {
        var record = preemptionService.confirm("BK-FREE", "OP-HI", "T1", "CAM", at(10, 0), at(11, 0));

        assertThat(record.getStatus().name()).isEqualTo("CONFIRMED");
        assertThat(record.getAffectedReservations()).isEmpty();
        assertThat(preemptionService.opportunityQuota("OP-HI").getRemainingQuotaMinutes()).isEqualTo(240);
        assertThat(bookingService.schedule("T1")).hasSize(1);
    }

    // ------------------------------------------------------------------
    // 拒绝路径：原日程完全不变
    // ------------------------------------------------------------------

    @Test
    void rejectWhenHigherOrEqualPriorityOpportunityReservationExists() {
        // 低优先级机会提案先抢占一个普通预订
        preemptionService.registerOpportunity("OP-LOW", 5, DEADLINE, Set.of("CAM"), 300);
        book("k1", "P1", "CAM", at(9, 0), at(10, 0));
        preemptionService.confirm("BK-A", "OP-LOW", "T1", "CAM", at(9, 0), at(10, 0));
        assertThat(bookingService.quota("P1").getRemainingQuotaMinutes()).isEqualTo(600);

        // OP-HI 优先级 10 > 5，可以抢占；而 OP-LOW 自己再次申请重叠区间不可抢占自己
        var blocked = preemptionService.confirm("BK-B", "OP-LOW", "T1", "CAM", at(9, 30), at(10, 30));

        assertThat(blocked.getStatus().name()).isEqualTo("REJECTED");
        assertThat(blocked.getConflictReasons()).anyMatch(r -> r.contains("不可抢占"));
        assertThat(blocked.getScheduleVersionBefore()).isEqualTo(blocked.getScheduleVersionAfter());
        // 日程与配额完全不变
        assertThat(bookingService.schedule("T1")).hasSize(1);
        assertThat(bookingService.schedule("T1").get(0).getOwnerCode()).isEqualTo("OP-LOW");
        assertThat(preemptionService.opportunityQuota("OP-LOW").getRemainingQuotaMinutes()).isEqualTo(240);
        assertThat(bookingService.quota("P1").getRemainingQuotaMinutes()).isEqualTo(600);
        // 快照中阻塞预订标记为不可抢占
        assertThat(blocked.getAffectedReservations()).hasSize(1);
        assertThat(blocked.getAffectedReservations().get(0).isPreemptable()).isFalse();

        // 更高优先级可以抢占它
        var override = preemptionService.confirm("BK-C", "OP-HI", "T1", "CAM", at(9, 0), at(10, 0));
        assertThat(override.getStatus().name()).isEqualTo("CONFIRMED");
        // 被抢占的机会预订直接取消并归还其机会配额
        assertThat(preemptionService.opportunityQuota("OP-LOW").getRemainingQuotaMinutes()).isEqualTo(300);
    }

    @Test
    void rejectWhenInstrumentIncompatibleLeavesScheduleUntouched() {
        preemptionService.registerOpportunity("OP-SPEC", 10, DEADLINE, Set.of("SPEC"), 300);
        book("k1", "P1", "CAM", at(10, 0), at(11, 0));

        var record = preemptionService.confirm("BK-INST", "OP-SPEC", "T1", "CAM", at(10, 0), at(11, 0));

        assertThat(record.getStatus().name()).isEqualTo("REJECTED");
        assertThat(record.getConflictReasons()).anyMatch(r -> r.contains("不允许使用仪器"));
        assertThat(bookingService.schedule("T1")).hasSize(1);
        assertThat(bookingService.schedule("T1").get(0).getOwnerCode()).isEqualTo("P1");
        assertThat(preemptionService.opportunityQuota("OP-SPEC").getRemainingQuotaMinutes()).isEqualTo(300);
    }

    @Test
    void rejectWhenOpportunityQuotaInsufficientLeavesScheduleUntouched() {
        preemptionService.registerOpportunity("OP-POOR", 10, DEADLINE, Set.of("CAM"), 30);
        book("k1", "P1", "CAM", at(10, 0), at(11, 0));

        var record = preemptionService.confirm("BK-Q", "OP-POOR", "T1", "CAM", at(10, 0), at(11, 0));

        assertThat(record.getStatus().name()).isEqualTo("REJECTED");
        assertThat(record.getConflictReasons()).anyMatch(r -> r.contains("配额不足"));
        assertThat(bookingService.schedule("T1")).hasSize(1);
        assertThat(bookingService.quota("P1").getRemainingQuotaMinutes()).isEqualTo(540);
        assertThat(preemptionService.opportunityQuota("OP-POOR").getRemainingQuotaMinutes()).isEqualTo(30);
    }

    @Test
    void rejectWhenWindowBeyondResponseDeadline() {
        preemptionService.registerOpportunity("OP-SHORT", 10, at(12, 0), Set.of("CAM"), 300);
        book("k1", "P1", "CAM", at(10, 0), at(11, 0));

        var record = preemptionService.confirm("BK-DL", "OP-SHORT", "T1", "CAM", at(12, 0), at(13, 0));

        assertThat(record.getStatus().name()).isEqualTo("REJECTED");
        assertThat(record.getConflictReasons()).anyMatch(r -> r.contains("响应时限"));
        assertThat(bookingService.schedule("T1")).hasSize(1);
    }

    // ------------------------------------------------------------------
    // 幂等
    // ------------------------------------------------------------------

    @Test
    void confirmIsIdempotentByBusinessKey() {
        book("k1", "P1", "CAM", at(10, 0), at(11, 0));

        var first = preemptionService.confirm("BK-IDEM", "OP-HI", "T1", "CAM", at(10, 0), at(11, 0));
        var replay = preemptionService.confirm("BK-IDEM", "OP-HI", "T1", "CAM", at(10, 0), at(11, 0));

        assertThat(replay.getId()).isEqualTo(first.getId());
        assertThat(replay.getOpportunityReservationId()).isEqualTo(first.getOpportunityReservationId());
        assertThat(bookingService.schedule("T1")).hasSize(1);
        assertThat(preemptionService.opportunityQuota("OP-HI").getRemainingQuotaMinutes()).isEqualTo(240);

        assertThatThrownBy(() -> preemptionService.confirm("BK-IDEM", "OP-HI", "T1", "CAM",
                at(12, 0), at(13, 0)))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void rejectedBusinessKeyReplaysOriginalRejection() {
        preemptionService.registerOpportunity("OP-SPEC", 10, DEADLINE, Set.of("SPEC"), 300);
        book("k1", "P1", "CAM", at(10, 0), at(11, 0));

        var rejected = preemptionService.confirm("BK-R", "OP-SPEC", "T1", "CAM", at(10, 0), at(11, 0));
        assertThat(rejected.getStatus().name()).isEqualTo("REJECTED");

        // 即使原预订被取消、窗口已空闲，同业务号仍返回原拒绝结果
        bookingService.cancel(rejected.getAffectedReservations().get(0).getReservationId());
        var replay = preemptionService.confirm("BK-R", "OP-SPEC", "T1", "CAM", at(10, 0), at(11, 0));
        assertThat(replay.getId()).isEqualTo(rejected.getId());
        assertThat(replay.getStatus().name()).isEqualTo("REJECTED");
        assertThat(preemptionService.opportunityQuota("OP-SPEC").getRemainingQuotaMinutes()).isEqualTo(300);
    }

    // ------------------------------------------------------------------
    // 待重排与重排
    // ------------------------------------------------------------------

    @Test
    void rescheduleSuccessDeductsQuotaAgainAndLinksReservations() {
        var normal = book("k1", "P1", "CAM", at(10, 0), at(11, 0));
        var record = preemptionService.confirm("BK-RS", "OP-HI", "T1", "CAM", at(10, 0), at(11, 0));

        var pending = preemptionService.pendingReschedules("OP-HI");
        assertThat(pending).extracting(Reservation::getId).containsExactly(normal.getId());

        var outcome = preemptionService.reschedule(normal.getId(), "rs-1", "T1", "CAM",
                at(14, 0), at(15, 0));

        assertThat(outcome.created()).isTrue();
        assertThat(outcome.newReservation().getStatus()).isEqualTo(ReservationStatus.ACTIVE);
        var old = reservationRepository.findById(normal.getId()).orElseThrow();
        assertThat(old.getStatus()).isEqualTo(ReservationStatus.RESCHEDULED);
        assertThat(old.getRescheduledToId()).isEqualTo(outcome.newReservation().getId());
        // 重排成功才再次扣减配额
        assertThat(bookingService.quota("P1").getRemainingQuotaMinutes()).isEqualTo(540);
        assertThat(preemptionService.pendingReschedules("OP-HI")).isEmpty();
        assertThat(bookingService.schedule("T1")).hasSize(2);
        assertThat(record.getId()).isNotNull();
    }

    @Test
    void rescheduleBeyondDeadlineIsRejectedAndQuotaUntouched() {
        preemptionService.registerOpportunity("OP-SHORT", 10, at(12, 0), Set.of("CAM"), 300);
        var normal = book("k1", "P1", "CAM", at(10, 0), at(11, 0));
        preemptionService.confirm("BK-S1", "OP-SHORT", "T1", "CAM", at(10, 0), at(11, 0));

        assertThatThrownBy(() -> preemptionService.reschedule(normal.getId(), "rs-x", "T1", "CAM",
                at(12, 30), at(13, 0)))
                .isInstanceOf(RescheduleNotAllowedException.class)
                .hasMessageContaining("响应时限");

        assertThat(reservationRepository.findById(normal.getId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.PENDING_RESCHEDULE);
        assertThat(bookingService.quota("P1").getRemainingQuotaMinutes()).isEqualTo(600);
    }

    @Test
    void rescheduleConflictLeavesPendingAndQuotaUntouched() {
        var normal = book("k1", "P1", "CAM", at(10, 0), at(11, 0));
        preemptionService.confirm("BK-S2", "OP-HI", "T1", "CAM", at(10, 0), at(11, 0));

        // 与机会预订重叠
        assertThatThrownBy(() -> preemptionService.reschedule(normal.getId(), "rs-y", "T1", "CAM",
                at(10, 30), at(11, 30)))
                .isInstanceOf(RuntimeException.class);

        assertThat(reservationRepository.findById(normal.getId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.PENDING_RESCHEDULE);
        assertThat(bookingService.quota("P1").getRemainingQuotaMinutes()).isEqualTo(600);
    }

    @Test
    void rescheduleRejectsNonPendingReservation() {
        var normal = book("k1", "P1", "CAM", at(10, 0), at(11, 0));

        assertThatThrownBy(() -> preemptionService.reschedule(normal.getId(), "rs-z", "T1", "CAM",
                at(14, 0), at(15, 0)))
                .isInstanceOf(RescheduleNotAllowedException.class);
    }

    @Test
    void cancelPendingReservationDoesNotRefundAgain() {
        var normal = book("k1", "P1", "CAM", at(10, 0), at(11, 0));
        preemptionService.confirm("BK-CP", "OP-HI", "T1", "CAM", at(10, 0), at(11, 0));
        assertThat(bookingService.quota("P1").getRemainingQuotaMinutes()).isEqualTo(600);

        var cancelled = bookingService.cancel(normal.getId());
        assertThat(cancelled.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        // 抢占时已归还，取消不能重复归还
        assertThat(bookingService.quota("P1").getRemainingQuotaMinutes()).isEqualTo(600);
        assertThat(preemptionService.pendingReschedules("OP-HI")).isEmpty();

        // 重复取消幂等
        assertThat(bookingService.cancel(normal.getId()).getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(bookingService.quota("P1").getRemainingQuotaMinutes()).isEqualTo(600);
    }

    @Test
    void cancelOpportunityReservationRefundsOpportunityQuota() {
        var record = preemptionService.confirm("BK-CO", "OP-HI", "T1", "CAM", at(10, 0), at(11, 0));
        assertThat(preemptionService.opportunityQuota("OP-HI").getRemainingQuotaMinutes()).isEqualTo(240);

        bookingService.cancel(record.getOpportunityReservationId());

        assertThat(preemptionService.opportunityQuota("OP-HI").getRemainingQuotaMinutes()).isEqualTo(300);
        assertThat(bookingService.schedule("T1")).isEmpty();
    }

    @Test
    void recordsAreQueryableByOpportunity() {
        preemptionService.confirm("BK-Q1", "OP-HI", "T1", "CAM", at(10, 0), at(11, 0));
        preemptionService.registerOpportunity("OP-SPEC", 10, DEADLINE, Set.of("SPEC"), 300);
        preemptionService.confirm("BK-Q2", "OP-SPEC", "T1", "CAM", at(10, 0), at(11, 0));

        var records = preemptionService.recordsByOpportunity("OP-HI");
        assertThat(records).hasSize(1);
        assertThat(records.get(0).getBusinessKey()).isEqualTo("BK-Q1");
        assertThat(preemptionService.findRecord("BK-Q2").getStatus().name()).isEqualTo("REJECTED");
    }

    // ------------------------------------------------------------------
    // 并发：不能重复归还或重复消费
    // ------------------------------------------------------------------

    @Test
    void concurrentConfirmsWithSameBusinessKeyApplyOnce() throws Exception {
        book("k1", "P1", "CAM", at(10, 0), at(11, 0));
        int threads = 8;
        int errors = runConcurrently(threads, () -> {
            try {
                preemptionService.confirm("BK-RACE1", "OP-HI", "T1", "CAM", at(10, 0), at(11, 0));
            } catch (IdempotencyConflictException e) {
                // 串行化后应直接返回原记录，不应出现此异常；出现则让计数失败
                throw e;
            }
        });

        assertThat(errors).isZero();
        assertThat(preemptionRecordRepository.findByBusinessKey("BK-RACE1")).isPresent();
        assertThat(bookingService.schedule("T1")).hasSize(1);
        assertThat(preemptionService.pendingReschedules("OP-HI")).hasSize(1);
        assertThat(bookingService.quota("P1").getRemainingQuotaMinutes()).isEqualTo(600);
        assertThat(preemptionService.opportunityQuota("OP-HI").getRemainingQuotaMinutes()).isEqualTo(240);
    }

    @Test
    void concurrentOverlappingPreemptionsConfirmAtMostOne() throws Exception {
        book("k1", "P1", "CAM", at(10, 0), at(11, 0));
        int threads = 6;
        AtomicInteger confirmed = new AtomicInteger();
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int index = i;
            tasks.add(() -> {
                var record = preemptionService.confirm("BK-MULTI-" + index, "OP-HI", "T1", "CAM",
                        at(10, 0), at(11, 0));
                if (record.getStatus().name().equals("CONFIRMED")) {
                    confirmed.incrementAndGet();
                }
                return null;
            });
        }
        execute(tasks);

        // 相同优先级的机会预订互相不可抢占，故只有第一个能确认
        assertThat(confirmed.get()).isEqualTo(1);
        assertThat(bookingService.schedule("T1")).hasSize(1);
        assertThat(preemptionService.opportunityQuota("OP-HI").getRemainingQuotaMinutes()).isEqualTo(240);
        assertThat(bookingService.quota("P1").getRemainingQuotaMinutes()).isEqualTo(600);
    }

    @Test
    void concurrentCancelAndPreemptionNeverDoubleRefund() throws Exception {
        int iterations = 8;
        for (int i = 0; i < iterations; i++) {
            // 每轮使用独立时段，避免轮次之间日程相互干扰
            Instant start = at(i, 0);
            Instant end = at(i, 30);
            String key = "normal-cr-" + i;
            var normal = bookingService.book(key, "P1", "T1", "CAM", start, end).reservation();
            String bk = "BK-CR-" + i;
            var latch = new CountDownLatch(1);
            var pool = Executors.newFixedThreadPool(2);
            try {
                var f1 = pool.submit(() -> {
                    latch.await();
                    bookingService.cancel(normal.getId());
                    return null;
                });
                var f2 = pool.submit(() -> {
                    latch.await();
                    preemptionService.confirm(bk, "OP-HI", "T1", "CAM", start, end);
                    return null;
                });
                latch.countDown();
                f1.get();
                f2.get();
            } finally {
                pool.shutdown();
            }

            // 无论取消还是抢占先执行：P1 的 30 分钟都恰好归还一次，
            // 而 T1 上 [start,end) 最终恰好保留一条有效预订（普通或机会）。
            // 配额守恒：P1 剩余 + 所有有效普通预订分钟 = 600。
            long remaining = bookingService.quota("P1").getRemainingQuotaMinutes();
            long activeNormalMinutes = bookingService.schedule("T1").stream()
                    .filter(r -> !r.isOpportunityReservation())
                    .mapToLong(Reservation::getDurationMinutes).sum();
            assertThat(remaining + activeNormalMinutes)
                    .as("第 %d 轮：配额守恒（剩余 %d + 有效普通预订 %d）", i, remaining, activeNormalMinutes)
                    .isEqualTo(600);
            var record = preemptionService.findRecord(bk);
            if (record.getStatus().name().equals("CONFIRMED")) {
                assertThat(preemptionService.opportunityQuota("OP-HI").getRemainingQuotaMinutes())
                        .as("第 %d 轮：机会抢占成功恰好消费一次", i)
                        .isEqualTo(300 - 30L * (i + 1));
            }
        }
    }

    @Test
    void concurrentReschedulesWithSameKeyDeductQuotaOnce() throws Exception {
        var normal = book("k1", "P1", "CAM", at(10, 0), at(11, 0));
        preemptionService.confirm("BK-RR", "OP-HI", "T1", "CAM", at(10, 0), at(11, 0));

        int threads = 6;
        int errors = runConcurrently(threads, () -> preemptionService.reschedule(
                normal.getId(), "rs-same", "T1", "CAM", at(14, 0), at(15, 0)));

        assertThat(errors).isZero();
        assertThat(bookingService.quota("P1").getRemainingQuotaMinutes()).isEqualTo(540);
        long newBookings = bookingService.schedule("T1").stream()
                .filter(r -> !r.isOpportunityReservation())
                .count();
        assertThat(newBookings).isEqualTo(1);
    }

    @Test
    void concurrentReschedulesDifferentKeysOnlyOneWins() throws Exception {
        var normal = book("k1", "P1", "CAM", at(10, 0), at(11, 0));
        preemptionService.confirm("BK-RD", "OP-HI", "T1", "CAM", at(10, 0), at(11, 0));

        int threads = 6;
        AtomicInteger winners = new AtomicInteger();
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int index = i;
            tasks.add(() -> {
                try {
                    var outcome = preemptionService.reschedule(normal.getId(), "rs-diff-" + index, "T1", "CAM",
                            at(14, 0), at(15, 0));
                    if (outcome.created()) {
                        winners.incrementAndGet();
                    }
                } catch (RuntimeException expected) {
                    // 时间冲突或状态已变更
                }
                return null;
            });
        }
        execute(tasks);

        assertThat(winners.get()).isEqualTo(1);
        assertThat(bookingService.quota("P1").getRemainingQuotaMinutes()).isEqualTo(540);
        var reloaded = reservationRepository.findById(normal.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ReservationStatus.RESCHEDULED);
    }

    @Test
    void registerRejectsPastDeadline() {
        assertThatThrownBy(() -> preemptionService.registerOpportunity(
                "OP-PAST", 1, Instant.parse("2020-01-01T00:00:00Z"), Set.of("CAM"), 100))
                .isInstanceOf(BusinessRuleException.class);
    }

    // ------------------------------------------------------------------

    private static int runConcurrently(int threads, ThrowingTask task) throws Exception {
        var pool = Executors.newFixedThreadPool(threads);
        AtomicInteger errors = new AtomicInteger();
        try {
            List<Future<Void>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    try {
                        task.run();
                    } catch (RuntimeException e) {
                        errors.incrementAndGet();
                    }
                    return null;
                }));
            }
            for (Future<Void> future : futures) {
                future.get();
            }
        } finally {
            pool.shutdown();
        }
        return errors.get();
    }

    private static void execute(List<Callable<Void>> tasks) throws Exception {
        var pool = Executors.newFixedThreadPool(tasks.size());
        try {
            for (Future<Void> future : pool.invokeAll(tasks)) {
                future.get();
            }
        } finally {
            pool.shutdown();
        }
    }

    @FunctionalInterface
    private interface ThrowingTask {
        void run();
    }
}
