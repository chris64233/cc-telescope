package com.chris64233.cc.telescope;

import com.chris64233.cc.telescope.domain.Reservation;
import com.chris64233.cc.telescope.domain.ReservationStatus;
import com.chris64233.cc.telescope.repository.ProposalRepository;
import com.chris64233.cc.telescope.repository.ReservationRepository;
import com.chris64233.cc.telescope.repository.TelescopeRepository;
import com.chris64233.cc.telescope.service.BookingService;
import com.chris64233.cc.telescope.service.BusinessRuleException;
import com.chris64233.cc.telescope.service.CancellationNotAllowedException;
import com.chris64233.cc.telescope.service.IdempotencyConflictException;
import com.chris64233.cc.telescope.service.ScheduleConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class BookingServiceTest {

    private static final Instant BASE = Instant.parse("2030-01-01T00:00:00Z");

    @Autowired
    private BookingService bookingService;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private ProposalRepository proposalRepository;

    @Autowired
    private TelescopeRepository telescopeRepository;

    @BeforeEach
    void setUp() {
        reservationRepository.deleteAll();
        proposalRepository.deleteAll();
        telescopeRepository.deleteAll();
        bookingService.registerTelescope("T1", "山顶望远镜", 30, Set.of("CAM", "SPEC"));
        bookingService.registerProposal("P1", Set.of("CAM", "SPEC"), 600);
    }

    private static Instant at(int hour, int minute) {
        return BASE.plusSeconds((hour * 60L + minute) * 60L);
    }

    @Test
    void bookDeductsProposalQuota() {
        var outcome = bookingService.book("k1", "P1", "T1", "CAM", at(10, 0), at(11, 0));

        assertThat(outcome.created()).isTrue();
        assertThat(outcome.reservation().getDurationMinutes()).isEqualTo(60);
        assertThat(bookingService.quota("P1").getRemainingQuotaMinutes()).isEqualTo(540);
    }

    @Test
    void replayWithSameContentReturnsOriginalReservation() {
        var first = bookingService.book("k1", "P1", "T1", "CAM", at(10, 0), at(11, 0));
        var replay = bookingService.book("k1", "P1", "T1", "CAM", at(10, 0), at(11, 0));

        assertThat(replay.created()).isFalse();
        assertThat(replay.reservation().getId()).isEqualTo(first.reservation().getId());
        assertThat(bookingService.quota("P1").getRemainingQuotaMinutes()).isEqualTo(540);
    }

    @Test
    void replayWithDifferentContentConflicts() {
        bookingService.book("k1", "P1", "T1", "CAM", at(10, 0), at(11, 0));

        assertThatThrownBy(() -> bookingService.book("k1", "P1", "T1", "CAM", at(12, 0), at(13, 0)))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void rejectsInstrumentNotSupportedByTelescope() {
        assertThatThrownBy(() -> bookingService.book("k1", "P1", "T1", "RADIO", at(10, 0), at(11, 0)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("不支持仪器");
    }

    @Test
    void rejectsInstrumentNotAllowedByProposal() {
        bookingService.registerProposal("P2", Set.of("SPEC"), 60);

        assertThatThrownBy(() -> bookingService.book("k1", "P2", "T1", "CAM", at(10, 0), at(11, 0)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("不允许使用仪器");
    }

    @Test
    void rejectsBookingBeyondRemainingQuota() {
        assertThatThrownBy(() -> bookingService.book("k1", "P1", "T1", "CAM", at(10, 0), at(21, 0)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("配额不足");
    }

    @Test
    void rejectsInvalidTimeRange() {
        assertThatThrownBy(() -> bookingService.book("k1", "P1", "T1", "CAM", at(11, 0), at(10, 0)))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> bookingService.book("k2", "P1", "T1", "CAM", at(10, 0), at(10, 0)))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void rejectsOverlappingReservations() {
        bookingService.book("k1", "P1", "T1", "CAM", at(10, 0), at(11, 0));

        assertThatThrownBy(() -> bookingService.book("k2", "P1", "T1", "CAM", at(10, 30), at(11, 30)))
                .isInstanceOf(ScheduleConflictException.class);
        assertThatThrownBy(() -> bookingService.book("k3", "P1", "T1", "CAM", at(9, 30), at(10, 30)))
                .isInstanceOf(ScheduleConflictException.class);
        assertThatThrownBy(() -> bookingService.book("k4", "P1", "T1", "CAM", at(9, 0), at(12, 0)))
                .isInstanceOf(ScheduleConflictException.class);
    }

    @Test
    void sameInstrumentAllowsBackToBack() {
        bookingService.book("k1", "P1", "T1", "CAM", at(10, 0), at(11, 0));
        var outcome = bookingService.book("k2", "P1", "T1", "CAM", at(11, 0), at(12, 0));

        assertThat(outcome.created()).isTrue();
    }

    @Test
    void differentInstrumentRequiresSwitchGap() {
        bookingService.book("k1", "P1", "T1", "CAM", at(10, 0), at(11, 0));

        assertThatThrownBy(() -> bookingService.book("k2", "P1", "T1", "SPEC", at(11, 0), at(12, 0)))
                .isInstanceOf(ScheduleConflictException.class)
                .hasMessageContaining("切换时长");
        assertThatThrownBy(() -> bookingService.book("k3", "P1", "T1", "SPEC", at(11, 29), at(12, 0)))
                .isInstanceOf(ScheduleConflictException.class);

        var outcome = bookingService.book("k4", "P1", "T1", "SPEC", at(11, 30), at(12, 0));
        assertThat(outcome.created()).isTrue();
    }

    @Test
    void checksBothNeighborsWhenInsertingBetweenReservations() {
        bookingService.book("k1", "P1", "T1", "CAM", at(10, 0), at(11, 0));
        bookingService.book("k2", "P1", "T1", "SPEC", at(12, 0), at(13, 0));

        // 与前一条不同仪器，切换间隔不足
        assertThatThrownBy(() -> bookingService.book("k3", "P1", "T1", "SPEC", at(11, 0), at(11, 30)))
                .isInstanceOf(ScheduleConflictException.class);
        // 与后一条不同仪器，切换间隔不足
        assertThatThrownBy(() -> bookingService.book("k4", "P1", "T1", "CAM", at(11, 30), at(12, 0)))
                .isInstanceOf(ScheduleConflictException.class);
        // 前后均满足切换间隔
        var outcome = bookingService.book("k5", "P1", "T1", "SPEC", at(11, 30), at(11, 45));
        assertThat(outcome.created()).isTrue();
    }

    @Test
    void cancelledReservationFreesTimeSlot() {
        var first = bookingService.book("k1", "P1", "T1", "CAM", at(10, 0), at(11, 0)).reservation();
        bookingService.cancel(first.getId());

        var rebooked = bookingService.book("k2", "P1", "T1", "SPEC", at(10, 0), at(11, 0));
        assertThat(rebooked.created()).isTrue();
    }

    @Test
    void cancelRefundsQuotaExactlyOnce() {
        var reservation = bookingService.book("k1", "P1", "T1", "CAM", at(10, 0), at(11, 0)).reservation();
        assertThat(bookingService.quota("P1").getRemainingQuotaMinutes()).isEqualTo(540);

        var cancelled = bookingService.cancel(reservation.getId());
        assertThat(cancelled.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(bookingService.quota("P1").getRemainingQuotaMinutes()).isEqualTo(600);

        var again = bookingService.cancel(reservation.getId());
        assertThat(again.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(bookingService.quota("P1").getRemainingQuotaMinutes()).isEqualTo(600);
    }

    @Test
    void startedReservationCannotBeCancelled() {
        var reservation = bookingService.book("k1", "P1", "T1", "CAM",
                Instant.parse("2020-01-01T10:00:00Z"), Instant.parse("2020-01-01T11:00:00Z")).reservation();

        assertThatThrownBy(() -> bookingService.cancel(reservation.getId()))
                .isInstanceOf(CancellationNotAllowedException.class);
        assertThat(bookingService.quota("P1").getRemainingQuotaMinutes()).isEqualTo(540);
    }

    @Test
    void scheduleIsSortedByStartTimeAndExcludesCancelled() {
        bookingService.book("k1", "P1", "T1", "CAM", at(12, 0), at(12, 30));
        bookingService.book("k2", "P1", "T1", "CAM", at(9, 0), at(9, 30));
        var cancelled = bookingService.book("k3", "P1", "T1", "CAM", at(10, 0), at(10, 30)).reservation();
        bookingService.cancel(cancelled.getId());

        var schedule = bookingService.schedule("T1");

        assertThat(schedule).extracting(Reservation::getStartTime)
                .containsExactly(at(9, 0), at(12, 0));
    }

    @Test
    void concurrentBookingsOnSameSlotAllowOnlyOneWinner() throws Exception {
        int threads = 8;
        AtomicInteger successes = new AtomicInteger();
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int index = i;
            tasks.add(() -> {
                try {
                    bookingService.book("race-" + index, "P1", "T1", "CAM", at(10, 0), at(11, 0));
                    successes.incrementAndGet();
                } catch (ScheduleConflictException expected) {
                    // 其余线程应因时间冲突失败
                }
                return null;
            });
        }

        runConcurrently(tasks);

        assertThat(successes.get()).isEqualTo(1);
        assertThat(bookingService.schedule("T1")).hasSize(1);
        assertThat(bookingService.quota("P1").getRemainingQuotaMinutes()).isEqualTo(540);
    }

    @Test
    void concurrentBookingsNeverOversellQuota() throws Exception {
        bookingService.registerProposal("PQ", Set.of("CAM"), 120);
        int threads = 8;
        AtomicInteger successes = new AtomicInteger();
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int index = i;
            tasks.add(() -> {
                try {
                    // 每笔 30 分钟、互不重叠；配额 120 分钟最多成交 4 笔
                    bookingService.book("quota-" + index, "PQ", "T1", "CAM",
                            at(10 + index, 0), at(10 + index, 30));
                    successes.incrementAndGet();
                } catch (BusinessRuleException expected) {
                    // 配额耗尽后失败
                }
                return null;
            });
        }

        runConcurrently(tasks);

        assertThat(successes.get()).isEqualTo(4);
        assertThat(bookingService.quota("PQ").getRemainingQuotaMinutes()).isEqualTo(0);
        assertThat(bookingService.schedule("T1")).hasSize(4);
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
