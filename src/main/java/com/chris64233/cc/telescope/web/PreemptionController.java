package com.chris64233.cc.telescope.web;

import com.chris64233.cc.telescope.domain.PreemptionStatus;
import com.chris64233.cc.telescope.service.PreemptionService;
import com.chris64233.cc.telescope.web.dto.PreemptionPlanRequest;
import com.chris64233.cc.telescope.web.dto.PreemptionPlanResponse;
import com.chris64233.cc.telescope.web.dto.PreemptionRecordResponse;
import com.chris64233.cc.telescope.web.dto.PreemptionRequest;
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
@RequestMapping("/api/preemptions")
public class PreemptionController {

    private final PreemptionService preemptionService;

    public PreemptionController(PreemptionService preemptionService) {
        this.preemptionService = preemptionService;
    }

    /** 试算：不落库、不改变日程，返回受影响预订与前后仪器切换时间。 */
    @PostMapping("/plan")
    public PreemptionPlanResponse plan(@Valid @RequestBody PreemptionPlanRequest request) {
        var plan = preemptionService.plan(request.opportunityCode(), request.telescopeCode(),
                request.instrument(), request.startTime(), request.endTime());
        return PreemptionPlanResponse.from(plan);
    }

    /**
     * 确认抢占：以 businessKey 保证幂等。仅首次确认成功返回 201；
     * 被拒绝（原日程不变，记录落库）或业务号重放返回 200，
     * 通过响应体 status（CONFIRMED/REJECTED）区分。
     */
    @PostMapping
    public ResponseEntity<PreemptionRecordResponse> confirm(@Valid @RequestBody PreemptionRequest request) {
        var outcome = preemptionService.confirmOutcome(request.businessKey(), request.opportunityCode(),
                request.telescopeCode(), request.instrument(), request.startTime(), request.endTime());
        boolean created = outcome.created() && outcome.record().getStatus() == PreemptionStatus.CONFIRMED;
        return ResponseEntity.status(created ? HttpStatus.CREATED : HttpStatus.OK)
                .body(PreemptionRecordResponse.from(outcome.record()));
    }

    /** 查询抢占记录：日程版本、前后日程快照、配额变化、受影响预订与冲突原因。 */
    @GetMapping("/{businessKey}")
    public PreemptionRecordResponse find(@PathVariable String businessKey) {
        return PreemptionRecordResponse.from(preemptionService.findRecord(businessKey));
    }
}
