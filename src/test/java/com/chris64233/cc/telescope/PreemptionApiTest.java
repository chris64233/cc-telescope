package com.chris64233.cc.telescope;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
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
    private TestDatabaseCleaner cleaner;

    @BeforeEach
    void setUp() {
        cleaner.clean();
    }

    private void registerBaseline() throws Exception {
        mockMvc.perform(post("/api/telescopes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"PT1","name":"抢占望远镜","switchMinutes":30,
                                 "instruments":["CAM","SPEC"]}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/proposals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"PP1","instruments":["CAM"],"totalQuotaMinutes":600}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/proposals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"PTOO","instruments":["CAM"],"totalQuotaMinutes":120,
                                 "targetOpportunity":true,"priority":10,
                                 "validUntil":"2031-01-01T00:00:00Z"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.targetOpportunity").value(true))
                .andExpect(jsonPath("$.priority").value(10));
    }

    private long book(String idempotencyKey, String proposal, String start, String end) throws Exception {
        String body = """
                {"idempotencyKey":"%s","proposalCode":"%s","telescopeCode":"PT1",
                 "instrument":"CAM","startTime":"%s","endTime":"%s"}
                """.formatted(idempotencyKey, proposal, start, end);
        var result = mockMvc.perform(post("/api/reservations")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.parse(result.getResponse().getContentAsString()).read("$.id", Long.class);
    }

    @Test
    void previewConfirmQueryAndRearrangeOverRest() throws Exception {
        registerBaseline();
        long victim = book("p-b1", "PP1", "2030-06-01T10:00:00Z", "2030-06-01T11:00:00Z");

        // 预览：被影响预订为 victim
        mockMvc.perform(post("/api/preemptions/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"proposalCode":"PTOO","telescopeCode":"PT1","instrument":"CAM",
                                 "startTime":"2030-06-01T10:00:00Z","endTime":"2030-06-01T11:00:00Z"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feasible").value(true))
                .andExpect(jsonPath("$.affectedReservations", hasSize(1)))
                .andExpect(jsonPath("$.affectedReservations[0].reservationId").value((int) victim));

        // 确认抢占
        String confirmBody = """
                {"businessKey":"PBK-1","proposalCode":"PTOO","telescopeCode":"PT1","instrument":"CAM",
                 "startTime":"2030-06-01T10:00:00Z","endTime":"2030-06-01T11:00:00Z"}
                """;
        var confirmed = mockMvc.perform(post("/api/preemptions")
                        .contentType(MediaType.APPLICATION_JSON).content(confirmBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.scheduleVersionBefore").value(1))
                .andExpect(jsonPath("$.scheduleVersionAfter").value(2))
                .andExpect(jsonPath("$.tooQuotaBeforeMinutes").value(120))
                .andExpect(jsonPath("$.tooQuotaAfterMinutes").value(60))
                .andExpect(jsonPath("$.affectedReservations", hasSize(1)))
                .andExpect(jsonPath("$.scheduleBefore", hasSize(1)))
                .andExpect(jsonPath("$.scheduleBefore[0].reservationId").value((int) victim))
                .andExpect(jsonPath("$.scheduleAfter", hasSize(1)))
                .andExpect(jsonPath("$.newReservationId").isNumber())
                .andReturn();
        long newReservationId = JsonPath.parse(confirmed.getResponse().getContentAsString())
                .read("$.newReservationId", Long.class);

        // 业务号幂等重放
        mockMvc.perform(post("/api/preemptions")
                        .contentType(MediaType.APPLICATION_JSON).content(confirmBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.newReservationId").value((int) newReservationId));

        // 原预订进入待重排；配额已归还；日程只剩新预订
        mockMvc.perform(get("/api/reservations/" + victim))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_REARRANGE"))
                .andExpect(jsonPath("$.preemptedByBusinessKey").value("PBK-1"));
        mockMvc.perform(get("/api/proposals/PP1/quota"))
                .andExpect(jsonPath("$.remainingQuotaMinutes").value(600));
        mockMvc.perform(get("/api/telescopes/PT1/schedule"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value((int) newReservationId));

        // 待重排查询
        mockMvc.perform(get("/api/proposals/PP1/pending-rearrangements"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value((int) victim));

        // 抢占单详情：版本、快照、配额变化、受影响预订
        mockMvc.perform(get("/api/preemptions/PBK-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businessKey").value("PBK-1"))
                .andExpect(jsonPath("$.reasons").isArray())
                .andExpect(jsonPath("$.scheduleBefore[0].reservationId").value((int) victim));

        // 重排
        mockMvc.perform(post("/api/reservations/" + victim + "/rearrange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"idempotencyKey":"prr-1",
                                 "startTime":"2030-06-01T14:00:00Z","endTime":"2030-06-01T15:00:00Z"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.rearrangedFromId").value((int) victim))
                .andExpect(jsonPath("$.durationMinutes").value(60));

        // 重排后重新扣减，待重排列表清空，原预订进入终态
        mockMvc.perform(get("/api/proposals/PP1/quota"))
                .andExpect(jsonPath("$.remainingQuotaMinutes").value(540));
        mockMvc.perform(get("/api/proposals/PP1/pending-rearrangements"))
                .andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(get("/api/reservations/" + victim))
                .andExpect(jsonPath("$.status").value("PREEMPTED"));
    }

    @Test
    void rejectedPreemptionReturns422AndPersistsReasons() throws Exception {
        registerBaseline();
        // 高优先级 ToO 预订不可被低优先级抢占方抢占
        mockMvc.perform(post("/api/proposals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"PHI","instruments":["CAM"],"totalQuotaMinutes":300,
                                 "targetOpportunity":true,"priority":20,
                                 "validUntil":"2031-01-01T00:00:00Z"}
                                """))
                .andExpect(status().isCreated());
        book("p-hi", "PHI", "2030-06-01T10:00:00Z", "2030-06-01T11:00:00Z");

        mockMvc.perform(post("/api/preemptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"businessKey":"PBK-REJ","proposalCode":"PTOO","telescopeCode":"PT1",
                                 "instrument":"CAM","startTime":"2030-06-01T10:30:00Z",
                                 "endTime":"2030-06-01T11:00:00Z"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.reasons[0]", containsString("NON_PREEMPTABLE_RESERVATION")))
                .andExpect(jsonPath("$.affectedReservations", hasSize(0)));

        // 原日程完全不变
        mockMvc.perform(get("/api/telescopes/PT1/schedule"))
                .andExpect(jsonPath("$", hasSize(1)));
        mockMvc.perform(get("/api/proposals/PTOO/quota"))
                .andExpect(jsonPath("$.remainingQuotaMinutes").value(120));
        // 拒绝记录可按业务号查询
        mockMvc.perform(get("/api/preemptions/PBK-REJ"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
    }
}
