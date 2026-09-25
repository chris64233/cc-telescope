package com.chris64233.cc.telescope.service;

import org.springframework.http.HttpStatus;

public class ResourceNotFoundException extends BookingException {

    public ResourceNotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, message);
    }
}
