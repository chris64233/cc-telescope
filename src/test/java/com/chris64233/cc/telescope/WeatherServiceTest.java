package com.chris64233.cc.telescope;

import com.chris64233.cc.telescope.domain.Reservation;
import com.chris64233.cc.telescope.domain.ReservationStatus;
import com.chris64233.cc.telescope.domain.WeatherAffectedStatus;
import com.chris64233.cc.telescope.repository.OpportunityProposalRepository;
import com.chris64233.cc.telescope.repository.ProposalRepository;
import com.chris64233.cc.telescope.repository.ReservationRepository;
import com.chris64233.cc.telescope.repository.TelescopeRepository;
import com.chris64233.cc.telescope.repository.WeatherAffectedRecordRepository;
import com.chris64233.cc.telescope.repository.WeatherEventRepository;
import com.chris64233.cc.telescope.service.BookingService;
import com.chris64233.cc.telescope.service.BusinessRuleException;
import com.chris64233.cc.telescope.service.PreemptionService;
import com.chris64233.cc.telescope.service.WeatherService;
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
class WeatherServiceTest {

    private static final Instant BASE = Instant.parse("2030-01-01T00:00:00Z");
    private static final Instant DEADLINE = Instant.parse("2030-01-03T00:00:00Z");

    @Autowired
    private WeatherService weatherService;
    @Autowired
    private BookingService bookingService;
    @Autowired
    private PreemptionService preemptionService;
    @Autowired
    private WeatherEventRepository weatherEventRepository;
    @Autowired
    private WeatherAffectedRecordRepository affectedRepository;
    @Autowired
    private ReservationRepository reservationRepository;
    @Autowired
    private ProposalRepository proposalRepository;
    @Autowired
    private OpportunityProposalRepository opportunityRepository;
    @Autowired
    private TelescopeRepository telescopeRepository;

    @BeforeEach
    void setUp() {
        // 删除顺序需满足外键：受影响记录 → 预订 → 天气事件 → 账户 → 望远镜
        affectedRepository.deleteAll();
        reservationRepository.deleteAll();
        weatherEventRepository.deleteAll();
        opportunityRepository.deleteAll();
        proposalRepository.deleteAll();
        telescopeRepository.deleteAll();

        bookingService.registerTelescope("T1", "山顶望远镜", 30, Set.of("CAM", "SPEC"));
        bookingService.registerProposal("P1", Set.of("CAM", "SPEC"), 600);
        bookingService.registerProposal("P2", Set.of("CAM", "SPEC"), 600);
        preemptionService.registerOpportunity("OP1", 9, DEADLINE, Set.of("CAM", "SPEC"), 300);
    }

    private static Instant at(int hour, int minute) {
        return BASE.plusSeconds((hour * 60L + minute) * 60L);
    }

    private Reservation book(String key, String proposal, String instrument, Instant start, Instant end) {
        return bookingService.book(key, proposal, "T1", instrument, start, end).reservation();
    }

    // ------------------------------------------------------------------
    // 关闭：原子标记未执行观测并释放资源
    // ------------------------------------------------------------------

