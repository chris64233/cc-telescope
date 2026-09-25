package com.chris64233.cc.telescope.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record TelescopeRequest(
        @NotBlank String code,
        @NotBlank String name,
        @NotNull @PositiveOrZero Integer switchOverMinutes,
        List<String> instruments) {

    public TelescopeRequest {
        if (instruments == null) {
            instruments = List.of();
        }
    }
}
