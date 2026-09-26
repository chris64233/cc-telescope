package com.chris64233.cc.telescope.web;

import com.chris64233.cc.telescope.service.BookingService;
import com.chris64233.cc.telescope.service.PreemptionService;
import com.chris64233.cc.telescope.web.dto.CreateProposalRequest;
import com.chris64233.cc.telescope.web.dto.QuotaResponse;
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
@RequestMapping("/api/proposals")
public class ProposalController {

    private final BookingService bookingService;
    private final PreemptionService preemptionService;

    public ProposalController(BookingService bookingService, PreemptionService preemptionService) {
        this.bookingService = bookingService;
        this.preemptionService = preemptionService;
    }

    @PostMapping
    public ResponseEntity<QuotaResponse> register(@Valid @RequestBody CreateProposalRequest request) {
        var proposal = bookingService.registerProposal(
                request.code(), request.instruments(), request.totalQuotaMinutes(),
                Boolean.TRUE.equals(request.targetOpportunity()), request.priority(),
                request.validUntil());
        return ResponseEntity.status(HttpStatus.CREATED).body(QuotaResponse.from(proposal));
    }

    @GetMapping("/{code}/quota")
    public QuotaResponse quota(@PathVariable String code) {
        return QuotaResponse.from(bookingService.quota(code));
    }

    /** 查询该提案下处于待重排状态的预订（被抢占后尚未重排或放弃）。 */
    @GetMapping("/{code}/pending-rearrangements")
    public List<ReservationResponse> pendingRearrangements(@PathVariable String code) {
        return preemptionService.pendingRearrangements(code).stream()
                .map(ReservationResponse::from)
                .toList();
    }
}
