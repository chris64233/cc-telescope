package com.chris64233.cc.telescope.web;

import com.chris64233.cc.telescope.domain.PreemptionStatus;
import com.chris64233.cc.telescope.service.PreemptionService;
import com.chris64233.cc.telescope.web.dto.ConfirmPreemptionRequest;
import com.chris64233.cc.telescope.web.dto.PreemptionPreviewRequest;
import com.chris64233.cc.telescope.web.dto.PreemptionPreviewResponse;
import com.chris64233.cc.telescope.web.dto.PreemptionResponse;
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

    /** 抢占预览：计算被影响的普通预订与前后仪器切换时间，不改变任何状态。 */
    @PostMapping("/preview")
    public PreemptionPreviewResponse preview(@Valid @RequestBody PreemptionPreviewRequest request) {
        return PreemptionPreviewResponse.from(preemptionService.preview(
                request.proposalCode(), request.telescopeCode(), request.instrument(),
                request.startTime(), request.endTime()));
    }

    /**
     * 确认抢占（业务号幂等）。可行时一次性取消被覆盖预订、归还配额并建立新预订；
     * 存在冲突原因时保存 REJECTED 记录（HTTP 422），原日程完全不变。
     */
    @PostMapping
    public ResponseEntity<PreemptionResponse> confirm(@Valid @RequestBody ConfirmPreemptionRequest request) {
        var preemption = preemptionService.confirm(request.businessKey(), request.proposalCode(),
                request.telescopeCode(), request.instrument(), request.startTime(), request.endTime());
        HttpStatus status = preemption.getStatus() == PreemptionStatus.CONFIRMED
                ? HttpStatus.CREATED : HttpStatus.UNPROCESSABLE_ENTITY;
        return ResponseEntity.status(status).body(PreemptionResponse.from(preemption));
    }

    /** 按业务号查询抢占单：前后日程快照、日程版本、配额变化、受影响预订与冲突原因。 */
    @GetMapping("/{businessKey}")
    public PreemptionResponse find(@PathVariable String businessKey) {
        return PreemptionResponse.from(preemptionService.findPreemption(businessKey));
    }
}
