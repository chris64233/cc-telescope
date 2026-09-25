package com.chris64233.cc.telescope.service;

import org.springframework.http.HttpStatus;

public class BusinessRuleException extends BookingException {

    public BusinessRuleException(String message) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
}
