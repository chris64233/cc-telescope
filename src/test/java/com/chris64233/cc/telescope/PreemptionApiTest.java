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
class PreemptionApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private com.chris64233.cc.telescope.repository.PreemptionRecordRepository preemptionRecordRepository;
    @Autowired
    private com.chris64233.cc.telescope.repository.ReservationRepository reservationRepository;
    @Autowired
    private com.chris64233.cc.telescope.repository.OpportunityProposalRepository opportunityRepository;
    @Autowired
    private com.chris64233.cc.telescope.repository.ProposalRepository proposalRepository;
    @Autowired
    private com.chris64233.cc.telescope.repository.TelescopeRepository telescopeRepository;
    @Autowired
    private com.chris64233.cc.telescope.repository.WeatherAffectedRecordRepository weatherAffectedRepository;
    @Autowired
    private com.chris64233.cc.telescope.repository.WeatherEventRepository weatherEventRepository;

    @BeforeEach
    void cleanDatabase() {
        weatherAffectedRepository.deleteAll();
        preemptionRecordRepository.deleteAll();
        reservationRepository.deleteAll();
        weatherEventRepository.deleteAll();
        opportunityRepository.deleteAll();
        proposalRepository.deleteAll();
        telescopeRepository.deleteAll();
    }

    @Test
    void fullPreemptionLifecycleOverRest() throws Exception {
        registerTelescope();
        registerProposal();
        registerOpportunity();

        // 普通预订 10:00-11:00 CAM
        var booked = mockMvc.perform(post("/api/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"idempotencyKey":"n1","proposalCode":"PA","telescopeCode":"TA",
                                 "instrument":"CAM","startTime":"2030-06-01T10:00:00Z",
                                 "endTime":"2030-06-01T11:00:00Z"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ownerType").value("NORMAL"))
                .andReturn();
        long normalId = JsonPath.parse(booked.getResponse().getContentAsString()).read("$.id", Long.class);

        // 试算
        mockMvc.perform(post("/api/preemptions/plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"opportunityCode":"OA","telescopeCode":"TA","instrument":"CAM",
                                 "startTime":"2030-06-01T10:30:00Z","endTime":"2030-06-01T11:30:00Z"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feasible").value(true))
                .andExpect(jsonPath("$.affectedReservations", hasSize(1)))
                .andExpect(jsonPath("$.affectedReservations[0].ownerCode").value("PA"))
                .andExpect(jsonPath("$.affectedReservations[0].preemptable").value(true));

        // 确认抢占
        var confirmed = mockMvc.perform(post("/api/preemptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"businessKey":"PRE-1","opportunityCode":"OA","telescopeCode":"TA",
                                 "instrument":"CAM","startTime":"2030-06-01T10:30:00Z",
                                 "endTime":"2030-06-01T11:30:00Z"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.scheduleVersionBefore").value(0))
                .andExpect(jsonPath("$.scheduleVersionAfter").value(1))
                .andExpect(jsonPath("$.scheduleBefore", hasSize(1)))
                .andExpect(jsonPath("$.scheduleAfter", hasSize(1)))
                .andExpect(jsonPath("$.scheduleAfter[0].ownerType").value("OPPORTUNITY"))
                .andExpect(jsonPath("$.quotaChanges", hasSize(2)))
                .andReturn();
        long opportunityReservationId = JsonPath.parse(confirmed.getResponse().getContentAsString())
                .read("$.opportunityReservationId", Long.class);

        // 业务号幂等重放
        mockMvc.perform(post("/api/preemptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"businessKey":"PRE-1","opportunityCode":"OA","telescopeCode":"TA",
                                 "instrument":"CAM","startTime":"2030-06-01T10:30:00Z",
                                 "endTime":"2030-06-01T11:30:00Z"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.opportunityReservationId").value(opportunityReservationId));

        // 普通预订进入待重排
        mockMvc.perform(get("/api/reservations/" + normalId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_RESCHEDULE"))
                .andExpect(jsonPath("$.preemptedByOpportunityCode").value("OA"))
                .andExpect(jsonPath("$.preemptedByBusinessKey").value("PRE-1"));

        // 待重排任务列表
        mockMvc.perform(get("/api/opportunities/OA/pending-reschedules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(normalId));

        // 配额：PA 归还 60，OA 消费 60
        mockMvc.perform(get("/api/proposals/PA/quota"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remainingQuotaMinutes").value(120));
        mockMvc.perform(get("/api/opportunities/OA/quota"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priority").value(10))
                .andExpect(jsonPath("$.remainingQuotaMinutes").value(60));

        // 重排到 14:00-15:00
        mockMvc.perform(post("/api/reservations/" + normalId + "/reschedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"idempotencyKey":"RS-1","telescopeCode":"TA","instrument":"CAM",
                                 "startTime":"2030-06-01T14:00:00Z","endTime":"2030-06-01T15:00:00Z"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.ownerType").value("NORMAL"));

        // 重排后配额再次扣减，原预订变为 RESCHEDULED
        mockMvc.perform(get("/api/proposals/PA/quota"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remainingQuotaMinutes").value(60));
        mockMvc.perform(get("/api/reservations/" + normalId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESCHEDULED"))
                .andExpect(jsonPath("$.rescheduledToId").isNumber());
        mockMvc.perform(get("/api/opportunities/OA/pending-reschedules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        // 抢占记录查询
        mockMvc.perform(get("/api/preemptions/PRE-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businessKey").value("PRE-1"))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.affectedReservations", hasSize(1)));
        mockMvc.perform(get("/api/opportunities/OA/preemptions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void rejectedPreemptionReturnsReasonsAndLeavesSchedule() throws Exception {
        registerTelescope();
        registerProposal();
        // 只允许 SPEC 的机会提案申请 CAM 时段：仪器不兼容
        mockMvc.perform(post("/api/opportunities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"OB","priority":10,
                                 "responseDeadline":"2030-06-02T00:00:00Z","instruments":["SPEC"],
                                 "totalQuotaMinutes":120}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"idempotencyKey":"n2","proposalCode":"PA","telescopeCode":"TA",
                                 "instrument":"CAM","startTime":"2030-06-01T10:00:00Z",
                                 "endTime":"2030-06-01T11:00:00Z"}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/preemptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"businessKey":"PRE-REJ","opportunityCode":"OB","telescopeCode":"TA",
                                 "instrument":"CAM","startTime":"2030-06-01T10:00:00Z",
                                 "endTime":"2030-06-01T11:00:00Z"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.scheduleVersionBefore").value(0))
                .andExpect(jsonPath("$.scheduleVersionAfter").value(0))
                .andExpect(jsonPath("$.opportunityReservationId").doesNotExist())
                .andExpect(jsonPath("$.conflictReasons[0]").isString())
                .andExpect(jsonPath("$.affectedReservations", hasSize(1)))
                .andExpect(jsonPath("$.affectedReservations[0].preemptable").value(true));

        // 原日程与配额不变
        mockMvc.perform(get("/api/telescopes/TA/schedule"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].ownerCode").value("PA"));
        mockMvc.perform(get("/api/opportunities/OB/quota"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remainingQuotaMinutes").value(120));

        // 冲突原因可查询
        mockMvc.perform(get("/api/preemptions/PRE-REJ"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.conflictReasons[0]").value(org.hamcrest.Matchers.containsString("仪器")));
    }

    @Test
    void rescheduleConflictOverRestDoesNotConsumeQuota() throws Exception {
        registerTelescope();
        registerProposal();
        registerOpportunity();

        mockMvc.perform(post("/api/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"idempotencyKey":"n3","proposalCode":"PA","telescopeCode":"TA",
                                 "instrument":"CAM","startTime":"2030-06-01T10:00:00Z",
                                 "endTime":"2030-06-01T11:00:00Z"}
                                """))
                .andExpect(status().isCreated());
        var preempted = mockMvc.perform(post("/api/preemptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"businessKey":"PRE-3","opportunityCode":"OA","telescopeCode":"TA",
                                 "instrument":"CAM","startTime":"2030-06-01T10:00:00Z",
                                 "endTime":"2030-06-01T11:00:00Z"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        long normalId = JsonPath.parse(preempted.getResponse().getContentAsString())
                .read("$.affectedReservations[0].reservationId", Long.class);

        // 与机会预订重叠的重排被拒绝（409）
        mockMvc.perform(post("/api/reservations/" + normalId + "/reschedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"idempotencyKey":"RS-BAD","telescopeCode":"TA","instrument":"CAM",
                                 "startTime":"2030-06-01T10:30:00Z","endTime":"2030-06-01T11:30:00Z"}
                                """))
                .andExpect(status().isConflict());

        // 配额未二次扣减，任务仍待重排
        mockMvc.perform(get("/api/proposals/PA/quota"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remainingQuotaMinutes").value(120));
        mockMvc.perform(get("/api/opportunities/OA/pending-reschedules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
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

    private void registerOpportunity() throws Exception {
        mockMvc.perform(post("/api/opportunities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"OA","priority":10,
                                 "responseDeadline":"2030-06-02T00:00:00Z","instruments":["CAM","SPEC"],
                                 "totalQuotaMinutes":120}
                                """))
                .andExpect(status().isCreated());
    }
}
