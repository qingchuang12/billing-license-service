package com.billing.license.exception;

/**
 * 管理后台鉴权失败异常。
 *
 * i9 修正：从 {@code AdminController} 内部类抽离为独立的顶层异常类，
 * 消除异常包反向依赖 controller 包的不合理分层（GlobalExceptionHandler 本就位于
 * exception 包，不应依赖 controller 包的内部类）。由 GlobalExceptionHandler 统一处理。
 */
public class AdminUnauthorizedException extends RuntimeException {

    public AdminUnauthorizedException() {
        super("Admin API key invalid");
    }
}
