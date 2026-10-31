package com.billing.license.config;

import com.billing.license.infrastructure.kms.KmsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 生产启动自检：把「能启动但真实链路不可用」的配置错误在启动期显性化。
 *
 * <p>覆盖三类历史坑：
 * <ul>
 *   <li>{@code APP_BASE_URL} 指向 localhost/非 HTTPS → 渠道回调不可达；</li>
 *   <li>验证码 pepper 为空 → 库被读后 6 位验证码可被彩虹表反查；</li>
 *   <li>License 私钥延迟加载 → 健康检查全绿，但第一笔支付后签发失败（收钱不发货）。</li>
 * </ul>
 */
@Slf4j
@Component
@Profile("prod")
@RequiredArgsConstructor
public class ProductionConfigValidator implements ApplicationRunner {

    private final AccountProperties accountProperties;
    private final KmsService kmsService;

    @Value("${app.base-url:}")
    private String appBaseUrl;

    @Override
    public void run(ApplicationArguments args) {
        List<String> errors = new ArrayList<>();

        if (isBlank(appBaseUrl)
                || !appBaseUrl.startsWith("https://")
                || appBaseUrl.contains("localhost")
                || appBaseUrl.contains("127.0.0.1")) {
            errors.add("app.base-url 必须是公网 HTTPS 地址，当前=" + (isBlank(appBaseUrl) ? "(空)" : appBaseUrl));
        }

        if (isBlank(accountProperties.getCodePepper()) || accountProperties.getCodePepper().length() < 16) {
            errors.add("account.code-pepper 必须配置且长度≥16（ACCOUNT_CODE_PEPPER）");
        }

        byte[] probe = "billing-license-startup-probe".getBytes(StandardCharsets.UTF_8);
        try {
            byte[] signature = kmsService.sign(probe);
            if (!kmsService.verify(probe, signature)) {
                errors.add("License 签名密钥自检失败：签名无法被对应公钥验证（私钥/公钥不配对或 kid 配错）");
            }
        } catch (Exception e) {
            errors.add("License 签名密钥加载/签名失败：" + e.getClass().getSimpleName()
                + " - " + e.getMessage());
        }

        if (!errors.isEmpty()) {
            String message = "生产配置自检失败，拒绝启动：\n- " + String.join("\n- ", errors);
            log.error("{}", message);
            throw new IllegalStateException(message);
        }
        log.info("生产配置自检通过：base-url={}, code-pepper=已配置, license-algorithm={}",
            appBaseUrl, kmsService.getAlgorithm());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
