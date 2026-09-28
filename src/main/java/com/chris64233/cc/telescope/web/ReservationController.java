package com.chris64233.cc.telescope.web;

import com.chris64233.cc.telescope.service.BookingService;
import com.chris64233.cc.telescope.service.PreemptionService;
import com.chris64233.cc.telescope.service.WeatherService;
import com.chris64233.cc.telescope.web.dto.BookReservationRequest;
import com.chris64233.cc.telescope.web.dto.ReservationResponse;
import com.chris64233.cc.telescope.web.dto.RescheduleRequest;
import com.chris64233.cc.telescope.web.dto.WeatherRecoveryRequest;
import com.chris64233.cc.telescope.web.dto.WeatherRecoveryResponse;
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
    private final WeatherService weatherService;

    public ReservationController(BookingService bookingService, PreemptionService preemptionService,
                                 WeatherService weatherService) {
        this.bookingService = bookingService;
        this.preemptionService = preemptionService;
        this.weatherService = weatherService;
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

    /**
     * 天气恢复排期：为被阻断预订申请新时段，继承原优先级、只消耗可恢复分钟（不动配额）。
     * 请求幂等；新时段校验失败整体回滚，不留部分占用。
     */
    @PostMapping("/{id}/recover")
    public ResponseEntity<ReservationResponse> recover(@PathVariable Long id,
                                                       @Valid @RequestBody WeatherRecoveryRequest request) {
        var outcome = weatherService.recover(id, request.idempotencyKey(), request.telescopeCode(),
                request.instrument(), request.startTime(), request.endTime());
        HttpStatus status = outcome.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(ReservationResponse.from(outcome.newReservation()));
    }

    /** 查询某原预订的天气恢复详情：天气事件、原预订、新预订与剩余可恢复分钟数。 */
    @GetMapping("/{id}/recovery")
    public WeatherRecoveryResponse recovery(@PathVariable Long id) {
        var view = weatherService.findRecovery(id);
        return WeatherRecoveryResponse.from(view.recovery(), view.recoveredReservations());
    }
}
