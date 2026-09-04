package com.example.wechatsales.exception;

/** 资源不存在 */
public class NotFoundException extends BusinessException {

    public NotFoundException(String message) {
        super(message);
    }
}
