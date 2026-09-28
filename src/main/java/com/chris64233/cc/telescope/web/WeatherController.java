package com.chris64233.cc.telescope.web;

import com.chris64233.cc.telescope.service.WeatherService;
import com.chris64233.cc.telescope.web.dto.AdjustWeatherRequest;
import com.chris64233.cc.telescope.web.dto.DeclareWeatherRequest;
import com.chris64233.cc.telescope.web.dto.WeatherEventResponse;
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

import java.util.List;

@RestController
@RequestMapping("/api/weather")
public class WeatherController {

    private final WeatherService weatherService;

    public WeatherController(WeatherService weatherService) {
        this.weatherService = weatherService;
    }

    /** 登记天气关闭事件（携带业务号，幂等；首次 201，重放 200）。 */
    @PostMapping
    public ResponseEntity<WeatherEventResponse> declare(@Valid @RequestBody DeclareWeatherRequest request) {
        var outcome = weatherService.declare(request.businessKey(), request.telescopeCode(),
                request.startTime(), request.endTime());
        return ResponseEntity.status(outcome.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(WeatherEventResponse.from(outcome.event()));
    }

    /** 调整关闭窗口范围：扩大阻断新增预订，缩小恢复落出窗口的预订。 */
    @PostMapping("/{businessKey}/adjust")
    public ResponseEntity<WeatherEventResponse> adjust(@PathVariable String businessKey,
                                                       @Valid @RequestBody AdjustWeatherRequest request) {
        var outcome = weatherService.adjust(businessKey, request.startTime(), request.endTime());
        return ResponseEntity.status(outcome.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(WeatherEventResponse.from(outcome.event()));
    }

    /** 查询关闭事件：当前窗口、受影响预订快照与操作历史。 */
    @GetMapping("/{businessKey}")
    public WeatherEventResponse find(@PathVariable String businessKey) {
        return WeatherEventResponse.from(weatherService.findEvent(businessKey));
    }

    /** 查询事件下全部恢复资格：原预订、天气事件、新预订与剩余可恢复分钟数。 */
    @GetMapping("/{businessKey}/recoveries")
    public List<WeatherRecoveryResponse> recoveries(@PathVariable String businessKey) {
        return weatherService.recoveriesByEvent(businessKey).stream()
                .map(v -> WeatherRecoveryResponse.from(v.recovery(), v.recoveredReservations()))
                .toList();
    }

    /** 当前仍可恢复排期（BLOCKED / PARTIALLY_RECOVERED）的全部资格。 */
    @GetMapping("/recoverable")
    public List<WeatherRecoveryResponse> recoverable() {
        return weatherService.recoverableReservations().stream()
                .map(v -> WeatherRecoveryResponse.from(v.recovery(), v.recoveredReservations()))
                .toList();
    }
}
