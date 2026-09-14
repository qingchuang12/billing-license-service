package com.billing.license.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.*;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.junit.jupiter.api.Test;
import org.springdoc.core.customizers.OpenApiCustomizer;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * H-C3 验证（无需数据库）：直接驱动 {@link OpenApiConfig#billingEnvelopeCustomizer()}，
 * 断言文档响应 schema 与运行时统一响应壳（{@code ApiResponseAdvice}）一致：
 * 业务端点被包成 {@code {success, code, message, data, traceId, timestamp}}，
 * webhook 端点保持原始结构，已是壳的不重复包装。
 */
class OpenApiEnvelopeCustomizerTest {

    /** 自建引用 Map：持有与 OpenAPI 中相同的 PathItem 实例（customizer 原地修改）。 */
    private final Map<String, PathItem> refPaths = new LinkedHashMap<>();

    private OpenApiCustomizer customizer() {
        return new OpenApiConfig().billingEnvelopeCustomizer();
    }

    private OpenAPI withPath(String path, String statusCode, Schema<?> schema) {
        refPaths.clear();
        Paths p = new Paths();
        PathItem item = new PathItem();
        Operation op = new Operation();
        ApiResponse resp = new ApiResponse();
        if (schema != null) {
            resp.setContent(new Content().addMediaType("application/json",
                    new MediaType().schema(schema)));
        }
        op.setResponses(new ApiResponses().addApiResponse(statusCode, resp));
        item.setGet(op);
        refPaths.put(path, item);
        p.put(path, item);
        return new OpenAPI().paths(p);
    }

    private Schema<?> schemaOf(String path, String statusCode) {
        return refPaths.get(path).getGet().getResponses().get(statusCode)
                .getContent().get("application/json").getSchema();
    }

    @Test
    void businessEndpoint_shouldBeWrappedByEnvelope() {
        Schema<?> dto = new StringSchema();
        OpenAPI api = withPath("/api/licenses/customer/abc", "200", dto);

        customizer().customise(api);

        Schema<?> wrapped = schemaOf("/api/licenses/customer/abc", "200");
        assertNotNull(wrapped.getProperties());
        for (String field : new String[]{"success", "code", "message", "data", "traceId", "timestamp"}) {
            assertTrue(wrapped.getProperties().containsKey(field), "响应壳应含字段：" + field);
        }
        assertEquals(dto, wrapped.getProperties().get("data"), "原有业务 schema 应作为 data 保留");
    }

    @Test
    void webhookEndpoint_shouldNotBeWrapped() {
        Schema<?> raw = new StringSchema();
        OpenAPI api = withPath("/api/webhooks/stripe", "200", raw);

        customizer().customise(api);

        assertEquals(raw, schemaOf("/api/webhooks/stripe", "200"),
                "webhook 端点必须原样返回渠道报文，不得包壳");
    }

    @Test
    void alreadyEnveloped_shouldNotBeDoubleWrapped() {
        Schema<?> envelope = new ObjectSchema()
                .addProperty("success", new BooleanSchema())
                .addProperty("data", new ObjectSchema());
        OpenAPI api = withPath("/api/orders/123", "200", envelope);

        customizer().customise(api);

        assertEquals(envelope, schemaOf("/api/orders/123", "200"), "已是统一壳不应重复包装");
    }
}
