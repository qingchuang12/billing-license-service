package com.billing.license;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Swagger/OpenAPI 可用性门禁 + 诊断：以 test profile（H2）启动真实 Web 服务，
 * 断言文档端点可访问、已扫描到 controller、注解生效；
 * 并核查 swagger-config / swagger-initializer（决定 UI 加载哪个 JSON）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class OpenApiDocAvailabilityTest {

    @Autowired
    private Environment environment;

    private HttpResponse<String> get(String path) throws Exception {
        int port = Integer.parseInt(environment.getProperty("local.server.port"));
        HttpClient client = HttpClient.newHttpClient();
        return client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void apiDocs_shouldRenderAllControllers_withAnnotations() throws Exception {
        HttpResponse<String> docs = get("/v3/api-docs");
        String body = docs.body();

        assertEquals(200, docs.statusCode(), "/v3/api-docs 应可访问");
        assertTrue(body.contains("\"paths\""), "应含 paths");
        int pathCount = body.split("\"/api/", -1).length - 1;
        assertTrue(pathCount >= 20, "应扫描到全部 controller 端点，实际约 " + pathCount);
        assertTrue(body.contains("\"tags\""), "应含 controller @Tag 分组");
        assertTrue(body.contains("\"summary\""), "应含 @Operation summary");

        assertEquals(200, get("/swagger-ui/index.html").statusCode(), "Swagger UI 首页应可访问");
    }

    @Test
    void swaggerConfig_mustNotBeWrappedByBusinessEnvelope() throws Exception {
        HttpResponse<String> cfg = get("/v3/api-docs/swagger-config");
        String body = cfg.body();
        assertEquals(200, cfg.statusCode(), "swagger-config 应可访问");
        // 必须暴露 swagger-ui 期望的顶层字段，否则 UI 会回退到 petstore 默认地址
        assertTrue(body.contains("\"configUrl\""), "swagger-config 应含顶层 configUrl");
        assertTrue(body.contains("\"url\""), "swagger-config 应含顶层 url");
        // 不得被统一响应壳（ApiResponseAdvice）包裹
        assertTrue(!body.contains("\"traceId\"") && !body.contains("\"success\""),
                "swagger-config 不应被业务响应壳包裹（否则 Swagger UI 加载 petstore 默认地址）");

        HttpResponse<String> init = get("/swagger-ui/swagger-initializer.js");
        assertEquals(200, init.statusCode(), "swagger-initializer.js 应可访问");
    }
}
