package com.chris64233.cc.telescope;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class WeatherApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private com.chris64233.cc.telescope.repository.PreemptionRecordRepository preemptionRecordRepository;
    @Autowired
    private com.chris64233.cc.telescope.repository.WeatherRecoveryRepository weatherRecoveryRepository;
    @Autowired
    private com.chris64233.cc.telescope.repository.WeatherEventRepository weatherEventRepository;
    @Autowired
    private com.chris64233.cc.telescope.repository.ReservationRepository reservationRepository;
    @Autowired
    private com.chris64233.cc.telescope.repository.OpportunityProposalRepository opportunityRepository;
    @Autowired
    private com.chris64233.cc.telescope.repository.ProposalRepository proposalRepository;
    @Autowired
    private com.chris64233.cc.telescope.repository.TelescopeRepository telescopeRepository;

    @BeforeEach
    void cleanDatabase() {
        preemptionRecordRepository.deleteAll();
        reservationRepository.clearFundedWeatherRecoveryReferences();
        weatherRecoveryRepository.deleteAll();
        reservationRepository.deleteAll();
        weatherEventRepository.deleteAll();
        opportunityRepository.deleteAll();
        proposalRepository.deleteAll();
        telescopeRepository.deleteAll();
    }

    @Test
    void fullWeatherRecoveryLifecycleOverRest() throws Exception {
        registerTelescope();
        registerProposal();

        // 普通预订 10:00-12:00 CAM（120 分钟）
        var booked = mockMvc.perform(post("/api/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"idempotencyKey":"n1","proposalCode":"PA","telescopeCode":"TA",
                                 "instrument":"CAM","startTime":"2030-06-01T10:00:00Z",
                                 "endTime":"2030-06-01T12:00:00Z"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        long originalId = JsonPath.parse(booked.getResponse().getContentAsString()).read("$.id", Long.class);

        // 登记天气关闭 09:00-13:00
        mockMvc.perform(post("/api/weather")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"businessKey":"WX-1","telescopeCode":"TA",
                                 "startTime":"2030-06-01T09:00:00Z","endTime":"2030-06-01T13:00:00Z"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.windowStart").value("2030-06-01T09:00:00Z"))
                .andExpect(jsonPath("$.affectedReservations", hasSize(1)))
                .andExpect(jsonPath("$.affectedReservations[0].action").value("BLOCKED"))
                .andExpect(jsonPath("$.affectedReservations[0].durationMinutes").value(120))
                .andExpect(jsonPath("$.operations", hasSize(1)))
                .andExpect(jsonPath("$.operations[0].operation").value("DECLARED"));

        // 重放登记：200，不重复阻断
        mockMvc.perform(post("/api/weather")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"businessKey":"WX-1","telescopeCode":"TA",
                                 "startTime":"2030-06-01T09:00:00Z","endTime":"2030-06-01T13:00:00Z"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.affectedReservations", hasSize(1)));

        // 原预订变为天气阻断；日程为空；配额未退还（已扣 120）
        mockMvc.perform(get("/api/reservations/" + originalId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WEATHER_BLOCKED"))
                .andExpect(jsonPath("$.weatherEventBusinessKey").value("WX-1"));
        mockMvc.perform(get("/api/telescopes/TA/schedule"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(get("/api/proposals/PA/quota"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remainingQuotaMinutes").value(0));

        // 恢复详情：原预订、天气事件、剩余 120、尚无新预订
        mockMvc.perform(get("/api/reservations/" + originalId + "/recovery"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.weatherEventBusinessKey").value("WX-1"))
                .andExpect(jsonPath("$.originalReservation.id").value(originalId))
                .andExpect(jsonPath("$.blockedMinutes").value(120))
                .andExpect(jsonPath("$.recoveredMinutes").value(0))
                .andExpect(jsonPath("$.remainingRecoverableMinutes").value(120))
                .andExpect(jsonPath("$.status").value("BLOCKED"))
                .andExpect(jsonPath("$.recoveredReservations", hasSize(0)));

        // 部分恢复 30 分钟到 14:00-14:30
        mockMvc.perform(post("/api/reservations/" + originalId + "/recover")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"idempotencyKey":"RC-1","telescopeCode":"TA","instrument":"CAM",
                                 "startTime":"2030-06-01T14:00:00Z","endTime":"2030-06-01T14:30:00Z"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.ownerType").value("NORMAL"));

        // 配额仍为 0（恢复不扣配额）；原预订仍阻断；剩余 90
        mockMvc.perform(get("/api/proposals/PA/quota"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remainingQuotaMinutes").value(0));
        mockMvc.perform(get("/api/reservations/" + originalId))
                .andExpect(jsonPath("$.status").value("WEATHER_BLOCKED"));
        mockMvc.perform(get("/api/reservations/" + originalId + "/recovery"))
                .andExpect(jsonPath("$.status").value("PARTIALLY_RECOVERED"))
                .andExpect(jsonPath("$.remainingRecoverableMinutes").value(90))
                .andExpect(jsonPath("$.recoveredReservations", hasSize(1)));

        // 恢复请求幂等重放
        mockMvc.perform(post("/api/reservations/" + originalId + "/recover")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"idempotencyKey":"RC-1","telescopeCode":"TA","instrument":"CAM",
                                 "startTime":"2030-06-01T14:00:00Z","endTime":"2030-06-01T14:30:00Z"}
                                """))
                .andExpect(status().isOk());

        // 超额恢复（120 > 剩余 90）被拒绝，不留占用
        mockMvc.perform(post("/api/reservations/" + originalId + "/recover")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"idempotencyKey":"RC-BAD","telescopeCode":"TA","instrument":"CAM",
                                 "startTime":"2030-06-01T16:00:00Z","endTime":"2030-06-01T18:00:00Z"}
                                """))
                .andExpect(status().isUnprocessableEntity());

        // 恢复回仍关闭的窗口被拒绝（409）
        mockMvc.perform(post("/api/reservations/" + originalId + "/recover")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"idempotencyKey":"RC-WIN","telescopeCode":"TA","instrument":"CAM",
                                 "startTime":"2030-06-01T12:30:00Z","endTime":"2030-06-01T12:45:00Z"}
                                """))
                .andExpect(status().isConflict());

        // 用剩余 90 恢复完成 16:00-17:30
        mockMvc.perform(post("/api/reservations/" + originalId + "/recover")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"idempotencyKey":"RC-2","telescopeCode":"TA","instrument":"CAM",
                                 "startTime":"2030-06-01T16:00:00Z","endTime":"2030-06-01T17:30:00Z"}
                                """))
                .andExpect(status().isCreated());

        // 原预订 RECOVERED；恢复详情含两条新预订、剩余 0
        mockMvc.perform(get("/api/reservations/" + originalId))
                .andExpect(jsonPath("$.status").value("RECOVERED"))
                .andExpect(jsonPath("$.recoveredToId").isNumber());
        mockMvc.perform(get("/api/reservations/" + originalId + "/recovery"))
                .andExpect(jsonPath("$.status").value("RECOVERED"))
                .andExpect(jsonPath("$.remainingRecoverableMinutes").value(0))
                .andExpect(jsonPath("$.recoveredReservations", hasSize(2)));

        // 事件下恢复资格查询
        mockMvc.perform(get("/api/weather/WX-1/recoveries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].remainingRecoverableMinutes").value(0));
    }

    @Test
    void adjustShrinkRestoresReservationOverRest() throws Exception {
        registerTelescope();
        registerProposal();

        mockMvc.perform(post("/api/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"idempotencyKey":"n2","proposalCode":"PA","telescopeCode":"TA",
                                 "instrument":"CAM","startTime":"2030-06-01T10:00:00Z",
                                 "endTime":"2030-06-01T11:00:00Z"}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/weather")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"businessKey":"WX-2","telescopeCode":"TA",
                                 "startTime":"2030-06-01T09:00:00Z","endTime":"2030-06-01T12:00:00Z"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/telescopes/TA/schedule")).andExpect(jsonPath("$", hasSize(0)));

        // 缩小到 12:00-13:00，原 10-11 落出窗口，恢复回日程
        mockMvc.perform(post("/api/weather/WX-2/adjust")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"startTime":"2030-06-01T12:00:00Z","endTime":"2030-06-01T13:00:00Z"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.windowStart").value("2030-06-01T12:00:00Z"))
                .andExpect(jsonPath("$.operations", hasSize(2)))
                .andExpect(jsonPath("$.operations[1].restored").value(1));

        mockMvc.perform(get("/api/telescopes/TA/schedule"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"));
    }

    @Test
    void cancelBlockedReservationRefundsRemainingOverRest() throws Exception {
        registerTelescope();
        registerProposal();

        mockMvc.perform(post("/api/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"idempotencyKey":"n3","proposalCode":"PA","telescopeCode":"TA",
                                 "instrument":"CAM","startTime":"2030-06-01T10:00:00Z",
                                 "endTime":"2030-06-01T12:00:00Z"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/weather")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"businessKey":"WX-3","telescopeCode":"TA",
                                 "startTime":"2030-06-01T09:00:00Z","endTime":"2030-06-01T13:00:00Z"}
                                """))
                .andExpect(status().isCreated());
        // 先恢复 30 分钟
        mockMvc.perform(post("/api/reservations/1/recover")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"idempotencyKey":"RC-X","telescopeCode":"TA","instrument":"CAM",
                                 "startTime":"2030-06-01T14:00:00Z","endTime":"2030-06-01T14:30:00Z"}
                                """))
                .andExpect(status().isCreated());

        // 放弃阻断的原预订：剩余 90 退还配额（PA 总额 120，恢复不扣，故 0 + 90 = 90）
        mockMvc.perform(post("/api/reservations/1/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mockMvc.perform(get("/api/proposals/PA/quota"))
                .andExpect(jsonPath("$.remainingQuotaMinutes").value(90));
        mockMvc.perform(get("/api/reservations/1/recovery"))
                .andExpect(jsonPath("$.status").value("FORFEITED"));

        // 重复取消不重复退还
        mockMvc.perform(post("/api/reservations/1/cancel")).andExpect(status().isOk());
        mockMvc.perform(get("/api/proposals/PA/quota"))
                .andExpect(jsonPath("$.remainingQuotaMinutes").value(90));
    }

    private void registerTelescope() throws Exception {
        mockMvc.perform(post("/api/telescopes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"TA","name":"测试望远镜","switchMinutes":30,
                                 "instruments":["CAM","SPEC"]}
                                """))
                .andExpect(status().isCreated());
    }

    private void registerProposal() throws Exception {
        mockMvc.perform(post("/api/proposals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"PA","instruments":["CAM","SPEC"],"totalQuotaMinutes":120}
                                """))
                .andExpect(status().isCreated());
    }
}
