package com.billing.license.exception;

import lombok.Getter;

/**
 * 业务异常类
 * 用于封装业务逻辑中的错误信息
 */
@Getter
public class BusinessException extends RuntimeException {
    
    /** 错误码，用于标识具体的业务错误类型 */
    private final String errorCode;
    
    /**
     * 构造函数
     * @param errorCode 错误码
     * @param message 错误消息
     */
    public BusinessException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
