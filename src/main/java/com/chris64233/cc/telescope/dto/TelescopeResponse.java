package com.chris64233.cc.telescope.dto;

import java.util.List;

public record TelescopeResponse(
        Long id,
        String code,
        String name,
        int switchOverMinutes,
        List<String> instruments) {
}