    @Test
    void closeInterruptsOnlyFutureReservationsAndReleasesQuota() {
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        // 正在执行（开始于 30 分钟前、30 分钟后结束，SPEC）：与窗口重叠但保持不变
        var running = book("k-running", "P1", "SPEC", now.minusSeconds(1800), now.plusSeconds(1800));
        // 已完成（1-2 小时前，CAM）：保持不变
        var done = book("k-done", "P2", "CAM", now.minusSeconds(7200), now.minusSeconds(3600));
        // 未来观测（3-4 小时后，CAM）：将被中断
        var future = book("k-future", "P1", "CAM", now.plusSeconds(3 * 3600L), now.plusSeconds(4 * 3600L));
        // 未来但远在关闭窗口之外：保持不变
        var outside = book("k-outside", "P2", "CAM", now.plusSeconds(8 * 3600L), now.plusSeconds(9 * 3600L));

        var outcome = weatherService.close("W-1", "T1", "暴雨",
                now.minusSeconds(4 * 3600L), now.plusSeconds(5 * 3600L));

        assertThat(outcome.created()).isTrue();
        assertThat(outcome.affected()).hasSize(1);

        var interrupted = bookingService.findReservation(future.getId());
        assertThat(interrupted.getStatus()).isEqualTo(ReservationStatus.WEATHER_CANCELLED);
        assertThat(interrupted.getWeatherEvent().getBusinessKey()).isEqualTo("W-1");
        // P1：未来观测 60 分钟已归还；正在执行的 60 分钟仍占用 → 剩余 540
        assertThat(bookingService.quota("P1").getRemainingQuotaMinutes()).isEqualTo(540);
        // P2：已完成 60 + 窗口外未来 60 仍占用 → 剩余 480
        assertThat(bookingService.quota("P2").getRemainingQuotaMinutes()).isEqualTo(480);

        assertThat(reservationRepository.findById(running.getId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.ACTIVE);
        assertThat(reservationRepository.findById(done.getId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.ACTIVE);
        assertThat(reservationRepository.findById(outside.getId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.ACTIVE);

        var scheduleIds = bookingService.schedule("T1").stream().map(Reservation::getId).toList();
        assertThat(scheduleIds).contains(running.getId(), done.getId(), outside.getId())
                .doesNotContain(future.getId());
    }

    @Test
    void affectedRecordKeepsOriginalPriorityAndRecoverableMinutes() {
        // 机会观测（带优先级）被天气中断后，受影响记录保留优先级与分钟数
        var opp = preemptionService.confirm("PRE-OP", "OP1", "T1", "CAM", at(10, 0), at(11, 0));
        Long oppReservationId = opp.getOpportunityReservationId();

        weatherService.close("W-OP", "T1", "大风", at(9, 0), at(12, 0));

        var affected = weatherService.affectedByEvent("W-OP");
        assertThat(affected).hasSize(1);
        var a = affected.get(0);
        assertThat(a.getOwnerType()).isEqualTo("OPPORTUNITY");
        assertThat(a.getOwnerCode()).isEqualTo("OP1");
        assertThat(a.getPriority()).isEqualTo(9);
        assertThat(a.getRecoverableMinutes()).isEqualTo(60);
        assertThat(a.getOriginalDurationMinutes()).isEqualTo(60);
        assertThat(a.getStatus()).isEqualTo(WeatherAffectedStatus.AFFECTED);
        assertThat(a.getOriginalReservation().getId()).isEqualTo(oppReservationId);
        // 机会配额在关闭时已归还
        assertThat(preemptionService.opportunityQuota("OP1").getRemainingQuotaMinutes()).isEqualTo(300);
    }

    @Test
    void closeIsIdempotentByBusinessKeyAndDoesNotReleaseTwice() {
        book("k1", "P1", "CAM", at(10, 0), at(11, 0));

        var first = weatherService.close("W-IDEM", "T1", "雨", at(10, 0), at(11, 0));
        var replay = weatherService.close("W-IDEM", "T1", "雨", at(10, 0), at(11, 0));

        assertThat(replay.created()).isFalse();
        assertThat(replay.event().getId()).isEqualTo(first.event().getId());
        assertThat(weatherService.affectedByEvent("W-IDEM")).hasSize(1);
        // 配额恰好归还一次
        assertThat(bookingService.quota("P1").getRemainingQuotaMinutes()).isEqualTo(600);

        assertThatThrownBy(() -> weatherService.close("W-IDEM", "T1", "雨", at(10, 0), at(12, 0)))
                .isInstanceOf(com.chris64233.cc.telescope.service.IdempotencyConflictException.class);
    }

    // ------------------------------------------------------------------
    // 恢复排期
    // ------------------------------------------------------------------

    @Test
    void recoverBooksNewSlotConsumesQuotaAndCompletesAffected() {
        var original = book("k1", "P1", "CAM", at(10, 0), at(11, 0));
        weatherService.close("W-2", "T1", "雨", at(10, 0), at(11, 0));
        Long affectedId = weatherService.affectedByEvent("W-2").get(0).getId();
        assertThat(bookingService.quota("P1").getRemainingQuotaMinutes()).isEqualTo(600);

        var outcome = weatherService.recover(affectedId, "rc-1", "T1", "CAM", at(14, 0), at(15, 0));

        assertThat(outcome.created()).isTrue();
        assertThat(outcome.newReservation().getStatus()).isEqualTo(ReservationStatus.ACTIVE);
        // 恢复成功重新扣减配额
        assertThat(bookingService.quota("P1").getRemainingQuotaMinutes()).isEqualTo(540);

        var affected = outcome.affected();
        assertThat(affected.getStatus()).isEqualTo(WeatherAffectedStatus.RECOVERED);
        assertThat(affected.getRecoverableMinutes()).isZero();
        assertThat(affected.getRecoveryBookings()).hasSize(1);
        assertThat(affected.getRecoveryBookings().get(0).getNewReservationId())
                .isEqualTo(outcome.newReservation().getId());

        var old = reservationRepository.findById(original.getId()).orElseThrow();
        assertThat(old.getStatus()).isEqualTo(ReservationStatus.WEATHER_RECOVERED);
        assertThat(old.getWeatherRecoveredToId()).isEqualTo(outcome.newReservation().getId());
    }

    @Test
    void recoverCanBePartialAndThenFullyRecovered() {
        book("k1", "P1", "CAM", at(10, 0), at(12, 0)); // 120 分钟
        weatherService.close("W-P", "T1", "雨", at(10, 0), at(12, 0));
        Long affectedId = weatherService.affectedByEvent("W-P").get(0).getId();

        // 第一次只恢复 60 分钟
        var first = weatherService.recover(affectedId, "rc-a", "T1", "CAM", at(14, 0), at(15, 0));
        assertThat(first.created()).isTrue();
        assertThat(first.affected().getStatus()).isEqualTo(WeatherAffectedStatus.AFFECTED);
        assertThat(first.affected().getRecoverableMinutes()).isEqualTo(60);
        // 原预订仍是 WEATHER_CANCELLED（尚未全部恢复）
        assertThat(first.affected().getOriginalReservation().getStatus())
                .isEqualTo(ReservationStatus.WEATHER_CANCELLED);
        assertThat(bookingService.quota("P1").getRemainingQuotaMinutes()).isEqualTo(540);

        // 第二次用完剩余 60 分钟
        var second = weatherService.recover(affectedId, "rc-b", "T1", "CAM", at(16, 0), at(17, 0));
        assertThat(second.affected().getStatus()).isEqualTo(WeatherAffectedStatus.RECOVERED);
        assertThat(second.affected().getRecoverableMinutes()).isZero();
        assertThat(second.affected().getRecoveryBookings()).hasSize(2);
        assertThat(bookingService.quota("P1").getRemainingQuotaMinutes()).isEqualTo(480);
    }

    @Test
    void recoverPreservesOpportunityPriorityAndEnforcesDeadline() {
        preemptionService.confirm("PRE-OP2", "OP1", "T1", "CAM", at(10, 0), at(11, 0));
        weatherService.close("W-OP2", "T1", "风", at(10, 0), at(11, 0));
        Long affectedId = weatherService.affectedByEvent("W-OP2").get(0).getId();

        var outcome = weatherService.recover(affectedId, "rc-op", "T1", "CAM", at(20, 0), at(21, 0));
        assertThat(outcome.newReservation().isOpportunityReservation()).isTrue();
        assertThat(outcome.newReservation().getPriority()).isEqualTo(9);
        assertThat(preemptionService.opportunityQuota("OP1").getRemainingQuotaMinutes()).isEqualTo(240);

        // 已 RECOVERED 的记录再次恢复被拒绝
        Long otherId = weatherService.affectedByEvent("W-OP2").get(0).getId();
        assertThatThrownBy(() -> weatherService.recover(otherId, "rc-late", "T1", "CAM",
                Instant.parse("2030-01-03T01:00:00Z"), Instant.parse("2030-01-03T02:00:00Z")))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void recoverBeyondRecoverableMinutesLeavesNoPartialOccupancy() {
        book("k1", "P1", "CAM", at(10, 0), at(11, 0)); // 60 分钟可恢复
        weatherService.close("W-3", "T1", "雨", at(10, 0), at(11, 0));
        Long affectedId = weatherService.affectedByEvent("W-3").get(0).getId();
        long reservationsBefore = reservationRepository.count();

        // 申请 90 分钟 > 60 可恢复
        assertThatThrownBy(() -> weatherService.recover(affectedId, "rc-big", "T1", "CAM",
                at(14, 0), at(15, 30)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("可恢复");

        // 没有新预订、配额未扣、可恢复分钟数不变
        assertThat(reservationRepository.count()).isEqualTo(reservationsBefore);
        assertThat(bookingService.quota("P1").getRemainingQuotaMinutes()).isEqualTo(600);
        var affected = weatherService.findAffected(affectedId);
        assertThat(affected.getStatus()).isEqualTo(WeatherAffectedStatus.AFFECTED);
        assertThat(affected.getRecoverableMinutes()).isEqualTo(60);
        assertThat(affected.getRecoveryBookings()).isEmpty();
    }

    @Test
    void recoverScheduleConflictLeavesNoPartialOccupancy() {
        // 被中断的观测
        book("k1", "P1", "CAM", at(10, 0), at(11, 0));
        weatherService.close("W-4", "T1", "雨", at(10, 0), at(11, 0));
        Long affectedId = weatherService.affectedByEvent("W-4").get(0).getId();
        // 一个仍然 ACTIVE 的观测占住 14:00-15:00
        book("k2", "P2", "CAM", at(14, 0), at(15, 0));

        assertThatThrownBy(() -> weatherService.recover(affectedId, "rc-conflict", "T1", "CAM",
                at(14, 0), at(15, 0)))
                .isInstanceOf(RuntimeException.class);

        // 配额与可恢复分钟数均未变
        assertThat(bookingService.quota("P1").getRemainingQuotaMinutes()).isEqualTo(600);
        assertThat(weatherService.findAffected(affectedId).getRecoverableMinutes()).isEqualTo(60);
        var p1Active = bookingService.schedule("T1").stream()
                .filter(r -> r.getOwnerCode().equals("P1")).count();
        assertThat(p1Active).isZero();
    }

    @Test
    void recoverIsIdempotentByKey() {
        book("k1", "P1", "CAM", at(10, 0), at(11, 0));
        weatherService.close("W-5", "T1", "雨", at(10, 0), at(11, 0));
        Long affectedId = weatherService.affectedByEvent("W-5").get(0).getId();

        var first = weatherService.recover(affectedId, "rc-idem", "T1", "CAM", at(16, 0), at(17, 0));
        var replay = weatherService.recover(affectedId, "rc-idem", "T1", "CAM", at(16, 0), at(17, 0));

        assertThat(replay.created()).isFalse();
        assertThat(replay.newReservation().getId()).isEqualTo(first.newReservation().getId());
        assertThat(bookingService.quota("P1").getRemainingQuotaMinutes()).isEqualTo(540);
        assertThat(weatherService.findAffected(affectedId).getRecoveryBookings()).hasSize(1);
    }

    @Test
    void cannotRecoverNonAffectedRecord() {
        book("k1", "P1", "CAM", at(10, 0), at(11, 0));
        weatherService.close("W-6", "T1", "雨", at(10, 0), at(11, 0));
        Long affectedId = weatherService.affectedByEvent("W-6").get(0).getId();
        weatherService.recover(affectedId, "rc-done", "T1", "CAM", at(16, 0), at(17, 0));

        assertThatThrownBy(() -> weatherService.recover(affectedId, "rc-again", "T1", "CAM",
                at(18, 0), at(19, 0)))
                .isInstanceOf(com.chris64233.cc.telescope.service.RescheduleNotAllowedException.class);
    }

    // ------------------------------------------------------------------
    // 范围调整（扩大 / 缩小）
    // ------------------------------------------------------------------

    @Test
    void adjustWindowExpandsToInterruptMoreAndShrinksToRestore() {
        var r1 = book("k1", "P1", "CAM", at(10, 0), at(11, 0));
        var r2 = book("k2", "P1", "CAM", at(13, 0), at(14, 0));

        // 初始窗口只覆盖 10:00-11:00
        weatherService.close("W-A", "T1", "雨", at(9, 30), at(11, 30));
        assertThat(weatherService.affectedByEvent("W-A")).hasSize(1);
        assertThat(bookingService.quota("P1").getRemainingQuotaMinutes()).isEqualTo(540);

        // 扩大到覆盖 13:00-14:00
        var expanded = weatherService.adjustWindow("W-A", "T1", at(9, 30), at(14, 30));
        assertThat(expanded.event().getWindowVersion()).isEqualTo(1);
        assertThat(expanded.event().getWindowHistory()).hasSize(2);
        assertThat(weatherService.affectedByEvent("W-A")).hasSize(2);
        assertThat(bookingService.quota("P1").getRemainingQuotaMinutes()).isEqualTo(600);
        assertThat(reservationRepository.findById(r2.getId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.WEATHER_CANCELLED);

        // 缩小回原窗口：r2 不再受影响，原时段空闲，应还原
        var shrunk = weatherService.adjustWindow("W-A", "T1", at(9, 30), at(11, 30));
        assertThat(shrunk.event().getWindowVersion()).isEqualTo(2);
        assertThat(reservationRepository.findById(r2.getId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.ACTIVE);
        // 还原后重新占用 60 分钟
        assertThat(bookingService.quota("P1").getRemainingQuotaMinutes()).isEqualTo(540);
        // r2 的受影响记录变为 RESTORED，r1 仍 AFFECTED
        var statuses = weatherService.affectedByEvent("W-A").stream()
                .map(a -> a.getOriginalReservation().getId() + ":" + a.getStatus())
                .toList();
        assertThat(statuses).contains(r1.getId() + ":" + WeatherAffectedStatus.AFFECTED.name(),
                r2.getId() + ":" + WeatherAffectedStatus.RESTORED.name());
    }

    @Test
    void adjustSameWindowIsIdempotent() {
        book("k1", "P1", "CAM", at(10, 0), at(11, 0));
        weatherService.close("W-B", "T1", "雨", at(10, 0), at(11, 0));

        var replay = weatherService.adjustWindow("W-B", "T1", at(10, 0), at(11, 0));
        assertThat(replay.created()).isFalse();
        assertThat(replay.event().getWindowVersion()).isZero();
        assertThat(bookingService.quota("P1").getRemainingQuotaMinutes()).isEqualTo(600);
    }

    @Test
    void shrinkDoesNotRestoreWhenOriginalSlotOccupied() {
        var r1 = book("k1", "P1", "CAM", at(10, 0), at(11, 0));
        weatherService.close("W-C", "T1", "雨", at(9, 0), at(12, 0));
        Long affectedId = weatherService.affectedByEvent("W-C").get(0).getId();
        // 恢复到 13:00-14:00 后，原 10:00-11:00 仍空，但下面用别的提案占住原时段
        var blocker = book("k-block", "P2", "CAM", at(10, 0), at(11, 0));

        // 缩小窗口使其不再覆盖 r1 的原时段，但原时段已被 blocker 占用 → 无法还原
        weatherService.adjustWindow("W-C", "T1", at(7, 0), at(8, 0));

        var affected = weatherService.findAffected(affectedId);
        assertThat(affected.getStatus()).isEqualTo(WeatherAffectedStatus.AFFECTED);
        assertThat(reservationRepository.findById(r1.getId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.WEATHER_CANCELLED);
        // 未重新扣减
        assertThat(bookingService.quota("P1").getRemainingQuotaMinutes()).isEqualTo(600);
        assertThat(reservationRepository.findById(blocker.getId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.ACTIVE);
    }

    // ------------------------------------------------------------------
    // 放弃
    // ------------------------------------------------------------------

    @Test
    void abandonClearsRecoverableMinutesAndIsIdempotent() {
        book("k1", "P1", "CAM", at(10, 0), at(11, 0));
        weatherService.close("W-D", "T1", "雨", at(10, 0), at(11, 0));
        Long affectedId = weatherService.affectedByEvent("W-D").get(0).getId();

        var abandoned = weatherService.abandon(affectedId);
        assertThat(abandoned.affected().getStatus()).isEqualTo(WeatherAffectedStatus.ABANDONED);
        assertThat(abandoned.affected().getRecoverableMinutes()).isZero();
        // 关闭时已释放，放弃不再触碰配额
        assertThat(bookingService.quota("P1").getRemainingQuotaMinutes()).isEqualTo(600);

        var again = weatherService.abandon(affectedId);
        assertThat(again.affected().getStatus()).isEqualTo(WeatherAffectedStatus.ABANDONED);
        assertThat(bookingService.quota("P1").getRemainingQuotaMinutes()).isEqualTo(600);
    }

    // ------------------------------------------------------------------
    // 并发：不能重复释放或消耗分钟数
    // ------------------------------------------------------------------

    @Test
    void concurrentCloseSameBusinessKeyAppliesOnce() throws Exception {
        book("k1", "P1", "CAM", at(10, 0), at(11, 0));
        int errors = runConcurrently(8, () ->
                weatherService.close("W-RACE", "T1", "雨", at(10, 0), at(11, 0)));

        assertThat(errors).isZero();
        assertThat(weatherEventRepository.findByBusinessKey("W-RACE")).isPresent();
        assertThat(weatherService.affectedByEvent("W-RACE")).hasSize(1);
        assertThat(bookingService.quota("P1").getRemainingQuotaMinutes()).isEqualTo(600);
    }

    @Test
    void concurrentCancelAndCloseNeverDoubleRelease() throws Exception {
        int iterations = 8;
        for (int idx = 0; idx < iterations; idx++) {
            Instant start = at(20 + idx, 0);
            Instant end = at(20 + idx, 30);
            var normal = bookingService.book("w-cr-" + idx, "P1", "T1", "CAM", start, end).reservation();
            String businessKey = "W-CR-" + idx;

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
                    weatherService.close(businessKey, "T1", "雨", start, end);
                    return null;
                });
                latch.countDown();
                f1.get();
                f2.get();
            } finally {
                pool.shutdown();
            }

            // 无论取消还是关闭先执行，P1 的 30 分钟都恰好释放一次：
            // 剩余配额 + 仍 ACTIVE 的 P1 预订分钟 = 600
            long remaining = bookingService.quota("P1").getRemainingQuotaMinutes();
            long activeNormalMinutes = bookingService.schedule("T1").stream()
                    .filter(r -> !r.isOpportunityReservation() && r.getOwnerCode().equals("P1"))
                    .mapToLong(Reservation::getDurationMinutes).sum();
            assertThat(remaining + activeNormalMinutes)
                    .as("第 %d 轮配额守恒（剩余 %d + 有效 %d）", idx, remaining, activeNormalMinutes)
                    .isEqualTo(600);
        }
    }

    @Test
    void concurrentRecoverSameKeyConsumesOnce() throws Exception {
        book("k1", "P1", "CAM", at(10, 0), at(11, 0));
        weatherService.close("W-RR", "T1", "雨", at(10, 0), at(11, 0));
        Long affectedId = weatherService.affectedByEvent("W-RR").get(0).getId();

        int errors = runConcurrently(6, () ->
                weatherService.recover(affectedId, "rc-race", "T1", "CAM", at(18, 0), at(19, 0)));

        assertThat(errors).isZero();
        assertThat(bookingService.quota("P1").getRemainingQuotaMinutes()).isEqualTo(540);
        long recoveredBookings = bookingService.schedule("T1").stream()
                .filter(r -> r.getOwnerCode().equals("P1")).count();
        assertThat(recoveredBookings).isEqualTo(1);
        assertThat(weatherService.findAffected(affectedId).getRecoveryBookings()).hasSize(1);
    }

    @Test
    void concurrentRecoverDifferentKeysOnlyOneFullyWins() throws Exception {
        // 可恢复 60 分钟：多个不同键并发申请整段恢复，只有一个能成功扣减
        book("k1", "P1", "CAM", at(10, 0), at(11, 0));
        weatherService.close("W-RD", "T1", "雨", at(10, 0), at(11, 0));
        Long affectedId = weatherService.affectedByEvent("W-RD").get(0).getId();

        int threads = 6;
        AtomicInteger winners = new AtomicInteger();
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int index = i;
            tasks.add(() -> {
                try {
                    var outcome = weatherService.recover(affectedId, "rc-diff-" + index, "T1", "CAM",
                            at(20, 0), at(21, 0));
                    if (outcome.created()) {
                        winners.incrementAndGet();
                    }
                } catch (RuntimeException expected) {
                    // 可恢复分钟不足或已恢复
                }
                return null;
            });
        }
        execute(tasks);

        assertThat(winners.get()).isEqualTo(1);
        assertThat(bookingService.quota("P1").getRemainingQuotaMinutes()).isEqualTo(540);
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
