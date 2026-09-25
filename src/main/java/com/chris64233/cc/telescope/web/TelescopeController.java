package com.chris64233.cc.telescope.web;

import com.chris64233.cc.telescope.service.BookingService;
import com.chris64233.cc.telescope.web.dto.CreateTelescopeRequest;
import com.chris64233.cc.telescope.web.dto.ReservationResponse;
import com.chris64233.cc.telescope.web.dto.TelescopeResponse;
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
@RequestMapping("/api/telescopes")
public class TelescopeController {

    private final BookingService bookingService;

    public TelescopeController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @PostMapping
    public ResponseEntity<TelescopeResponse> register(@Valid @RequestBody CreateTelescopeRequest request) {
        var telescope = bookingService.registerTelescope(
                request.code(), request.name(), request.switchMinutes(), request.instruments());
        return ResponseEntity.status(HttpStatus.CREATED).body(TelescopeResponse.from(telescope));
    }

    @GetMapping("/{code}/schedule")
    public List<ReservationResponse> schedule(@PathVariable String code) {
        return bookingService.schedule(code).stream().map(ReservationResponse::from).toList();
    }
}
