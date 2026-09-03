package com.billing.license.service.payment.util;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 表单参数解析工具（支付宝等渠道回调为 {@code application/x-www-form-urlencoded} 格式）。
 *
 * <p>集中维护 {@code key=value} 的拆分与 URL 解码规则，避免各策略类各自内联解析导致的
 * 解码/空值规则漂移（漂移会退化为「sign 取到了但待签串拼错」这类更难排查的缺陷）。
 * 与同包 {@link AmountValidator} 同构。
 */
public final class FormParamParser {

    private FormParamParser() {
    }

    /**
     * 解析 {@code application/x-www-form-urlencoded} 风格字符串为有序 Map。
     *
     * @param payload 形如 {@code a=1&b=2} 的串，可为 null 或空
     * @return 有序（保持出现顺序）键值表；null/空串返回空表
     */
    public static Map<String, String> parse(String payload) {
        Map<String, String> params = new LinkedHashMap<>();
        if (payload == null || payload.isEmpty()) {
            return params;
        }
        String[] pairs = payload.split("&");
        for (String pair : pairs) {
            if (pair.isEmpty()) {
                continue;
            }
            int idx = pair.indexOf('=');
            if (idx <= 0) {
                // 无 '=' 或 '=' 为首个字符：跳过无键片段，避免脏数据
                continue;
            }
            String key = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8);
            String value = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
            params.put(key, value);
        }
        return params;
    }
}
