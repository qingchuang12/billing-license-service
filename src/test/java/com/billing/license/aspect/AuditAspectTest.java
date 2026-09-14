package com.billing.license.aspect;

import com.billing.license.annotation.Audit;
import com.billing.license.entity.AuditLog;
import com.billing.license.service.AuditLogService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * E 验证（无需数据库）：直接驱动 {@link AuditAspect#around}，
 * 断言审计语义——action/actor 取值、SpEL target 解析、抛异常记 FAIL 且原样抛出。
 */
class AuditAspectTest {

    @SuppressWarnings("unused")
    static class Target {
        @Audit(action = "DO_X", target = "#id", actor = "tester")
        public String doX(String id) {
            return "ok";
        }

        @Audit(action = "FAIL_X", actor = "tester")
        public String failX() {
            throw new IllegalStateException("boom");
        }
    }

    private Audit annotationFor(String method, Class<?>... paramTypes) throws Exception {
        Method m = Target.class.getMethod(method, paramTypes);
        return m.getAnnotation(Audit.class);
    }

    private ProceedingJoinPoint pjpFor(String method, Class<?>[] paramTypes, Object[] args, Object resultOrError)
            throws Throwable {
        Method m = Target.class.getMethod(method, paramTypes);
        MethodSignature sig = mock(MethodSignature.class);
        when(sig.getMethod()).thenReturn(m);
        when(sig.getName()).thenReturn(m.getName());
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.getSignature()).thenReturn(sig);
        when(pjp.getArgs()).thenReturn(args);
        if (resultOrError instanceof Throwable) {
            when(pjp.proceed()).thenThrow((Throwable) resultOrError);
        } else {
            when(pjp.proceed()).thenReturn(resultOrError);
        }
        return pjp;
    }

    @Test
    void success_recordsPersistWithResolvedTarget() throws Throwable {
        AuditLogService svc = mock(AuditLogService.class);
        AuditAspect aspect = new AuditAspect(svc);
        ProceedingJoinPoint pjp = pjpFor("doX", new Class<?>[]{String.class}, new Object[]{"ORDER-1"}, "ok");

        Object ret = aspect.around(pjp, annotationFor("doX", String.class));
        assertEquals("ok", ret);

        ArgumentCaptor<AuditLog> cap = ArgumentCaptor.forClass(AuditLog.class);
        verify(svc).persist(cap.capture());
        AuditLog rec = cap.getValue();
        assertEquals("DO_X", rec.getAction());
        assertEquals("tester", rec.getActor());
        assertTrue(rec.isSuccess());
        // SpEL #id 解析为方法实参（Spring Boot 编译开启 -parameters，参数名可发现）
        assertEquals("ORDER-1", rec.getTarget());
        verify(svc).audit(eq("tester"), eq("DO_X"), eq("ORDER-1"), eq(true), any());
    }

    @Test
    void failure_recordsFailAndRethrows() throws Throwable {
        AuditLogService svc = mock(AuditLogService.class);
        AuditAspect aspect = new AuditAspect(svc);
        ProceedingJoinPoint pjp = pjpFor("failX", new Class<?>[]{}, new Object[]{},
                new IllegalStateException("boom"));

        assertThrows(IllegalStateException.class, () -> aspect.around(pjp, annotationFor("failX")));

        ArgumentCaptor<AuditLog> cap = ArgumentCaptor.forClass(AuditLog.class);
        verify(svc).persist(cap.capture());
        AuditLog rec = cap.getValue();
        assertEquals("FAIL_X", rec.getAction());
        assertFalse(rec.isSuccess());
        assertTrue(rec.getDetail() != null && rec.getDetail().contains("boom"));
    }
}
