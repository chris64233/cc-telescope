package com.chris64233.cc.telescope.web;

import com.chris64233.cc.telescope.service.WeatherService;
import com.chris64233.cc.telescope.service.WeatherService.AffectedView;
import com.chris64233.cc.telescope.service.WeatherService.CloseOutcome;
import com.chris64233.cc.telescope.service.WeatherService.RecoveryOutcome;
import com.chris64233.cc.telescope.web.dto.WeatherAdjustRequest;
import com.chris64233.cc.telescope.web.dto.WeatherAffectedResponse;
import com.chris64233.cc.telescope.web.dto.WeatherCloseRequest;
import com.chris64233.cc.telescope.web.dto.WeatherCloseResponse;
import com.chris64233.cc.telescope.web.dto.WeatherEventResponse;
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

import java.util.List;

/**
 * 天气关闭与受影响观测的恢复排期接口。
 */
@RestController
@RequestMapping("/api/weather")
public class WeatherController {

    private final WeatherService weatherService;

    public WeatherController(WeatherService weatherService) {
        this.weatherService = weatherService;
    }

    /**
     * 登记天气关闭：原子标记窗口内未开始的观测并释放资源（已完成/执行中不变）。
     * 首次成功 201；相同业务号幂等重放 200。
     */
    @PostMapping("/closures")
    public ResponseEntity<WeatherCloseResponse> close(@Valid @RequestBody WeatherCloseRequest request) {
        CloseOutcome outcome = weatherService.close(request.businessKey(), request.telescopeCode(),
                request.reason(), request.startTime(), request.endTime());
        List<AffectedView> views = weatherService.affectedViewsByEvent(request.businessKey());
        HttpStatus status = outcome.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(WeatherCloseResponse.from(outcome, views));
    }

    /**
     * 调整天气关闭范围（扩大/缩小），在原业务号上生效：
     * 扩大中断新覆盖观测、缩小尝试还原不再受影响观测。相同窗口重放幂等。
     */
    @PostMapping("/closures/adjust")
    public ResponseEntity<WeatherCloseResponse> adjust(@Valid @RequestBody WeatherAdjustRequest request) {
        CloseOutcome outcome = weatherService.adjustWindow(request.businessKey(), request.telescopeCode(),
                request.startTime(), request.endTime());
        List<AffectedView> views = weatherService.affectedViewsByEvent(request.businessKey());
        return ResponseEntity.ok(WeatherCloseResponse.from(outcome, views));
    }

    /** 查询天气关闭事件（当前窗口、窗口历史、日程版本）。 */
    @GetMapping("/closures/{businessKey}")
    public WeatherEventResponse findEvent(@PathVariable String businessKey) {
        return WeatherEventResponse.from(weatherService.findEvent(businessKey));
    }

    /** 查询某天气事件下全部受影响记录（关联原预订、新预订与剩余可恢复分钟数）。 */
    @GetMapping("/closures/{businessKey}/affected")
    public List<WeatherAffectedResponse> affected(@PathVariable String businessKey) {
        return weatherService.affectedViewsByEvent(businessKey).stream()
                .map(WeatherAffectedResponse::from).toList();
    }

    /** 查询单条受影响记录（含原预订、天气事件、新预订、剩余可恢复分钟数）。 */
    @GetMapping("/affected/{id}")
    public WeatherAffectedResponse findAffected(@PathVariable Long id) {
        return WeatherAffectedResponse.from(weatherService.affectedView(id));
    }

    /**
     * 恢复排期：受影响提案以原优先级与剩余可恢复分钟数申请新时段。
     * 首次成功 201；相同幂等键重放 200；新时段校验失败 409/422 且不产生任何占用。
     */
    @PostMapping("/affected/{id}/recover")
    public ResponseEntity<WeatherRecoveryResponse> recover(@PathVariable Long id,
                                                           @Valid @RequestBody WeatherRecoveryRequest request) {
        RecoveryOutcome outcome = weatherService.recover(id, request.idempotencyKey(),
                request.telescopeCode(), request.instrument(), request.startTime(), request.endTime());
        AffectedView view = weatherService.affectedView(outcome.affected().getId());
        HttpStatus status = outcome.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(WeatherRecoveryResponse.from(outcome, view));
    }

    /** 放弃待恢复任务：剩余可恢复分钟数清零，不重复释放配额。重复放弃幂等。 */
    @PostMapping("/affected/{id}/abandon")
    public WeatherAffectedResponse abandon(@PathVariable Long id) {
        return WeatherAffectedResponse.from(weatherService.abandon(id));
    }
}
