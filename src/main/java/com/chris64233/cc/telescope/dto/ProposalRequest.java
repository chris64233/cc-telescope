package com.chris64233.cc.telescope.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record ProposalRequest(
        @NotBlank String proposalNo,
        @NotNull @Positive Integer totalMinutes,
        List<String> instruments) {

    public ProposalRequest {
        if (instruments == null) {
            instruments = List.of();
        }
    }
}
