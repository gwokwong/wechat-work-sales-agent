package com.example.wechatsales.exception;

/** 通用业务异常 */
public class BusinessException extends RuntimeException {

    public BusinessException(String message) {
        super(message);
    }
}
