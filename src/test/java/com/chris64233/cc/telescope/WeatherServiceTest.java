package com.chris64233.cc.telescope;

import com.chris64233.cc.telescope.domain.Reservation;
import com.chris64233.cc.telescope.domain.ReservationStatus;
import com.chris64233.cc.telescope.domain.WeatherRecoveryStatus;
import com.chris64233.cc.telescope.repository.OpportunityProposalRepository;
import com.chris64233.cc.telescope.repository.PreemptionRecordRepository;
import com.chris64233.cc.telescope.repository.ProposalRepository;
import com.chris64233.cc.telescope.repository.ReservationRepository;
import com.chris64233.cc.telescope.repository.TelescopeRepository;
import com.chris64233.cc.telescope.repository.WeatherEventRepository;
import com.chris64233.cc.telescope.repository.WeatherRecoveryRepository;
import com.chris64233.cc.telescope.service.BookingService;
import com.chris64233.cc.telescope.service.BusinessRuleException;
import com.chris64233.cc.telescope.service.IdempotencyConflictException;
import com.chris64233.cc.telescope.service.PreemptionService;
import com.chris64233.cc.telescope.service.ScheduleConflictException;
import com.chris64233.cc.telescope.service.WeatherRecoveryNotAllowedException;
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

    private static final Instant BASE = Instant.parse("2030-03-01T00:00:00Z");
    private static final Instant DEADLINE = Instant.parse("2030-03-03T00:00:00Z");

    @Autowired
    private BookingService bookingService;
    @Autowired
    private PreemptionService preemptionService;
    @Autowired
    private WeatherService weatherService;
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
    private WeatherEventRepository weatherEventRepository;
    @Autowired
    private WeatherRecoveryRepository weatherRecoveryRepository;

    @BeforeEach
    void setUp() {
        preemptionRecordRepository.deleteAll();
        // 先解除预订→恢复资格的出资外键，再按 恢复资格 → 预订 → 天气事件 顺序清理，避免循环外键
        reservationRepository.clearFundedWeatherRecoveryReferences();
        weatherRecoveryRepository.deleteAll();
        reservationRepository.deleteAll();
        weatherEventRepository.deleteAll();
        opportunityRepository.deleteAll();
        proposalRepository.deleteAll();
        telescopeRepository.deleteAll();

        bookingService.registerTelescope("T1", "山顶望远镜", 30, Set.of("CAM", "SPEC"));
        bookingService.registerProposal("P1", Set.of("CAM", "SPEC"), 600);
        bookingService.registerProposal("P2", Set.of("CAM"), 600);
        preemptionService.registerOpportunity("OP1", 8, DEADLINE, Set.of("CAM"), 300);
    }

    private static Instant at(int day, int hour, int minute) {
        return BASE.plusSeconds((day * 1440L + hour * 60L + minute) * 60L);
    }

    private Reservation book(String key, String proposal, String telescope, String instrument,
                             Instant start, Instant end) {
        return bookingService.book(key, proposal, telescope, instrument, start, end).reservation();
    }

    // ------------------------------------------------------------------
    // 关闭登记：原子标记未执行观测、释放资源、保留完成/执行中
    // ------------------------------------------------------------------

    @Test
    void declareBlocksOnlyNotStartedReservationsAndKeepsQuotaFrozen() {
        // 一条相对当前时间已经开始（过去）的观测：应保持不变
        var started = book("k0", "P2", "T1", "CAM",
                Instant.parse("2020-01-01T10:00:00Z"), Instant.parse("2020-01-01T11:00:00Z"));
        var upcoming = book("k1", "P1", "T1", "CAM", at(1, 10, 0), at(1, 11, 0));
        var upcoming2 = book("k2", "P1", "T1", "SPEC", at(1, 13, 0), at(1, 14, 0));

        // 超大窗口同时覆盖过去与未来
        var outcome = weatherService.declare("W-1", "T1",
                Instant.parse("2019-01-01T00:00:00Z"), at(2, 0, 0));

        assertThat(outcome.created()).isTrue();
        assertThat(outcome.blockedCount()).isEqualTo(2);

        // 已开始的观测保持 ACTIVE 不变；未来的两条被阻断
        assertThat(bookingService.findReservation(started.getId()).getStatus())
                .isEqualTo(ReservationStatus.ACTIVE);
        var blocked1 = bookingService.findReservation(upcoming.getId());
        assertThat(blocked1.getStatus()).isEqualTo(ReservationStatus.WEATHER_BLOCKED);
        assertThat(blocked1.getWeatherEvent().getBusinessKey()).isEqualTo("W-1");
        var blocked2 = bookingService.findReservation(upcoming2.getId());
        assertThat(blocked2.getStatus()).isEqualTo(ReservationStatus.WEATHER_BLOCKED);

        // 日程只剩已开始的观测
        assertThat(bookingService.schedule("T1")).extracting(Reservation::getId)
                .containsExactly(started.getId());

        // 配额保持冻结：P1 仍只扣了 k1+k2=120；阻断不退还到可消费配额
        assertThat(bookingService.quota("P1").getRemainingQuotaMinutes()).isEqualTo(480);

        // 可恢复分钟资格：60 分钟，保留原优先级（普通预订为 null）
        var rec1 = weatherService.findRecovery(upcoming.getId());
        assertThat(rec1.recovery().getBlockedMinutes()).isEqualTo(60);
        assertThat(rec1.recovery().getRemainingRecoverableMinutes()).isEqualTo(60);
        assertThat(rec1.recovery().getStatus()).isEqualTo(WeatherRecoveryStatus.BLOCKED);
        assertThat(rec1.recovery().getOriginalReservation().getPriority()).isNull();
    }

    @Test
    void declareKeepsAlreadyStartedReservationUntouched() {
        // 窗口在很遥远的未来，预订已结束/进行中相对于 now(2026) 不可能，因此构造窗口包含未来预订，
        // 另登记一条相对 now 已开始的预订（开始时间在过去），验证它不被阻断。
        var past = book("kp", "P1", "T1", "CAM",
                Instant.parse("2020-01-01T10:00:00Z"), Instant.parse("2020-01-01T11:00:00Z"));
        var future = book("kf", "P1", "T1", "CAM", at(1, 10, 0), at(1, 11, 0));

        // 窗口同时覆盖过去与未来：用一个超大窗口
        var outcome = weatherService.declare("W-PAST", "T1",
                Instant.parse("2019-01-01T00:00:00Z"), at(2, 0, 0));
        assertThat(outcome.blockedCount()).isEqualTo(1);

        assertThat(bookingService.findReservation(past.getId()).getStatus())
                .isEqualTo(ReservationStatus.ACTIVE);
        assertThat(bookingService.findReservation(future.getId()).getStatus())
                .isEqualTo(ReservationStatus.WEATHER_BLOCKED);
        // 过去的预订仍在“有效日程”中（按状态，不因时间自动失效）
        assertThat(bookingService.schedule("T1"))
                .extracting(Reservation::getId).containsExactly(past.getId());
    }

    @Test
    void declareIsIdempotentByBusinessKey() {
        var r = book("k1", "P1", "T1", "CAM", at(1, 10, 0), at(1, 11, 0));
        var first = weatherService.declare("W-IDEM", "T1", at(1, 9, 0), at(1, 12, 0));
        var replay = weatherService.declare("W-IDEM", "T1", at(1, 9, 0), at(1, 12, 0));

        assertThat(replay.created()).isFalse();
        assertThat(replay.event().getId()).isEqualTo(first.event().getId());
        assertThat(bookingService.findReservation(r.getId()).getStatus())
                .isEqualTo(ReservationStatus.WEATHER_BLOCKED);

        assertThatThrownBy(() -> weatherService.declare("W-IDEM", "T1", at(1, 9, 0), at(1, 13, 0)))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    // ------------------------------------------------------------------
    // 恢复排期：原优先级、只消耗可恢复分钟、幂等、失败不留占用
    // ------------------------------------------------------------------

    @Test
    void recoverConsumesOnlyRecoverableMinutesAndKeepsPriority() {
        var original = book("k1", "P1", "T1", "CAM", at(1, 10, 0), at(1, 11, 0));
        weatherService.declare("W-2", "T1", at(1, 9, 0), at(1, 12, 0));
        assertThat(bookingService.quota("P1").getRemainingQuotaMinutes()).isEqualTo(540);

        var outcome = weatherService.recover(original.getId(), "rc1", "T1", "CAM",
                at(1, 14, 0), at(1, 15, 0));

        assertThat(outcome.created()).isTrue();
        var rebooked = outcome.newReservation();
        assertThat(rebooked.getStatus()).isEqualTo(ReservationStatus.ACTIVE);
        assertThat(rebooked.getProposal().getCode()).isEqualTo("P1");
        assertThat(rebooked.getFundedByWeatherRecovery()).isNotNull();

        // 可恢复分钟耗尽：原预订 RECOVERED 并指向新预订
        var old = bookingService.findReservation(original.getId());
        assertThat(old.getStatus()).isEqualTo(ReservationStatus.RECOVERED);
        assertThat(old.getRecoveredToId()).isEqualTo(rebooked.getId());

        // 提案可消费配额不变（仍 540），恢复只消耗资格
        assertThat(bookingService.quota("P1").getRemainingQuotaMinutes()).isEqualTo(540);
        assertThat(outcome.recovery().getRemainingRecoverableMinutes()).isZero();
        assertThat(bookingService.schedule("T1")).extracting(Reservation::getId)
                .containsExactly(rebooked.getId());
    }

    @Test
    void recoverSupportsPartialRecoveryThenRemainder() {
        var original = book("k1", "P1", "T1", "CAM", at(1, 10, 0), at(1, 12, 0)); // 120 分钟
        weatherService.declare("W-3", "T1", at(1, 9, 0), at(1, 12, 30));

        var first = weatherService.recover(original.getId(), "p1", "T1", "CAM",
                at(1, 14, 0), at(1, 14, 30)); // 30 分钟
        assertThat(first.created()).isTrue();
        assertThat(first.recovery().getStatus()).isEqualTo(WeatherRecoveryStatus.PARTIALLY_RECOVERED);
        assertThat(first.recovery().getRemainingRecoverableMinutes()).isEqualTo(90);
        // 部分恢复期间原预订仍 WEATHER_BLOCKED
        assertThat(bookingService.findReservation(original.getId()).getStatus())
                .isEqualTo(ReservationStatus.WEATHER_BLOCKED);

        var second = weatherService.recover(original.getId(), "p2", "T1", "CAM",
                at(1, 16, 0), at(1, 17, 30)); // 90 分钟
        assertThat(second.recovery().getStatus()).isEqualTo(WeatherRecoveryStatus.RECOVERED);
        assertThat(second.recovery().getRemainingRecoverableMinutes()).isZero();
        assertThat(bookingService.findReservation(original.getId()).getStatus())
                .isEqualTo(ReservationStatus.RECOVERED);

        // 详情关联两条新预订与剩余分钟
        var view = weatherService.findRecovery(original.getId());
        assertThat(view.recoveredReservations()).hasSize(2);
        assertThat(view.recovery().getBlockedMinutes()).isEqualTo(120);
        assertThat(bookingService.quota("P1").getRemainingQuotaMinutes()).isEqualTo(480);
    }

    @Test
    void recoverBeyondRemainingMinutesIsRejected() {
        var original = book("k1", "P1", "T1", "CAM", at(1, 10, 0), at(1, 11, 0)); // 60
        weatherService.declare("W-4", "T1", at(1, 9, 0), at(1, 12, 0));

        assertThatThrownBy(() -> weatherService.recover(original.getId(), "big", "T1", "CAM",
                at(1, 14, 0), at(1, 15, 30))) // 90 > 60
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("可恢复分钟不足");

        // 未消耗：资格仍 60，无新预订，原预订仍阻断
        assertThat(weatherService.findRecovery(original.getId()).recovery()
                .getRemainingRecoverableMinutes()).isEqualTo(60);
        assertThat(bookingService.schedule("T1")).isEmpty();
    }

    @Test
    void recoverScheduleConflictLeavesNoPartialOccupation() {
        var keep = book("keep", "P2", "T1", "CAM", at(1, 14, 0), at(1, 15, 0));
        var original = book("k1", "P1", "T1", "CAM", at(1, 10, 0), at(1, 11, 0));
        weatherService.declare("W-5", "T1", at(1, 9, 0), at(1, 12, 0));

        // 与保留预订重叠
        assertThatThrownBy(() -> weatherService.recover(original.getId(), "conf", "T1", "CAM",
                at(1, 14, 30), at(1, 15, 30)))
                .isInstanceOf(ScheduleConflictException.class);

        // 无部分占用：日程仅有保留预订，资格未消耗
        assertThat(bookingService.schedule("T1")).extracting(Reservation::getId)
                .containsExactly(keep.getId());
        assertThat(weatherService.findRecovery(original.getId()).recovery()
                .getRemainingRecoverableMinutes()).isEqualTo(60);
        assertThat(bookingService.quota("P1").getRemainingQuotaMinutes()).isEqualTo(540);
    }

    @Test
    void recoverIntoOpenWeatherWindowIsRejected() {
        var original = book("k1", "P1", "T1", "CAM", at(1, 10, 0), at(1, 11, 0));
        weatherService.declare("W-OPEN", "T1", at(1, 9, 0), at(1, 12, 0));

        assertThatThrownBy(() -> weatherService.recover(original.getId(), "inwin", "T1", "CAM",
                at(1, 9, 30), at(1, 10, 30)))
                .isInstanceOf(ScheduleConflictException.class)
                .hasMessageContaining("天气关闭窗口");
    }

    @Test
    void recoverIsIdempotentByKey() {
        var original = book("k1", "P1", "T1", "CAM", at(1, 10, 0), at(1, 11, 0));
        weatherService.declare("W-6", "T1", at(1, 9, 0), at(1, 12, 0));

        var first = weatherService.recover(original.getId(), "rc-idem", "T1", "CAM",
                at(1, 14, 0), at(1, 15, 0));
        var replay = weatherService.recover(original.getId(), "rc-idem", "T1", "CAM",
                at(1, 14, 0), at(1, 15, 0));

        assertThat(replay.created()).isFalse();
        assertThat(replay.newReservation().getId()).isEqualTo(first.newReservation().getId());
        // 只消耗一次
        assertThat(weatherService.findRecovery(original.getId()).recovery().getRecoveredMinutes())
                .isEqualTo(60);
        assertThat(bookingService.schedule("T1")).hasSize(1);

        assertThatThrownBy(() -> weatherService.recover(original.getId(), "rc-idem", "T1", "CAM",
                at(1, 16, 0), at(1, 17, 0)))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void recoverRejectsNonBlockedReservation() {
        var normal = book("k1", "P1", "T1", "CAM", at(1, 10, 0), at(1, 11, 0));
        assertThatThrownBy(() -> weatherService.recover(normal.getId(), "x", "T1", "CAM",
                at(1, 14, 0), at(1, 15, 0)))
                .isInstanceOf(WeatherRecoveryNotAllowedException.class);
    }

    // ------------------------------------------------------------------
    // 机会观测被天气阻断后：保留优先级恢复
    // ------------------------------------------------------------------

    @Test
    void opportunityReservationBlockedAndRecoveredKeepsPriorityWithoutQuotaConsume() {
        // 机会预订 10:00-11:00（消费 60）
        var record = preemptionService.confirm("OPB-1", "OP1", "T1", "CAM",
                at(1, 10, 0), at(1, 11, 0));
        Long opReservationId = record.getOpportunityReservationId();
        assertThat(preemptionService.opportunityQuota("OP1").getRemainingQuotaMinutes()).isEqualTo(240);

        var outcome = weatherService.declare("W-OP", "T1", at(1, 9, 0), at(1, 12, 0));
        assertThat(outcome.blockedCount()).isEqualTo(1);
        // 阻断不退机会配额
        assertThat(preemptionService.opportunityQuota("OP1").getRemainingQuotaMinutes()).isEqualTo(240);

        var recovered = weatherService.recover(opReservationId, "oprc", "T1", "CAM",
                at(1, 14, 0), at(1, 15, 0));
        assertThat(recovered.newReservation().isOpportunityReservation()).isTrue();
        assertThat(recovered.newReservation().getPriority()).isEqualTo(8);
        // 恢复不再次扣机会配额
        assertThat(preemptionService.opportunityQuota("OP1").getRemainingQuotaMinutes()).isEqualTo(240);
    }

    // ------------------------------------------------------------------
    // 范围调整：扩大阻断、缩小恢复；不重复释放
    // ------------------------------------------------------------------

    @Test
    void adjustShrinkRestoresUnconsumedReservationAndEnlargesBlocksAgain() {
        var r = book("k1", "P1", "T1", "CAM", at(1, 10, 0), at(1, 11, 0));
        weatherService.declare("W-ADJ", "T1", at(1, 9, 0), at(1, 12, 0));
        assertThat(bookingService.findReservation(r.getId()).getStatus())
                .isEqualTo(ReservationStatus.WEATHER_BLOCKED);

        // 缩小到不再覆盖 10-11
        var shrunk = weatherService.adjust("W-ADJ", at(1, 12, 0), at(1, 13, 0));
        assertThat(shrunk.created()).isTrue();
        assertThat(shrunk.restoredCount()).isEqualTo(1);
        assertThat(shrunk.blockedCount()).isZero();
        assertThat(bookingService.findReservation(r.getId()).getStatus()).isEqualTo(ReservationStatus.ACTIVE);
        var recView = weatherService.findRecovery(r.getId());
        assertThat(recView.recovery().getStatus()).isEqualTo(WeatherRecoveryStatus.RESTORED);
        // 恢复回日程，无配额变化（本就冻结/不占配额语义）
        assertThat(bookingService.schedule("T1")).extracting(Reservation::getId)
                .containsExactly(r.getId());

        // 再次扩大覆盖：产生新一代资格
        var enlarged = weatherService.adjust("W-ADJ", at(1, 9, 0), at(1, 12, 0));
        assertThat(enlarged.blockedCount()).isEqualTo(1);
        assertThat(bookingService.findReservation(r.getId()).getStatus())
                .isEqualTo(ReservationStatus.WEATHER_BLOCKED);
        var latest = weatherService.findRecovery(r.getId());
        assertThat(latest.recovery().getStatus()).isEqualTo(WeatherRecoveryStatus.BLOCKED);
        assertThat(latest.recovery().getRemainingRecoverableMinutes()).isEqualTo(60);
    }

    @Test
    void adjustSameWindowIsIdempotent() {
        book("k1", "P1", "T1", "CAM", at(1, 10, 0), at(1, 11, 0));
        weatherService.declare("W-SAME", "T1", at(1, 9, 0), at(1, 12, 0));
        var replay = weatherService.adjust("W-SAME", at(1, 9, 0), at(1, 12, 0));
        assertThat(replay.created()).isFalse();
        assertThat(replay.blockedCount()).isZero();
        assertThat(replay.restoredCount()).isZero();
    }

    @Test
    void shrinkDoesNotRestorePartiallyRecoveredReservation() {
        var r = book("k1", "P1", "T1", "CAM", at(1, 10, 0), at(1, 12, 0)); // 120
        weatherService.declare("W-PR", "T1", at(1, 9, 0), at(1, 12, 30));
        weatherService.recover(r.getId(), "pr1", "T1", "CAM", at(1, 14, 0), at(1, 14, 30)); // 消耗 30

        // 缩小窗口使其落出：因已部分消耗，不恢复回日程
        var shrunk = weatherService.adjust("W-PR", at(1, 13, 0), at(1, 13, 30));
        assertThat(shrunk.restoredCount()).isZero();
        assertThat(bookingService.findReservation(r.getId()).getStatus())
                .isEqualTo(ReservationStatus.WEATHER_BLOCKED);
        // 仍可继续消耗剩余 90
        var again = weatherService.recover(r.getId(), "pr2", "T1", "CAM",
                at(1, 16, 0), at(1, 17, 30));
        assertThat(again.recovery().getStatus()).isEqualTo(WeatherRecoveryStatus.RECOVERED);
    }

    @Test
    void shrinkKeepsBlockedWhenOriginalSlotOccupied() {
        var r = book("k1", "P1", "T1", "CAM", at(1, 10, 0), at(1, 11, 0));
        weatherService.declare("W-OCC", "T1", at(1, 9, 0), at(1, 12, 0));

        // 另一提案在 r 的原时段放置预订（r 已不占日程，可以订入）
        var occupier = book("occ", "P2", "T1", "CAM", at(1, 10, 0), at(1, 11, 0));

        var shrunk = weatherService.adjust("W-OCC", at(1, 12, 0), at(1, 13, 0));
        assertThat(shrunk.restoredCount()).isZero();
        // r 保持阻断，仍有资格可恢复到别处
        assertThat(bookingService.findReservation(r.getId()).getStatus())
                .isEqualTo(ReservationStatus.WEATHER_BLOCKED);
        assertThat(weatherService.findRecovery(r.getId()).recovery()
                .getRemainingRecoverableMinutes()).isEqualTo(60);
        assertThat(bookingService.schedule("T1")).extracting(Reservation::getId)
                .containsExactly(occupier.getId());
    }

    // ------------------------------------------------------------------
    // 取消：被阻断预订与恢复新预订
    // ------------------------------------------------------------------

    @Test
    void cancelBlockedReservationRefundsOnlyRemainingOnce() {
        var r = book("k1", "P1", "T1", "CAM", at(1, 10, 0), at(1, 12, 0)); // 120
        weatherService.declare("W-CB", "T1", at(1, 9, 0), at(1, 12, 30));
        weatherService.recover(r.getId(), "cb1", "T1", "CAM", at(1, 14, 0), at(1, 14, 30)); // 消耗 30
        assertThat(bookingService.quota("P1").getRemainingQuotaMinutes()).isEqualTo(480);

        // 放弃阻断预订：剩余 90 一次性退还配额
        bookingService.cancel(r.getId());
        assertThat(bookingService.quota("P1").getRemainingQuotaMinutes()).isEqualTo(570);
        assertThat(weatherService.findRecovery(r.getId()).recovery().getStatus())
                .isEqualTo(WeatherRecoveryStatus.FORFEITED);

        // 重复取消幂等，不重复退还
        bookingService.cancel(r.getId());
        assertThat(bookingService.quota("P1").getRemainingQuotaMinutes()).isEqualTo(570);
    }

    @Test
    void cancelRecoveredReservationReturnsMinutesToRecoveryAndReopens() {
        var original = book("k1", "P1", "T1", "CAM", at(1, 10, 0), at(1, 11, 0));
        weatherService.declare("W-CR", "T1", at(1, 9, 0), at(1, 12, 0));
        var rebooked = weatherService.recover(original.getId(), "cr1", "T1", "CAM",
                at(1, 14, 0), at(1, 15, 0));
        assertThat(bookingService.findReservation(original.getId()).getStatus())
                .isEqualTo(ReservationStatus.RECOVERED);
        assertThat(bookingService.quota("P1").getRemainingQuotaMinutes()).isEqualTo(540);

        // 取消恢复后的新预订：分钟退回资格（配额不变），原预订重开为阻断
        bookingService.cancel(rebooked.newReservation().getId());
        assertThat(bookingService.quota("P1").getRemainingQuotaMinutes()).isEqualTo(540);
        assertThat(bookingService.findReservation(original.getId()).getStatus())
                .isEqualTo(ReservationStatus.WEATHER_BLOCKED);
        var view = weatherService.findRecovery(original.getId());
        assertThat(view.recovery().getStatus()).isEqualTo(WeatherRecoveryStatus.BLOCKED);
        assertThat(view.recovery().getRemainingRecoverableMinutes()).isEqualTo(60);
        assertThat(bookingService.schedule("T1")).isEmpty();

        // 可再次恢复
        var again = weatherService.recover(original.getId(), "cr2", "T1", "CAM",
                at(1, 16, 0), at(1, 17, 0));
        assertThat(again.created()).isTrue();
        assertThat(bookingService.findReservation(original.getId()).getStatus())
                .isEqualTo(ReservationStatus.RECOVERED);
    }

    // ------------------------------------------------------------------
    // 恢复后的新预订被抢占：分钟退回资格、原预订重开
    // ------------------------------------------------------------------

    @Test
    void preemptingRecoveredReservationReturnsMinutesToRecoveryNotQuota() {
        var original = book("k1", "P1", "T1", "CAM", at(1, 10, 0), at(1, 11, 0));
        weatherService.declare("W-PE", "T1", at(1, 9, 0), at(1, 12, 0));
        var rebooked = weatherService.recover(original.getId(), "pe1", "T1", "CAM",
                at(1, 14, 0), at(1, 15, 0)).newReservation();
        assertThat(bookingService.findReservation(original.getId()).getStatus())
                .isEqualTo(ReservationStatus.RECOVERED);

        // 机会提案抢占恢复后的新预订（OP1 优先级 8）
        var record = preemptionService.confirm("PE-1", "OP1", "T1", "CAM",
                at(1, 14, 0), at(1, 15, 0));
        assertThat(record.getStatus().name()).isEqualTo("CONFIRMED");

        // 新预订被取消；普通配额未被重复退还（仍 540，机会消费其自有 60）
        assertThat(bookingService.findReservation(rebooked.getId()).getStatus())
                .isEqualTo(ReservationStatus.CANCELLED);
        assertThat(bookingService.quota("P1").getRemainingQuotaMinutes()).isEqualTo(540);
        assertThat(preemptionService.opportunityQuota("OP1").getRemainingQuotaMinutes()).isEqualTo(240);

        // 60 分钟退回恢复资格；原预订重开为天气阻断，可再次恢复
        var view = weatherService.findRecovery(original.getId());
        assertThat(view.recovery().getStatus()).isEqualTo(WeatherRecoveryStatus.BLOCKED);
        assertThat(view.recovery().getRemainingRecoverableMinutes()).isEqualTo(60);
        assertThat(bookingService.findReservation(original.getId()).getStatus())
                .isEqualTo(ReservationStatus.WEATHER_BLOCKED);

        var again = weatherService.recover(original.getId(), "pe2", "T1", "CAM",
                at(1, 18, 0), at(1, 19, 0));
        assertThat(again.created()).isTrue();
        assertThat(bookingService.findReservation(original.getId()).getStatus())
                .isEqualTo(ReservationStatus.RECOVERED);
    }

    // ------------------------------------------------------------------
    // 查询：关联原预订、天气事件、新预订、剩余分钟
    // ------------------------------------------------------------------
    @Test
    void queriesLinkOriginalEventNewReservationsAndRemainingMinutes() {
        var original = book("k1", "P1", "T1", "CAM", at(1, 10, 0), at(1, 12, 0));
        weatherService.declare("W-Q", "T1", at(1, 9, 0), at(1, 12, 30));
        weatherService.recover(original.getId(), "q1", "T1", "CAM", at(1, 14, 0), at(1, 14, 30));

        // 单条恢复详情
        var view = weatherService.findRecovery(original.getId());
        assertThat(view.recovery().getWeatherEvent().getBusinessKey()).isEqualTo("W-Q");
        assertThat(view.recovery().getOriginalReservation().getId()).isEqualTo(original.getId());
        assertThat(view.recoveredReservations()).hasSize(1);
        assertThat(view.recovery().getRemainingRecoverableMinutes()).isEqualTo(90);

        // 事件下全部资格
        var byEvent = weatherService.recoveriesByEvent("W-Q");
        assertThat(byEvent).hasSize(1);

        // 仍可恢复列表包含该部分恢复资格
        var recoverable = weatherService.recoverableReservations();
        assertThat(recoverable).extracting(v -> v.recovery().getOriginalReservation().getId())
                .containsExactly(original.getId());

        // 事件审计：操作历史 DECLARED，受影响快照含 BLOCKED
        var event = weatherService.findEvent("W-Q");
        assertThat(event.getOperations()).hasSize(1);
        assertThat(event.getAffectedReservations()).hasSize(1);
        assertThat(event.getAffectedReservations().get(0).getAction()).isEqualTo("BLOCKED");
    }

    // ------------------------------------------------------------------
    // 并发：范围调整 / 取消 / 恢复 不重复释放或消耗
    // ------------------------------------------------------------------

    @Test
    void concurrentRecoveriesWithSameKeyConsumeOnce() throws Exception {
        var original = book("k1", "P1", "T1", "CAM", at(1, 10, 0), at(1, 11, 0));
        weatherService.declare("W-CC1", "T1", at(1, 9, 0), at(1, 12, 0));

        int threads = 8;
        int errors = runConcurrently(threads, () -> weatherService.recover(
                original.getId(), "same-rc", "T1", "CAM", at(1, 14, 0), at(1, 15, 0)));

        assertThat(errors).isZero();
        assertThat(weatherService.findRecovery(original.getId()).recovery().getRecoveredMinutes())
                .isEqualTo(60);
        assertThat(bookingService.schedule("T1")).hasSize(1);
        assertThat(bookingService.quota("P1").getRemainingQuotaMinutes()).isEqualTo(540);
    }

    @Test
    void concurrentRecoveriesDifferentKeysConsumeAtMostAvailable() throws Exception {
        var original = book("k1", "P1", "T1", "CAM", at(1, 10, 0), at(1, 12, 0)); // 120
        weatherService.declare("W-CC2", "T1", at(1, 9, 0), at(1, 12, 30));

        int threads = 6;
        AtomicInteger created = new AtomicInteger();
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int index = i;
            // 各申请 30 分钟、互不重叠的不同新时段（14:00 起每小时间隔，留足切换）
            Instant s = at(1, 14 + index, 0);
            Instant e = at(1, 14 + index, 30);
            tasks.add(() -> {
                try {
                    var o = weatherService.recover(original.getId(), "diff-rc-" + index, "T1", "CAM", s, e);
                    if (o.created()) {
                        created.incrementAndGet();
                    }
                } catch (RuntimeException expected) {
                    // 资格耗尽
                }
                return null;
            });
        }
        execute(tasks);

        // 120 分钟，每次 30，恰好 4 次成功
        assertThat(created.get()).isEqualTo(4);
        assertThat(weatherService.findRecovery(original.getId()).recovery().getRecoveredMinutes())
                .isEqualTo(120);
        assertThat(weatherService.findRecovery(original.getId()).recovery().getStatus())
                .isEqualTo(WeatherRecoveryStatus.RECOVERED);
        assertThat(bookingService.quota("P1").getRemainingQuotaMinutes()).isEqualTo(480);
    }

    @Test
    void concurrentCancelAndRecoveryNeverDoubleRelease() throws Exception {
        int iterations = 8;
        for (int i = 0; i < iterations; i++) {
            final int idx = i;
            Instant start = at(2 + idx, 10, 0);
            Instant end = at(2 + idx, 11, 0);
            var r = bookingService.book("ncr-" + idx, "P1", "T1", "CAM", start, end).reservation();
            weatherService.declare("W-NCR-" + idx, "T1",
                    at(2 + idx, 9, 0), at(2 + idx, 12, 0));

            Instant ns = at(2 + idx, 14, 0);
            Instant ne = at(2 + idx, 15, 0);
            var latch = new CountDownLatch(1);
            var pool = Executors.newFixedThreadPool(2);
            try {
                var f1 = pool.submit(() -> {
                    latch.await();
                    bookingService.cancel(r.getId());
                    return null;
                });
                var f2 = pool.submit(() -> {
                    latch.await();
                    try {
                        weatherService.recover(r.getId(), "ncr-rc-" + idx, "T1", "CAM", ns, ne);
                    } catch (RuntimeException swallow) {
                        // 与取消竞争失败即可
                    }
                    return null;
                });
                latch.countDown();
                f1.get();
                f2.get();
            } finally {
                pool.shutdown();
            }

            // 守恒：P1 总额 600 = 剩余可消费配额 + 配额出资的有效普通预订分钟 + 全部仍冻结的恢复分钟。
            // 取消赢→60 退还配额；恢复赢→60 在资格出资的新预订中（不计配额），冻结与配额出资皆为 0。
            long quota = bookingService.quota("P1").getRemainingQuotaMinutes();
            long quotaFundedActive = activeNormalQuotaFundedMinutes();
            long recoveryFundedActive = activeRecoveryFundedMinutes();
            long frozen = totalFrozenRecoverableMinutes();
            assertThat(quota + quotaFundedActive + recoveryFundedActive + frozen)
                    .as("第 %d 轮守恒：配额 %d + 配额出资有效 %d + 恢复出资有效 %d + 冻结 %d",
                            idx, quota, quotaFundedActive, recoveryFundedActive, frozen)
                    .isEqualTo(600);
        }
    }

    @Test
    void concurrentAdjustAndRecoveryDoNotDoubleRelease() throws Exception {
        int iterations = 6;
        for (int i = 0; i < iterations; i++) {
            final int idx = i;
            Instant start = at(10 + idx, 10, 0);
            Instant end = at(10 + idx, 11, 0);
            var r = bookingService.book("adj-" + idx, "P1", "T1", "CAM", start, end).reservation();
            String eventKey = "W-ADJRACE-" + idx;
            weatherService.declare(eventKey, "T1", at(10 + idx, 9, 0), at(10 + idx, 12, 0));

            var latch = new CountDownLatch(1);
            var pool = Executors.newFixedThreadPool(2);
            try {
                // 缩小：尝试恢复回日程
                var f1 = pool.submit(() -> {
                    latch.await();
                    try {
                        weatherService.adjust(eventKey, at(10 + idx, 12, 0), at(10 + idx, 13, 0));
                    } catch (RuntimeException swallow) {
                        // 竞争
                    }
                    return null;
                });
                // 恢复排期：尝试消耗资格
                var f2 = pool.submit(() -> {
                    latch.await();
                    try {
                        weatherService.recover(r.getId(), "adj-rc-" + idx, "T1", "CAM",
                                at(10 + idx, 14, 0), at(10 + idx, 15, 0));
                    } catch (RuntimeException swallow) {
                        // 竞争
                    }
                    return null;
                });
                latch.countDown();
                f1.get();
                f2.get();
            } finally {
                pool.shutdown();
            }

            // 守恒：P1 总额 600 = 剩余可消费配额 + P1 配额出资的有效普通预订分钟 + 全部仍冻结的恢复分钟。
            // 缩小赢→原预订恢复为 ACTIVE（配额出资，占 60）；恢复赢→新预订由资格出资（冻结、配额出资皆 0）。
            long quota = bookingService.quota("P1").getRemainingQuotaMinutes();
            long quotaFundedActive = activeNormalQuotaFundedMinutes();
            long recoveryFundedActive = activeRecoveryFundedMinutes();
            long frozen = totalFrozenRecoverableMinutes();
            assertThat(quota + quotaFundedActive + recoveryFundedActive + frozen)
                    .as("第 %d 轮：%d + %d + %d + %d", idx, quota, quotaFundedActive,
                            recoveryFundedActive, frozen)
                    .isEqualTo(600);
        }
    }

    /** T1 日程上归属 P1、由普通配额出资（非机会、非天气恢复资格出资）的有效预订分钟。 */
    private long activeNormalQuotaFundedMinutes() {
        return bookingService.schedule("T1").stream()
                .filter(x -> !x.isOpportunityReservation())
                .filter(x -> x.getProposal() != null && "P1".equals(x.getProposal().getCode()))
                .filter(x -> x.getFundedByWeatherRecovery() == null)
                .mapToLong(Reservation::getDurationMinutes).sum();
    }

    /** T1 日程上归属 P1、由天气恢复资格出资的有效新预订分钟（恢复排期成功、未再取消/抢占）。 */
    private long activeRecoveryFundedMinutes() {
        return bookingService.schedule("T1").stream()
                .filter(x -> !x.isOpportunityReservation())
                .filter(x -> x.getProposal() != null && "P1".equals(x.getProposal().getCode()))
                .filter(x -> x.getFundedByWeatherRecovery() != null)
                .mapToLong(Reservation::getDurationMinutes).sum();
    }

    /** 全局仍处于 BLOCKED / PARTIALLY_RECOVERED 的恢复资格剩余分钟总和。 */
    private long totalFrozenRecoverableMinutes() {
        return weatherService.recoverableReservations().stream()
                .mapToLong(v -> v.recovery().getRemainingRecoverableMinutes()).sum();
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
