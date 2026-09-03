package com.billing.license.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Springdoc OpenAPI v2 配置。
 * 文档端点：/v3/api-docs（JSON）、/swagger-ui.html（UI）。
 * 管理端接口需携带 X-API-Key，这里声明为全局 API Key 安全方案，便于在 Swagger UI 中填写。
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
                        .contact(new Contact().name("Billing Team").email("support@company.com")))
                .components(new Components().addSecuritySchemes("X-API-Key",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-API-Key")
                                .scheme("X-API-Key")))
                .addSecurityItem(new SecurityRequirement().addList("X-API-Key"));
    }
}
