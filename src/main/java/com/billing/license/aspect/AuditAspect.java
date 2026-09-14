package com.billing.license.aspect;

import com.billing.license.annotation.Audit;
import com.billing.license.entity.AuditLog;
import com.billing.license.service.AuditLogService;
import com.billing.license.util.KeyHashUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 审计切面（方案 B）：拦截 {@link Audit} 标注的方法，统一组装上下文（操作者密钥哈希、IP、UA）
 * 并调用 {@link AuditLogService} 同时写 AUDIT logger 与异步落库。success 语义为「抛异常 = FAIL」。
 */
@Slf4j
@Aspect
@Component
public class AuditAspect {

    private final AuditLogService auditLogService;
    private final ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();
    private final ExpressionParser spelParser = new SpelExpressionParser();

    public AuditAspect(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    @Around("@annotation(audit)")
    public Object around(ProceedingJoinPoint pjp, Audit audit) throws Throwable {
        String actor = resolveActor(audit);
        String action = audit.action().isEmpty()
                ? pjp.getSignature().getName().toUpperCase() : audit.action();
        String target = evalSpel(audit.target(), pjp);
        String ip = currentIp();
        String ua = currentUserAgent();

        boolean success = false;
        String detail = "";
        try {
            Object result = pjp.proceed();
            success = true;
            detail = evalSpel(audit.detail(), pjp);
            return result;
        } catch (Throwable t) {
            success = false;
            String base = evalSpel(audit.detail(), pjp);
            detail = (base == null || base.isEmpty() ? "" : base + " | ")
                    + (t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
            throw t;
        } finally {
            auditLogService.audit(actor, action, target, success, detail);
            auditLogService.persist(AuditLog.builder()
                    .actor(actor).action(action).target(target)
                    .success(success).detail(detail).ip(ip).userAgent(ua).build());
        }
    }

    private String resolveActor(Audit audit) {
        if (!audit.actor().isEmpty()) {
            return audit.actor();
        }
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return "anonymous";
        }
        HttpServletRequest req = attrs.getRequest();
        String key = req.getHeader("X-Admin-API-Key");
        if (key == null || key.isEmpty()) {
            key = req.getHeader("X-API-Key");
        }
        if (key == null || key.isEmpty()) {
            return "anonymous";
        }
        return KeyHashUtil.actorHash(key);
    }

    private String currentIp() {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return attrs != null && attrs.getRequest().getRemoteAddr() != null
                ? attrs.getRequest().getRemoteAddr() : null;
    }

    private String currentUserAgent() {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return attrs != null ? attrs.getRequest().getHeader("User-Agent") : null;
    }

    private String evalSpel(String expr, ProceedingJoinPoint pjp) {
        if (expr == null || expr.isBlank() || !expr.contains("#")) {
            return expr == null ? "" : expr;
        }
        try {
            MethodSignature ms = (MethodSignature) pjp.getSignature();
            EvaluationContext ctx = new StandardEvaluationContext();
            String[] names = parameterNameDiscoverer.getParameterNames(ms.getMethod());
            Object[] args = pjp.getArgs();
            if (names != null) {
                for (int i = 0; i < names.length; i++) {
                    ctx.setVariable(names[i], args[i]);
                }
            }
            Object v = spelParser.parseExpression(expr).getValue(ctx);
            return v == null ? "" : v.toString();
        } catch (Exception e) {
            log.debug("审计 SpEL 解析失败，回退原表达式：{}", expr, e);
            return expr;
        }
    }
}
