package com.chris64233.cc.telescope.web;

import com.chris64233.cc.telescope.service.BookingService;
import com.chris64233.cc.telescope.web.dto.CreateProposalRequest;
import com.chris64233.cc.telescope.web.dto.QuotaResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/proposals")
public class ProposalController {

    private final BookingService bookingService;

    public ProposalController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @PostMapping
    public ResponseEntity<QuotaResponse> register(@Valid @RequestBody CreateProposalRequest request) {
        var proposal = bookingService.registerProposal(
                request.code(), request.instruments(), request.totalQuotaMinutes());
        return ResponseEntity.status(HttpStatus.CREATED).body(QuotaResponse.from(proposal));
    }

    @GetMapping("/{code}/quota")
    public QuotaResponse quota(@PathVariable String code) {
        return QuotaResponse.from(bookingService.quota(code));
    }
}
