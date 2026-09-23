package com.billing.license.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.BooleanSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collections;
import java.util.List;

/**
 * Springdoc OpenAPI v3 配置。
 * 文档端点：/v3/api-docs（JSON）、/swagger-ui.html（UI）。
 *
 * <p>安全标识（plan-6.0 / A12）：真实鉴权分两档（见 {@code SecurityConfig}），
 * 故此处不再使用全局 {@code addSecurityItem}，而是声明一个 Bearer JWT 方案，
 * 由 {@link #billingSecurityCustomizer()} 按路径前缀逐接口映射，与 {@code SecurityConfig}
 * 的 {@code authorizeHttpRequests} 保持同源，避免文档与实际鉴权漂移。
 * X-API-Key 通道已于 2026-09-23 移除，管理端改由管理员 JWT 访问。
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI billingOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Billing & License Service API")
                        .version("1.0.0")
                        .description("统一计费与许可证管理服务的 OpenAPI 文档（三档收费：Pro 买断 / Pro Plus / 订阅制；5 家支付渠道）")
                        .contact(new Contact().name("Billing Team").email("service@ywhome.top")))
                .components(new Components()
                        .addSecuritySchemes("Bearer",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")));
    }

    /**
     * 按实际安全模型逐路径设置安全项（与 {@code SecurityConfig} 同源）。
     * 公开端点清空安全约束；管理端 / 特权端点要求 Bearer JWT（ROLE_ADMIN）。
     */
    @Bean
    public OpenApiCustomizer billingSecurityCustomizer() {
        return openApi -> {
            Paths paths = openApi.getPaths();
            if (paths == null) {
                return;
            }
            paths.forEach((path, item) -> {
                if (item == null) {
                    return;
                }
                List<Operation> operations = item.readOperations();
                if (operations == null || operations.isEmpty()) {
                    return;
                }
                if (isPublic(path)) {
                    operations.forEach(op -> op.setSecurity(Collections.emptyList()));
                } else if (requiresAdminOrPrivileged(path)) {
                    operations.forEach(op -> op.setSecurity(
                            List.of(new SecurityRequirement().addList("Bearer"))));
                } else {
                    // 其余路径（SecurityConfig 默认 denyAll，不会被暴露）保持无安全项
                    operations.forEach(op -> op.setSecurity(Collections.emptyList()));
                }
            });
        };
    }

    /** 公开端点（无需鉴权）：收银台、支付回调、License 校验、兑换码兑换。 */
    private static boolean isPublic(String path) {
        return path.startsWith("/api/checkout/")
                || path.startsWith("/api/webhooks/")
                || path.startsWith("/api/licenses/verify/")
                || path.equals("/api/redeem/redeem");
    }

    /** 需 Bearer JWT（ROLE_ADMIN）的端点：管理端全部 + 其余特权端点。注意 licenses/verify 已按公开处理。 */
    private static boolean requiresAdminOrPrivileged(String path) {
        return path.startsWith("/api/admin/")
                || path.startsWith("/api/licenses/")
                || path.startsWith("/api/orders/")
                || path.startsWith("/api/redeem/generate")
                || path.startsWith("/api/redeem/revoke/");
    }

    /**
     * H-C3：让 OpenAPI 文档反映统一响应壳。
     *
     * <p>运行时除 {@code /api/webhooks/**}（渠道原始报文，必须原样返回）以外的响应都会被
     * {@code ApiResponseAdvice} 包成 {@code ApiResponse{success, code, message, data, traceId, timestamp}}；
     * 若文档仍声明为裸 DTO，客户端按文档生成 SDK 必然解析失败。此 customizer 把响应 schema
     * 包装为同一结构，使「文档 = 实际」。
     */
    @Bean
    public OpenApiCustomizer billingEnvelopeCustomizer() {
        return openApi -> {
            Paths paths = openApi.getPaths();
            if (paths == null) {
                return;
            }
            paths.forEach((path, item) -> {
                if (item == null || path.startsWith("/api/webhooks/")) {
                    return;
                }
                for (Operation op : item.readOperations()) {
                    if (op.getResponses() == null) {
                        return;
                    }
                    op.getResponses().forEach((code, response) -> wrapWithEnvelope(code, response));
                }
            });
        };
    }

    /** 把单个响应的 schema 包装为统一响应壳（已是壳或无 schema 时不重复包装）。 */
    private static void wrapWithEnvelope(String statusCode,
                                         io.swagger.v3.oas.models.responses.ApiResponse response) {
        if (response == null || response.getContent() == null) {
            return;
        }
        boolean success = statusCode != null && statusCode.startsWith("2");
        response.getContent().forEach((mediaType, mt) -> {
            Schema<?> data = mt.getSchema();
            if (data != null && isEnvelope(data)) {
                return;
            }
            mt.setSchema(envelope(data, success));
        });
    }

    private static boolean isEnvelope(Schema<?> schema) {
        return schema.getProperties() != null && schema.getProperties().containsKey("success");
    }

    private static Schema<?> envelope(Schema<?> data, boolean success) {
        return new ObjectSchema()
                .description("统一响应壳（运行时由 ApiResponseAdvice 包裹）")
                .addProperty("success", new BooleanSchema().description("是否成功"))
                .addProperty("code", new StringSchema().description("业务码：成功为 SUCCESS，失败为业务错误码"))
                .addProperty("message", new StringSchema().description("错误描述，成功时为 null"))
                .addProperty("data", data != null
                        ? data
                        : new ObjectSchema().description(success ? "业务数据" : "错误时为 null"))
                .addProperty("traceId", new StringSchema().description("请求追踪 ID（响应头 X-Trace-Id）"))
                .addProperty("timestamp", new StringSchema().description("响应时间（ISO-8601）"));
    }
}
