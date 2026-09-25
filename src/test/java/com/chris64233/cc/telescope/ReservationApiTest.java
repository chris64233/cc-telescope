package com.chris64233.cc.telescope;

import com.jayway.jsonpath.JsonPath;
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
class ReservationApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void fullBookingLifecycleOverRest() throws Exception {
        mockMvc.perform(post("/api/telescopes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"API-T1","name":"测试望远镜","switchMinutes":30,
                                 "instruments":["CAM","SPEC"]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("API-T1"))
                .andExpect(jsonPath("$.switchMinutes").value(30));

        mockMvc.perform(post("/api/proposals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"API-P1","instruments":["CAM"],"totalQuotaMinutes":120}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.remainingQuotaMinutes").value(120));

        String body = """
                {"idempotencyKey":"api-key-1","proposalCode":"API-P1","telescopeCode":"API-T1",
                 "instrument":"CAM","startTime":"2030-06-01T10:00:00Z","endTime":"2030-06-01T11:00:00Z"}
                """;
        var created = mockMvc.perform(post("/api/reservations")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.durationMinutes").value(60))
                .andReturn();
        long reservationId = JsonPath.parse(created.getResponse().getContentAsString()).read("$.id", Long.class);

        // 相同内容重放返回原结果
        mockMvc.perform(post("/api/reservations")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber());

        // 相同幂等键不同内容返回冲突
        mockMvc.perform(post("/api/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"idempotencyKey":"api-key-1","proposalCode":"API-P1","telescopeCode":"API-T1",
                                 "instrument":"CAM","startTime":"2030-06-01T12:00:00Z","endTime":"2030-06-01T13:00:00Z"}
                                """))
                .andExpect(status().isConflict());

        mockMvc.perform(get("/api/telescopes/API-T1/schedule"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].instrument").value("CAM"));

        mockMvc.perform(get("/api/proposals/API-P1/quota"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usedQuotaMinutes").value(60))
                .andExpect(jsonPath("$.remainingQuotaMinutes").value(60));

        mockMvc.perform(post("/api/reservations/" + reservationId + "/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        mockMvc.perform(get("/api/proposals/API-P1/quota"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remainingQuotaMinutes").value(120));

        mockMvc.perform(get("/api/telescopes/API-T1/schedule"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void unknownTelescopeReturnsNotFound() throws Exception {
        mockMvc.perform(post("/api/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"idempotencyKey":"api-key-404","proposalCode":"NOPE","telescopeCode":"NOPE",
                                 "instrument":"CAM","startTime":"2030-06-01T10:00:00Z","endTime":"2030-06-01T11:00:00Z"}
                                """))
                .andExpect(status().isNotFound());
    }
}
