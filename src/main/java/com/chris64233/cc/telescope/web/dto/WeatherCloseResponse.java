package com.chris64233.cc.telescope.web.dto;

import com.chris64233.cc.telescope.service.WeatherService.AffectedView;
import com.chris64233.cc.telescope.service.WeatherService.CloseOutcome;

import java.util.List;

/**
 * 天气关闭/范围调整结果：事件详情与全部受影响记录。
 * {@code replayed=true} 表示相同业务号的幂等重放（未重复释放资源）。
 */
public record WeatherCloseResponse(
        boolean created,
        boolean replayed,
        WeatherEventResponse event,
        List<WeatherAffectedResponse> affected) {

    public static WeatherCloseResponse from(CloseOutcome outcome, List<AffectedView> views) {
        return new WeatherCloseResponse(outcome.created(), !outcome.created(),
                WeatherEventResponse.from(outcome.event()),
                views.stream().map(WeatherAffectedResponse::from).toList());
    }
}
