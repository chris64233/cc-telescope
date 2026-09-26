package com.chris64233.cc.telescope.web;

import com.chris64233.cc.telescope.service.PreemptionService;
import com.chris64233.cc.telescope.web.dto.CreateOpportunityRequest;
import com.chris64233.cc.telescope.web.dto.OpportunityQuotaResponse;
import com.chris64233.cc.telescope.web.dto.PreemptionRecordResponse;
import com.chris64233.cc.telescope.web.dto.ReservationResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/opportunities")
public class OpportunityController {

    private final PreemptionService preemptionService;

    public OpportunityController(PreemptionService preemptionService) {
        this.preemptionService = preemptionService;
    }

    @PostMapping
    public ResponseEntity<OpportunityQuotaResponse> register(@Valid @RequestBody CreateOpportunityRequest request) {
        var proposal = preemptionService.registerOpportunity(request.code(), request.priority(),
                request.responseDeadline(), request.instruments(), request.totalQuotaMinutes());
        return ResponseEntity.status(HttpStatus.CREATED).body(OpportunityQuotaResponse.from(proposal));
    }

    @GetMapping("/{code}/quota")
    public OpportunityQuotaResponse quota(@PathVariable String code) {
        return OpportunityQuotaResponse.from(preemptionService.opportunityQuota(code));
    }

    /** 该机会提案下全部抢占记录（含被拒绝的申请及其冲突原因）。 */
    @GetMapping("/{code}/preemptions")
    public List<PreemptionRecordResponse> preemptions(@PathVariable String code) {
        return preemptionService.recordsByOpportunity(code).stream()
                .map(PreemptionRecordResponse::from).toList();
    }

    /** 被该机会提案抢占、仍处于待重排状态的普通预订。 */
    @GetMapping("/{code}/pending-reschedules")
    public List<ReservationResponse> pendingReschedules(@PathVariable String code) {
        return preemptionService.pendingReschedules(code).stream()
                .map(ReservationResponse::from).toList();
    }
}
