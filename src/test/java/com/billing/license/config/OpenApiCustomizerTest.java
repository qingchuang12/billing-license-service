package com.billing.license.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import org.junit.jupiter.api.Test;
import org.springdoc.core.customizers.OpenApiCustomizer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * D3 验证（无需数据库）：直接驱动 {@link OpenApiConfig#billingSecurityCustomizer()}，
 * 断言各端点 security 字段与真实鉴权模型（SecurityConfig）一致。
 */
class OpenApiCustomizerTest {

    /** 自建引用 Map：持有与 OpenAPI 中相同的 PathItem 实例，绕开 swagger Paths 对 {…} 模板键取回不稳定的问题。 */
    private final Map<String, PathItem> refPaths = new LinkedHashMap<>();

    private OpenApiCustomizer customizer() {
        return new OpenApiConfig().billingSecurityCustomizer();
    }

    private OpenAPI withPaths(String... paths) {
        refPaths.clear();
        Paths p = new Paths();
        for (String path : paths) {
            PathItem item = new PathItem();
            item.setGet(new Operation());
            refPaths.put(path, item);
            p.put(path, item);
        }
        return new OpenAPI().paths(p);
    }

    /** 取某路径 GET 操作第一个安全项的 scheme 名；无安全项返回 ""。
     *  SecurityRequirement 是 LinkedHashMap<String,List<String>>，scheme 即 key。
     *  直接读自建引用 Map 中的 PathItem（customizer 原地 setSecurity 的是同一实例）。 */
    private String onlyScheme(OpenAPI api, String path) {
        PathItem item = refPaths.get(path);
        if (item == null) {
            return "<<MISSING>>";
        }
        List<SecurityRequirement> sec = item.getGet().getSecurity();
        if (sec == null || sec.isEmpty()) {
            return "";
        }
        return sec.get(0).keySet().iterator().next();
    }

    @Test
    void adminEndpoint_requiresBearer() {
        // plan-6.0 / A12（2026-09-23）：X-API-Key 通道已移除，管理端改由管理员 JWT（Bearer）访问
        OpenAPI api = withPaths("/api/admin/orders", "/api/admin/licenses/abc/revoke");
        customizer().customise(api);
        assertEquals("Bearer", onlyScheme(api, "/api/admin/orders"));
        assertEquals("Bearer", onlyScheme(api, "/api/admin/licenses/abc/revoke"));
    }

    @Test
    void privilegedEndpoint_requiresBearer() {
        // plan-6.0 / A12（2026-09-23）：特权端点改由 Bearer JWT（ROLE_ADMIN）保护
        OpenAPI api = withPaths(
                "/api/licenses/list",
                "/api/orders/123",
                "/api/redeem/generate",
                "/api/redeem/revoke/k");
        customizer().customise(api);
        assertEquals("Bearer", onlyScheme(api, "/api/licenses/list"));
        assertEquals("Bearer", onlyScheme(api, "/api/orders/123"));
        assertEquals("Bearer", onlyScheme(api, "/api/redeem/generate"));
        assertEquals("Bearer", onlyScheme(api, "/api/redeem/revoke/k"));
    }

    @Test
    void publicEndpoint_noSecurity() {
        OpenAPI api = withPaths(
                "/api/checkout/session",
                "/api/webhooks/stripe",
                "/api/licenses/verify/abc",
                "/api/redeem/redeem");
        customizer().customise(api);
        assertEquals("", onlyScheme(api, "/api/checkout/session"));
        assertEquals("", onlyScheme(api, "/api/webhooks/stripe"));
        assertEquals("", onlyScheme(api, "/api/licenses/verify/abc"));
        assertEquals("", onlyScheme(api, "/api/redeem/redeem"));
    }

    @Test
    void verifyExcludedFromPrivileged() {
        // /api/licenses/verify 必须按公开处理，不能误判为特权 Bearer
        // 用字面量子路径验证（customizer 以 startsWith 判定，与模板/字面量无关）
        OpenAPI api = withPaths("/api/licenses/verify/abc");
        customizer().customise(api);
        assertEquals("", onlyScheme(api, "/api/licenses/verify/abc"));
    }
}
