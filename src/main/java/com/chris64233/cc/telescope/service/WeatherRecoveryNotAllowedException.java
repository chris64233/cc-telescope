package com.chris64233.cc.telescope.service;

import org.springframework.http.HttpStatus;

/**
 * 天气恢复排期不被允许：预订不处于天气阻断状态、恢复资格已耗尽/作废/撤销等。
 */
public class WeatherRecoveryNotAllowedException extends BookingException {

    public WeatherRecoveryNotAllowedException(String message) {
        super(HttpStatus.CONFLICT, message);
    }
}
