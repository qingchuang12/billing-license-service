package com.billing.license.common.web;

import com.billing.license.dto.ApiResponse;
import com.billing.license.exception.BusinessException;
import com.billing.license.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * N2（2026-09-20）契约测试：错误响应**不得被二次包壳**。
 *
 * <p>历史缺陷：{@code GlobalExceptionHandler} 返回自拼 Map，{@code ApiResponseAdvice}
 * 把它当成普通业务数据再包一层成功壳（{@code success:true}、真提示沉到 {@code data.message}），
 * 前端读顶层 {@code message} 恒为 null → 一律落到兜底文案「操作失败，请稍后重试」。
 * 包名为 {@code com.billing.license.exception}，{@code contains(".exception.")} 判断恒为 false，
 * 那道排除分支从未生效。
 *
 * <p>本测试锁定两条：① 异常处理器的返回值不被 advice 包裹；② 普通业务数据仍按原样包裹（防矫枉过正）。
 */
class ApiResponseAdviceTest {

    private final ApiResponseAdvice advice = new ApiResponseAdvice();

    private static MethodParameter handlerReturnType() throws NoSuchMethodException {
        Method m = GlobalExceptionHandler.class.getDeclaredMethod(
            "handleBusinessException", BusinessException.class);
        return new MethodParameter(m, -1);
    }

    private static ServletServerHttpRequest request(String path) {
        MockHttpServletRequest raw = new MockHttpServletRequest("GET", path);
        raw.setRequestURI(path);
        return new ServletServerHttpRequest(raw);
    }

    /** 异常处理器已返回统一壳 → 必须原样放行，不得再包一层 */
    @Test
    void errorEnvelope_shouldNotBeWrappedAgain() throws Exception {
        MethodParameter returnType = handlerReturnType();
        assertFalse(advice.supports(returnType, null),
            "异常处理器所在包必须以包名排除（.exception 结尾也要命中）");

        ApiResponse<Void> error = ApiResponse.fail("CODE_INVALID", "验证码无效或已被使用", "t-123");
        Object out = advice.beforeBodyWrite(error, returnType, MediaType.APPLICATION_JSON, null,
            request("/api/account/password/reset"), null);

        assertSame(error, out, "已是 ApiResponse 的错误响应必须原样返回，不得二次包壳");
        assertFalse(((ApiResponse<?>) out).success());
        assertEquals("CODE_INVALID", ((ApiResponse<?>) out).code());
    }

    /** 普通业务数据仍要包壳，避免为了修 N2 把成功路径改坏 */
    @Test
    void plainPayload_shouldStillBeWrapped() throws Exception {
        MethodParameter returnType = handlerReturnType();
        Object out = advice.beforeBodyWrite(Map.of("accessToken", "jwt"), returnType,
            MediaType.APPLICATION_JSON, null, request("/api/account/login"), null);

        assertTrue(out instanceof ApiResponse, "普通 Map 返回值仍应被包裹为统一壳");
        assertTrue(((ApiResponse<?>) out).success());
        assertEquals("SUCCESS", ((ApiResponse<?>) out).code());
    }

    /** already-ApiResponse 的成功响应同样只放行一次 */
    @Test
    void successEnvelope_shouldPassThrough() throws Exception {
        ApiResponse<String> body = ApiResponse.ok("ok");
        Object out = advice.beforeBodyWrite(body, handlerReturnType(), MediaType.APPLICATION_JSON, null,
            request("/api/products"), null);
        assertSame(body, out);
    }
}
