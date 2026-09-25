package com.chris64233.cc.telescope.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.Set;

public record CreateTelescopeRequest(
        @NotBlank String code,
        @NotBlank String name,
        @Min(0) long switchMinutes,
        @NotEmpty Set<String> instruments) {
}
