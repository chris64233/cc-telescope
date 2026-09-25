package com.chris64233.cc.telescope.dto;

import jakarta.validation.constraints.NotBlank;

public record InstrumentRequest(
        @NotBlank String name) {
}
