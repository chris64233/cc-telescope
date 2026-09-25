package com.chris64233.cc.telescope.web;

import com.chris64233.cc.telescope.dto.BookingRequest;
import com.chris64233.cc.telescope.dto.BookingResponse;
import com.chris64233.cc.telescope.dto.QuotaResponse;
import com.chris64233.cc.telescope.dto.ScheduleResponse;
import com.chris64233.cc.telescope.service.BookingService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BookingController {

    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    /**
     * 创建预订。幂等键优先取 Idempotency-Key 请求头，其次取请求体字段。
     */
    @PostMapping("/api/bookings")
    @ResponseStatus(HttpStatus.CREATED)
    public BookingResponse book(
            @RequestHeader(name = "Idempotency-Key", required = false) String headerKey,
            @Valid @RequestBody BookingRequest request) {
        String key = headerKey != null && !headerKey.isBlank()
                ? headerKey : request.idempotencyKey();
        BookingRequest effective = new BookingRequest(
                request.proposalNo(), request.telescopeCode(), request.instrument(),
                request.startAt(), request.endAt(), key);
        return bookingService.book(effective);
    }

    @PostMapping("/api/bookings/{id}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@PathVariable Long id) {
        bookingService.cancel(id);
    }

    @GetMapping("/api/telescopes/{code}/schedule")
    public ScheduleResponse schedule(@PathVariable String code) {
        return bookingService.getSchedule(code);
    }

    @GetMapping("/api/proposals/{proposalNo}/quota")
    public QuotaResponse quota(@PathVariable String proposalNo) {
        return bookingService.getQuota(proposalNo);
    }
}
