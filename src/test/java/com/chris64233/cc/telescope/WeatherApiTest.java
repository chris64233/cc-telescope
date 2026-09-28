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
    private com.chris64233.cc.telescope.repository.WeatherAffectedRecordRepository affectedRepository;
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
        affectedRepository.deleteAll();
        reservationRepository.deleteAll();
        weatherEventRepository.deleteAll();
        opportunityRepository.deleteAll();
        proposalRepository.deleteAll();
        telescopeRepository.deleteAll();
    }

    @Test
    void fullWeatherClosureAndRecoveryLifecycleOverRest() throws Exception {
        registerTelescope();
        registerProposal();

        // 未来观测 10:00-12:00 CAM（120 分钟）
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

        // 天气关闭 09:00-13:00
        var closed = mockMvc.perform(post("/api/weather/closures")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"businessKey":"WX-1","telescopeCode":"TA","reason":"雷暴",
                                 "startTime":"2030-06-01T09:00:00Z","endTime":"2030-06-01T13:00:00Z"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.event.businessKey").value("WX-1"))
                .andExpect(jsonPath("$.event.windowVersion").value(0))
                .andExpect(jsonPath("$.event.windowHistory", hasSize(1)))
                .andExpect(jsonPath("$.affected", hasSize(1)))
                .andExpect(jsonPath("$.affected[0].ownerType").value("NORMAL"))
                .andExpect(jsonPath("$.affected[0].ownerCode").value("PA"))
                .andExpect(jsonPath("$.affected[0].recoverableMinutes").value(120))
                .andExpect(jsonPath("$.affected[0].status").value("AFFECTED"))
                .andReturn();
        long affectedId = JsonPath.parse(closed.getResponse().getContentAsString())
                .read("$.affected[0].id", Long.class);

        // 业务号幂等重放：200、replayed=true，不重复释放
        mockMvc.perform(post("/api/weather/closures")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"businessKey":"WX-1","telescopeCode":"TA","reason":"雷暴",
                                 "startTime":"2030-06-01T09:00:00Z","endTime":"2030-06-01T13:00:00Z"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.affected", hasSize(1)));

        // 原预订变为 WEATHER_CANCELLED，配额已释放回 300
        mockMvc.perform(get("/api/reservations/" + originalId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WEATHER_CANCELLED"))
                .andExpect(jsonPath("$.weatherEventBusinessKey").value("WX-1"));
        mockMvc.perform(get("/api/proposals/PA/quota"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remainingQuotaMinutes").value(300));
        // 日程为空
        mockMvc.perform(get("/api/telescopes/TA/schedule"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        // 第一次恢复 60 分钟 → 部分恢复
        var first = mockMvc.perform(post("/api/weather/affected/" + affectedId + "/recover")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"idempotencyKey":"RC-1","telescopeCode":"TA","instrument":"CAM",
                                 "startTime":"2030-06-01T15:00:00Z","endTime":"2030-06-01T16:00:00Z"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reservation.status").value("ACTIVE"))
                .andExpect(jsonPath("$.reservation.ownerCode").value("PA"))
                .andExpect(jsonPath("$.affected.status").value("AFFECTED"))
                .andExpect(jsonPath("$.affected.recoverableMinutes").value(60))
                .andExpect(jsonPath("$.affected.recoveryBookings", hasSize(1)))
                .andReturn();
        long newId = JsonPath.parse(first.getResponse().getContentAsString())
                .read("$.reservation.id", Long.class);
        mockMvc.perform(get("/api/proposals/PA/quota"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remainingQuotaMinutes").value(240));

        // 幂等重放恢复：200，返回同一新预订，不重复消耗
        mockMvc.perform(post("/api/weather/affected/" + affectedId + "/recover")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"idempotencyKey":"RC-1","telescopeCode":"TA","instrument":"CAM",
                                 "startTime":"2030-06-01T15:00:00Z","endTime":"2030-06-01T16:00:00Z"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(false))
                .andExpect(jsonPath("$.reservation.id").value(newId))
                .andExpect(jsonPath("$.affected.recoveryBookings", hasSize(1)));
        mockMvc.perform(get("/api/proposals/PA/quota"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remainingQuotaMinutes").value(240));

        // 第二次用完剩余 60 分钟 → RECOVERED
        mockMvc.perform(post("/api/weather/affected/" + affectedId + "/recover")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"idempotencyKey":"RC-2","telescopeCode":"TA","instrument":"CAM",
                                 "startTime":"2030-06-01T17:00:00Z","endTime":"2030-06-01T18:00:00Z"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.affected.status").value("RECOVERED"))
                .andExpect(jsonPath("$.affected.recoverableMinutes").value(0))
                .andExpect(jsonPath("$.affected.recoveryBookings", hasSize(2)));

        // 原预订变为 WEATHER_RECOVERED，配额 180
        mockMvc.perform(get("/api/reservations/" + originalId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WEATHER_RECOVERED"))
                .andExpect(jsonPath("$.weatherRecoveredToId").isNumber());
        mockMvc.perform(get("/api/proposals/PA/quota"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remainingQuotaMinutes").value(180));

        // 查询：关联原预订、天气事件、新预订与剩余可恢复分钟数
        mockMvc.perform(get("/api/weather/affected/" + affectedId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.weatherEventBusinessKey").value("WX-1"))
                .andExpect(jsonPath("$.originalReservationId").value(originalId))
                .andExpect(jsonPath("$.recoveryBookings", hasSize(2)))
                .andExpect(jsonPath("$.recoveryBookings[0].reservation.id").isNumber());
        mockMvc.perform(get("/api/weather/closures/WX-1/affected"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
        mockMvc.perform(get("/api/weather/closures/WX-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businessKey").value("WX-1"))
                .andExpect(jsonPath("$.windowHistory", hasSize(1)));
    }

    @Test
    void recoverValidationFailureLeavesNoOccupancyOverRest() throws Exception {
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
        var closed = mockMvc.perform(post("/api/weather/closures")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"businessKey":"WX-2","telescopeCode":"TA",
                                 "startTime":"2030-06-01T09:00:00Z","endTime":"2030-06-01T12:00:00Z"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        long affectedId = JsonPath.parse(closed.getResponse().getContentAsString())
                .read("$.affected[0].id", Long.class);

        // 申请 90 分钟 > 60 可恢复 → 422，无任何占用
        mockMvc.perform(post("/api/weather/affected/" + affectedId + "/recover")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"idempotencyKey":"RC-BIG","telescopeCode":"TA","instrument":"CAM",
                                 "startTime":"2030-06-01T15:00:00Z","endTime":"2030-06-01T16:30:00Z"}
                                """))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get("/api/proposals/PA/quota"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remainingQuotaMinutes").value(300));
        mockMvc.perform(get("/api/weather/affected/" + affectedId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AFFECTED"))
                .andExpect(jsonPath("$.recoverableMinutes").value(60))
                .andExpect(jsonPath("$.recoveryBookings", hasSize(0)));
    }

    @Test
    void windowAdjustmentExpandAndShrinkOverRest() throws Exception {
        registerTelescope();
        registerProposal();

        mockMvc.perform(post("/api/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"idempotencyKey":"a1","proposalCode":"PA","telescopeCode":"TA",
                                 "instrument":"CAM","startTime":"2030-06-01T10:00:00Z",
                                 "endTime":"2030-06-01T11:00:00Z"}
                                """))
                .andExpect(status().isCreated());
        var r2 = mockMvc.perform(post("/api/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"idempotencyKey":"a2","proposalCode":"PA","telescopeCode":"TA",
                                 "instrument":"CAM","startTime":"2030-06-01T13:00:00Z",
                                 "endTime":"2030-06-01T14:00:00Z"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        long secondId = JsonPath.parse(r2.getResponse().getContentAsString()).read("$.id", Long.class);

        // 初始窗口只覆盖 10:00 那条
        mockMvc.perform(post("/api/weather/closures")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"businessKey":"WX-3","telescopeCode":"TA",
                                 "startTime":"2030-06-01T09:30:00Z","endTime":"2030-06-01T11:30:00Z"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.affected", hasSize(1)));

        // 扩大窗口覆盖 13:00
        mockMvc.perform(post("/api/weather/closures/adjust")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"businessKey":"WX-3","telescopeCode":"TA",
                                 "startTime":"2030-06-01T09:30:00Z","endTime":"2030-06-01T14:30:00Z"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.event.windowVersion").value(1))
                .andExpect(jsonPath("$.event.windowHistory", hasSize(2)))
                .andExpect(jsonPath("$.affected", hasSize(2)));
        mockMvc.perform(get("/api/reservations/" + secondId))
                .andExpect(jsonPath("$.status").value("WEATHER_CANCELLED"));

        // 缩小窗口：13:00 那条还原
        mockMvc.perform(post("/api/weather/closures/adjust")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"businessKey":"WX-3","telescopeCode":"TA",
                                 "startTime":"2030-06-01T09:30:00Z","endTime":"2030-06-01T11:30:00Z"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.event.windowVersion").value(2));
        mockMvc.perform(get("/api/reservations/" + secondId))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
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
                                {"code":"PA","instruments":["CAM","SPEC"],"totalQuotaMinutes":300}
                                """))
                .andExpect(status().isCreated());
    }
}
