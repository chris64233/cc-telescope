package com.chris64233.cc.telescope.web;

import com.chris64233.cc.telescope.service.BookingService;
import com.chris64233.cc.telescope.service.PreemptionService;
import com.chris64233.cc.telescope.web.dto.BookReservationRequest;
import com.chris64233.cc.telescope.web.dto.ReservationResponse;
import com.chris64233.cc.telescope.web.dto.RescheduleRequest;
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
@RequestMapping("/api/reservations")
public class ReservationController {

    private final BookingService bookingService;
    private final PreemptionService preemptionService;

    public ReservationController(BookingService bookingService, PreemptionService preemptionService) {
        this.bookingService = bookingService;
        this.preemptionService = preemptionService;
    }

    @PostMapping
    public ResponseEntity<ReservationResponse> book(@Valid @RequestBody BookReservationRequest request) {
        var outcome = bookingService.book(request.idempotencyKey(), request.proposalCode(),
                request.telescopeCode(), request.instrument(), request.startTime(), request.endTime());
        HttpStatus status = outcome.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(ReservationResponse.from(outcome.reservation()));
    }

    @GetMapping("/{id}")
    public ReservationResponse find(@PathVariable Long id) {
        return ReservationResponse.from(bookingService.findReservation(id));
    }

    @PostMapping("/{id}/cancel")
    public ReservationResponse cancel(@PathVariable Long id) {
        return ReservationResponse.from(bookingService.cancel(id));
    }

    /** 将待重排预订安排到新时段；成功才扣减配额，失败不改变任何状态。 */
    @PostMapping("/{id}/reschedule")
    public ResponseEntity<ReservationResponse> reschedule(@PathVariable Long id,
                                                          @Valid @RequestBody RescheduleRequest request) {
        var outcome = preemptionService.reschedule(id, request.idempotencyKey(), request.telescopeCode(),
                request.instrument(), request.startTime(), request.endTime());
        HttpStatus status = outcome.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(ReservationResponse.from(outcome.newReservation()));
    }
}
