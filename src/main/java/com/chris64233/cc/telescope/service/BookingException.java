package com.chris64233.cc.telescope.service;

import org.springframework.http.HttpStatus;

public abstract class BookingException extends RuntimeException {

    private final HttpStatus status;

    protected BookingException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
