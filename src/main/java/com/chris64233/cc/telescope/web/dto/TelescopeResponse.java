package com.chris64233.cc.telescope.web.dto;

import com.chris64233.cc.telescope.domain.Telescope;

import java.util.Set;
import java.util.TreeSet;

public record TelescopeResponse(
        String code,
        String name,
        long switchMinutes,
        Set<String> instruments) {

    public static TelescopeResponse from(Telescope telescope) {
        return new TelescopeResponse(telescope.getCode(), telescope.getName(),
                telescope.getSwitchMinutes(), new TreeSet<>(telescope.getInstruments()));
    }
}
