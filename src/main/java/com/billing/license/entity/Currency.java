package com.billing.license.entity;

import com.billing.license.exception.BusinessException;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 货币枚举（ISO 4217 alpha-3）。
 *
 * <p>覆盖亚洲主要货币 + G20 主要货币，并承载最小货币单位（{@link #minorUnits()}）供金额换算
 * （如元→分）。
 *
 * <p>JSON 契约：序列化为大写 code（如 {@code "USD"}）；反序列化大小写不敏感
 * （{@link #fromCode(String)}），未知代码抛 {@link BusinessException}（映射为 400），
 * 从而在入口即校验币种、避免脏值流入领域。
 *
 * <p>边界：渠道回调的原始币种（{@code WebhookPayload.currency} / {@code PaymentEvent.currency}）
 * 仍以 String 承载，仅在本枚举入口处转换。
 */
public enum Currency {

    // ===== 亚洲主要货币 =====
    CNY(2),  // 人民币
    JPY(0),  // 日元（无小数位）
    KRW(0),  // 韩元（无小数位）
    HKD(2),  // 港币
    TWD(2),  // 新台币
    SGD(2),  // 新加坡元
    MYR(2),  // 马来西亚林吉特
    THB(2),  // 泰铢
    IDR(2),  // 印尼盾
    PHP(2),  // 菲律宾比索
    VND(0),  // 越南盾（无小数位）
    INR(2),  // 印度卢比

    // ===== G20 主要货币 =====
    USD(2),  // 美元
    EUR(2),  // 欧元
    GBP(2),  // 英镑
    CAD(2),  // 加元
    AUD(2),  // 澳元
    BRL(2),  // 巴西雷亚尔
    MXN(2),  // 墨西哥比索
    RUB(2),  // 卢布
    SAR(2),  // 沙特里亚尔
    TRY(2),  // 土耳其里拉
    ZAR(2),  // 南非兰特
    ARS(2);  // 阿根廷比索

    /** 最小货币单位对应的小数位数 */
    private final int minorUnits;

    Currency(int minorUnits) {
        this.minorUnits = minorUnits;
    }

    /** ISO 4217 大写代码（即枚举名），用于 JSON 序列化与持久化存储 */
    @JsonValue
    public String code() {
        return name();
    }

    /** 最小货币单位的小数位数（JPY/KRW/VND=0，其余=2） */
    public int minorUnits() {
        return minorUnits;
    }

    /** 大小写不敏感解析；null/空白/未知代码返回 null（不抛错，用于宽松边界）。 */
    public static Currency fromCodeOrNull(String code) {
        if (code == null) {
            return null;
        }
        String c = code.trim().toUpperCase();
        if (c.isEmpty()) {
            return null;
        }
        try {
            return Currency.valueOf(c);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 大小写不敏感解析；null/空白返回 null，未知代码抛 {@link BusinessException}。
     * 作为 Jackson {@code @JsonCreator}，使非法币种在反序列化阶段即被拒绝（HTTP 400）。
     */
    @JsonCreator
    public static Currency fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        Currency c = fromCodeOrNull(code);
        if (c == null) {
            throw new BusinessException("UNSUPPORTED_CURRENCY", "不支持的货币代码：" + code);
        }
        return c;
    }
}
