package com.chris64233.cc.telescope;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import com.chris64233.cc.telescope.repo.InstrumentRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class BookingIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final String BASE = "2030-01-10T";

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.execute("DELETE FROM observation_booking");
        jdbcTemplate.execute("DELETE FROM telescope_instrument");
        jdbcTemplate.execute("DELETE FROM proposal_instrument");
        jdbcTemplate.execute("DELETE FROM telescope");
        jdbcTemplate.execute("DELETE FROM proposal");
        jdbcTemplate.execute("DELETE FROM instrument");
    }

    @BeforeEach
    void seedInstruments() throws Exception {
        registerInstrument("CAM");
        registerInstrument("SPEC");
    }

    @Test
    void bookingSucceedsAndDeductsQuota() throws Exception {
        registerTelescope("T1", 30, "CAM", "SPEC");
        registerProposal("P-1", 120, "CAM", "SPEC");

        book("P-1", "T1", "CAM", at(10, 0), at(11, 0), "key-1")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.durationMinutes").value(60))
                .andExpect(jsonPath("$.status").value("BOOKED"));

        assertQuota("P-1", 120, 60, 60);
    }

    @Test
    void sameInstrumentCanBeBackToBack() throws Exception {
        registerTelescope("T1", 30, "CAM");
        registerProposal("P-1", 240, "CAM");

        book("P-1", "T1", "CAM", at(10, 0), at(11, 0), null)
                .andExpect(status().isCreated());
        // 左闭右开：11:00 结束，下一个 11:00 开始，同仪器可首尾相接。
        book("P-1", "T1", "CAM", at(11, 0), at(12, 0), null)
                .andExpect(status().isCreated());
    }

    @Test
    void differentInstrumentNeedsSwitchGapOnBothSides() throws Exception {
        registerTelescope("T1", 30, "CAM", "SPEC");
        registerProposal("P-1", 480, "CAM", "SPEC");

        book("P-1", "T1", "CAM", at(10, 0), at(11, 0), null)
                .andExpect(status().isCreated());

        // 紧贴前序预订、仪器不同 -> 冲突。
        book("P-1", "T1", "SPEC", at(11, 0), at(11, 20), null)
                .andExpect(status().isConflict());
        // 间隔不足 30 分钟 -> 冲突。
        book("P-1", "T1", "SPEC", at(11, 20), at(11, 40), null)
                .andExpect(status().isConflict());
        // 恰好留出 30 分钟 -> 允许。
        book("P-1", "T1", "SPEC", at(11, 30), at(12, 0), null)
                .andExpect(status().isCreated());

        // 检查后继相邻记录：新预订与后面的 SPEC 间隔不足 -> 冲突。
        book("P-1", "T1", "CAM", at(12, 10), at(12, 20), null)
                .andExpect(status().isConflict());
        // 紧贴后继预订 -> 冲突。
        book("P-1", "T1", "CAM", at(12, 0), at(12, 10), null)
                .andExpect(status().isConflict());
        // 恰好留足间隔 -> 允许。
        book("P-1", "T1", "CAM", at(12, 30), at(13, 0), null)
                .andExpect(status().isCreated());
    }

    @Test
    void zeroSwitchOverAllowsTouchingDifferentInstruments() throws Exception {
        registerTelescope("T0", 0, "CAM", "SPEC");
        registerProposal("P-1", 240, "CAM", "SPEC");

        book("P-1", "T0", "CAM", at(10, 0), at(11, 0), null)
                .andExpect(status().isCreated());
        book("P-1", "T0", "SPEC", at(11, 0), at(12, 0), null)
                .andExpect(status().isCreated());
    }

    @Test
    void bookingInsertedBetweenTwoExistingChecksBothNeighbours() throws Exception {
        registerTelescope("T1", 30, "CAM", "SPEC");
        registerProposal("P-1", 600, "CAM", "SPEC");

        // 两侧已有 CAM 预订，中间留出足够空隙插入 SPEC。
        book("P-1", "T1", "CAM", at(10, 0), at(11, 0), null)
                .andExpect(status().isCreated());
        book("P-1", "T1", "CAM", at(13, 0), at(14, 0), null)
                .andExpect(status().isCreated());

        // 与前驱仅隔 20 分钟 -> 冲突。
        book("P-1", "T1", "SPEC", at(11, 20), at(12, 0), null)
                .andExpect(status().isConflict());
        // 与后继仅隔 20 分钟 -> 冲突。
        book("P-1", "T1", "SPEC", at(12, 10), at(12, 40), null)
                .andExpect(status().isConflict());
        // 两侧均留足 30 分钟切换间隔 -> 允许。
        book("P-1", "T1", "SPEC", at(11, 30), at(12, 30), null)
                .andExpect(status().isCreated());
    }

    @Test
    void overlappingBookingsAreRejected() throws Exception {
        registerTelescope("T1", 30, "CAM");
        registerProposal("P-1", 240, "CAM");

        book("P-1", "T1", "CAM", at(10, 0), at(11, 0), null)
                .andExpect(status().isCreated());
        book("P-1", "T1", "CAM", at(10, 30), at(11, 30), null)
                .andExpect(status().isConflict());
        book("P-1", "T1", "CAM", at(9, 30), at(10, 30), null)
                .andExpect(status().isConflict());
    }

    @Test
    void instrumentMustBeSupportedByTelescopeAndAllowedByProposal() throws Exception {
        registerTelescope("T1", 30, "CAM");
        registerProposal("P-1", 240, "CAM", "SPEC");

        // 望远镜不支持该仪器。
        book("P-1", "T1", "SPEC", at(10, 0), at(10, 30), null)
                .andExpect(status().isConflict());

        registerTelescope("T2", 30, "CAM", "SPEC");
        registerProposal("P-2", 240, "CAM");
        // 提案不允许该仪器。
        book("P-2", "T2", "SPEC", at(10, 0), at(10, 30), null)
                .andExpect(status().isConflict());
    }

    @Test
    void quotaCannotBeOversold() throws Exception {
        registerTelescope("T1", 30, "CAM");
        registerProposal("P-1", 30, "CAM");

        book("P-1", "T1", "CAM", at(10, 0), at(10, 20), null)
                .andExpect(status().isCreated());
        book("P-1", "T1", "CAM", at(11, 0), at(11, 20), null)
                .andExpect(status().isConflict());
        assertQuota("P-1", 30, 20, 10);
    }

    @Test
    void idempotentReplayReturnsOriginalBooking() throws Exception {
        registerTelescope("T1", 30, "CAM");
        registerProposal("P-1", 240, "CAM");

        MvcResult first = book("P-1", "T1", "CAM", at(10, 0), at(10, 30), "idem-1")
                .andExpect(status().isCreated())
                .andReturn();
        long firstId = readId(first);

        MvcResult replay = book("P-1", "T1", "CAM", at(10, 0), at(10, 30), "idem-1")
                .andExpect(status().isCreated())
                .andReturn();
        assertThat(readId(replay)).isEqualTo(firstId);

        // 同键不同内容 -> 冲突。
        book("P-1", "T1", "CAM", at(12, 0), at(12, 30), "idem-1")
                .andExpect(status().isConflict());

        // 重放只扣减一次配额。
        assertQuota("P-1", 240, 30, 210);
    }

    @Test
    void cancelRefundsQuotaOnceAndStartedBookingCannotCancel() throws Exception {
        registerTelescope("T1", 30, "CAM");
        registerProposal("P-1", 120, "CAM");

        MvcResult result = book("P-1", "T1", "CAM", at(10, 0), at(11, 0), "cancel-1")
                .andExpect(status().isCreated())
                .andReturn();
        long bookingId = readId(result);

        cancel(bookingId).andExpect(status().isNoContent());
        assertQuota("P-1", 120, 0, 120);

        // 重复取消不会再次归还。
        cancel(bookingId).andExpect(status().isConflict());
        assertQuota("P-1", 120, 0, 120);

        // 已开始的预订不得取消。
        MvcResult started = book("P-1", "T1", "CAM",
                Instant.now().minus(1, ChronoUnit.HOURS),
                Instant.now().plus(1, ChronoUnit.HOURS), "cancel-2")
                .andExpect(status().isCreated())
                .andReturn();
        cancel(readId(started)).andExpect(status().isConflict());
        assertQuota("P-1", 120, 120, 0);
    }

    @Test
    void cancelledBookingFreesScheduleSlot() throws Exception {
        registerTelescope("T1", 30, "CAM");
        registerProposal("P-1", 240, "CAM");

        MvcResult result = book("P-1", "T1", "CAM", at(10, 0), at(11, 0), null)
                .andExpect(status().isCreated())
                .andReturn();
        cancel(readId(result)).andExpect(status().isNoContent());

        book("P-1", "T1", "CAM", at(10, 0), at(11, 0), null)
                .andExpect(status().isCreated());
    }

    @Test
    void scheduleIsStableSortedByStartAtAscending() throws Exception {
        registerTelescope("T1", 0, "CAM");
        registerProposal("P-1", 480, "CAM");

        book("P-1", "T1", "CAM", at(12, 0), at(13, 0), null).andExpect(status().isCreated());
        book("P-1", "T1", "CAM", at(10, 0), at(11, 0), null).andExpect(status().isCreated());
        book("P-1", "T1", "CAM", at(11, 0), at(12, 0), null).andExpect(status().isCreated());

        mockMvc.perform(get("/api/telescopes/T1/schedule"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookings[0].startAt").value(BASE + "10:00:00Z"))
                .andExpect(jsonPath("$.bookings[1].startAt").value(BASE + "11:00:00Z"))
                .andExpect(jsonPath("$.bookings[2].startAt").value(BASE + "12:00:00Z"));
    }

    @Test
    void invalidWindowRejected() throws Exception {
        registerTelescope("T1", 30, "CAM");
        registerProposal("P-1", 240, "CAM");

        // 非整数分钟。
        bookRaw("P-1", "T1", "CAM", at(10, 0).toString(),
                at(10, 0).plusSeconds(45).toString(), null)
                .andExpect(status().isConflict());
        // 结束不晚于开始。
        book("P-1", "T1", "CAM", at(11, 0), at(10, 0), null)
                .andExpect(status().isConflict());
    }

    @Test
    void concurrentBookingsDoNotOversellQuota() throws Exception {
        registerTelescope("T1", 0, "CAM");
        registerTelescope("T2", 0, "CAM");
        registerProposal("P-C", 30, "CAM");

        List<Integer> statuses = runConcurrent(
                bookingCall("P-C", "T1", "CAM", at(10, 0), at(10, 20), "c-1"),
                bookingCall("P-C", "T2", "CAM", at(10, 0), at(10, 20), "c-2"));

        long created = statuses.stream().filter(s -> s == 201).count();
        long conflicts = statuses.stream().filter(s -> s == 409).count();
        assertThat(created).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);
        assertQuota("P-C", 30, 20, 10);
    }

    @Test
    void concurrentBookingsDoNotCreateScheduleConflict() throws Exception {
        registerTelescope("T1", 30, "CAM");
        registerProposal("P-1", 240, "CAM");
        registerProposal("P-2", 240, "CAM");

        List<Integer> statuses = runConcurrent(
                bookingCall("P-1", "T1", "CAM", at(10, 0), at(11, 0), "s-1"),
                bookingCall("P-2", "T1", "CAM", at(10, 30), at(11, 30), "s-2"));

        long created = statuses.stream().filter(s -> s == 201).count();
        long conflicts = statuses.stream().filter(s -> s == 409).count();
        assertThat(created).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);
    }

    // ---- 辅助方法 ----

    private Instant at(int hour, int minute) {
        return Instant.parse(String.format("%s%02d:%02d:00Z", BASE, hour, minute));
    }

    private void registerInstrument(String name) throws Exception {
        mockMvc.perform(post("/api/instruments")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("name", name))))
                .andExpect(status().isCreated());
    }

    private void registerTelescope(String code, int switchOverMinutes, String... instruments)
            throws Exception {
        mockMvc.perform(post("/api/telescopes")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", code,
                                "name", "望远镜-" + code,
                                "switchOverMinutes", switchOverMinutes,
                                "instruments", List.of(instruments)))))
                .andExpect(status().isCreated());
    }

    private void registerProposal(String proposalNo, int totalMinutes, String... instruments)
            throws Exception {
        mockMvc.perform(post("/api/proposals")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "proposalNo", proposalNo,
                                "totalMinutes", totalMinutes,
                                "instruments", List.of(instruments)))))
                .andExpect(status().isCreated());
    }

    private org.springframework.test.web.servlet.ResultActions book(
            String proposalNo, String telescopeCode, String instrument,
            Instant startAt, Instant endAt, String idempotencyKey) throws Exception {
        return bookRaw(proposalNo, telescopeCode, instrument,
                startAt.toString(), endAt.toString(), idempotencyKey);
    }

    private org.springframework.test.web.servlet.ResultActions bookRaw(
            String proposalNo, String telescopeCode, String instrument,
            String startAt, String endAt, String idempotencyKey) throws Exception {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("proposalNo", proposalNo);
        body.put("telescopeCode", telescopeCode);
        body.put("instrument", instrument);
        body.put("startAt", startAt);
        body.put("endAt", endAt);
        if (idempotencyKey != null) {
            body.put("idempotencyKey", idempotencyKey);
        }
        var request = post("/api/bookings")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(body));
        return mockMvc.perform(request);
    }

    private org.springframework.test.web.servlet.ResultActions cancel(long id) throws Exception {
        return mockMvc.perform(post("/api/bookings/" + id + "/cancel"));
    }

    private void assertQuota(String proposalNo, int total, int used, int remaining) throws Exception {
        mockMvc.perform(get("/api/proposals/" + proposalNo + "/quota"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalMinutes").value(total))
                .andExpect(jsonPath("$.usedMinutes").value(used))
                .andExpect(jsonPath("$.remainingMinutes").value(remaining));
    }

    private long readId(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private Callable<Integer> bookingCall(
            String proposalNo, String telescopeCode, String instrument,
            Instant startAt, Instant endAt, String idempotencyKey) {
        return () -> {
            try {
                return book(proposalNo, telescopeCode, instrument, startAt, endAt, idempotencyKey)
                        .andReturn()
                        .getResponse()
                        .getStatus();
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                throw e;
            }
        };
    }

    @SafeVarargs
    private List<Integer> runConcurrent(Callable<Integer>... calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.length);
        CountDownLatch ready = new CountDownLatch(calls.length);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Integer>> futures = new java.util.ArrayList<>();
            for (Callable<Integer> call : calls) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return call.call();
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Integer> statuses = new java.util.ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(15, TimeUnit.SECONDS));
            }
            return statuses;
        } finally {
            pool.shutdown();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}
