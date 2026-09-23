package com.billing.license.security;

import com.billing.license.config.AccountProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;

/**
 * TOTP 实现（RFC 6238），plan-7.0 / M2。
 *
 * <p><b>为什么手写而不引依赖</b>：本项目构建为<b>离线</b>（Maven {@code -o}），
 * 且本地仓实查<b>无任何 TOTP 类库</b>（{@code ~/.m2} 全盘无命中）——引新依赖不可行。
 * RFC 6238 的算法本体只是「HMAC-SHA1 + 动态截断 + Base32」，约 40 行，
 * 与其引依赖不如手写，反而零供应链风险。
 *
 * <p><b>正确性依据</b>：{@link #hotp} 的期望值不靠自证——由
 * {@code TotpServiceTest} 直接引用 <b>RFC 6238 附录 B 官方测试向量</b>
 * （密钥 ASCII {@code "12345678901234567890"}，X=30，T0=0，8 位码）逐条断言。
 *
 * <p><b>防重放</b>：RFC 6238 §5.2 明确要求「the verifier MUST NOT accept the second
 * attempt of the OTP after the successful validation has been issued for the first OTP」。
 * 故 {@link #verify} 接收调用方传入的 {@code lastUsedStep}，<b>只接受严格大于它的时间步</b>，
 * 并由调用方把命中的步持久化。
 */
@Component
public class TotpService {

    /** RFC 4648 Base32 字母表（认证器 App 通用，无填充） */
    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    /** 密钥字节数：RFC 4226 §4 建议至少 128 位、SHA-1 输出长度 160 位为佳 */
    private static final int SECRET_BYTES = 20;

    /** 输出位数（认证器 App 通行值） */
    public static final int DIGITS = 6;

    private static final int[] DIGITS_POWER =
        {1, 10, 100, 1000, 10000, 100000, 1000000, 10000000, 100000000};

    private final AccountProperties.Mfa config;
    private final SecureRandom random = new SecureRandom();

    public TotpService(AccountProperties properties) {
        this.config = properties.getMfa();
    }

    /** 生成新密钥（Base32，无填充），供 otpauth URI 与认证器手动录入使用 */
    public String generateSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        random.nextBytes(bytes);
        return base32Encode(bytes);
    }

    /** 当前时间步 = floor(Unix 秒 / 步长)，RFC 6238 §4.2 */
    public long currentStep() {
        return Instant.now().getEpochSecond() / config.getTotpStepSeconds();
    }

    /** 计算指定时间步的 6 位动态码 */
    public String code(String base32Secret, long step) {
        return hotp(base32Decode(base32Secret), step, DIGITS);
    }

    /**
     * 校验动态码，返回命中的时间步；不匹配返回 {@code null}。
     *
     * <p>容错窗口取配置的 {@code totpWindowSteps}（默认前后各 1 步，约 ±30 秒），
     * 与 RFC 6238 §5.2「at most one time step is allowed as the network delay」一致。
     * 严格跳过 {@code step <= lastUsedStep} 的候选，实现一次性语义。
     *
     * @param lastUsedStep 该账号上一个成功使用的时间步；{@code null} 表示从未使用
     */
    public Long verify(String base32Secret, String input, Long lastUsedStep) {
        if (base32Secret == null || input == null) {
            return null;
        }
        String normalized = input.trim();
        if (normalized.length() != DIGITS
            || !normalized.chars().allMatch(Character::isDigit)) {
            return null;
        }
        long current = currentStep();
        int window = Math.max(0, config.getTotpWindowSteps());
        // 从远期往近期扫，命中即取——远期步优先可保证被记录的 lastUsedStep 单调前推
        for (long step = current + window; step >= current - window; step--) {
            if (step < 0) {
                continue;
            }
            if (lastUsedStep != null && step <= lastUsedStep) {
                continue;
            }
            if (constantTimeEquals(hotp(base32Decode(base32Secret), step, DIGITS), normalized)) {
                return step;
            }
        }
        return null;
    }

    /**
     * 构造 {@code otpauth://} URI（Google Authenticator Key URI Format）。
     *
     * <p>本项目的静态页<b>零 CDN、无构建链</b>，无法引入二维码库，故改为展示本 URI
     * 与 Base32 密钥文本，由用户手动录入认证器（各主流认证器均支持手动输入）。
     */
    public String otpauthUri(String account, String base32Secret) {
        String issuer = config.getIssuer();
        String label = encode(issuer) + ":" + encode(account);
        return "otpauth://totp/" + label
            + "?secret=" + base32Secret
            + "&issuer=" + encode(issuer)
            + "&algorithm=SHA1"
            + "&digits=" + DIGITS
            + "&period=" + config.getTotpStepSeconds();
    }

    // ==================== HOTP 内核（RFC 4226） ====================

    /**
     * HOTP：{@code Truncate(HMAC-SHA1(K, C))}，动态截断见 RFC 4226 §5.3。
     *
     * <p>包级可见以便测试直接传入 RFC 附录 B 的 ASCII 密钥字节，避免「先编码再解码」的
     * 自证循环。
     */
    static String hotp(byte[] key, long counter, int digits) {
        byte[] message = new byte[8];
        for (int i = 7; i >= 0; i--) {
            message[i] = (byte) (counter & 0xff);
            counter >>>= 8;
        }
        byte[] hash = hmacSha1(key, message);
        int offset = hash[hash.length - 1] & 0x0f;
        int binary = ((hash[offset] & 0x7f) << 24)
            | ((hash[offset + 1] & 0xff) << 16)
            | ((hash[offset + 2] & 0xff) << 8)
            | (hash[offset + 3] & 0xff);
        int otp = binary % DIGITS_POWER[digits];
        // 必须左补零：官方向量中存在 "07081804" 这类前导零
        return String.format("%0" + digits + "d", otp);
    }

    private static byte[] hmacSha1(byte[] key, byte[] message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            return mac.doFinal(message);
        } catch (Exception e) {
            // HmacSHA1 是 JDK 必备算法，走到这里说明运行环境异常，属启动期问题
            throw new IllegalStateException("HMAC-SHA1 不可用", e);
        }
    }

    /** 定长比较，避免按字符短路泄漏动态码前缀（配合限流使用） */
    private static boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.US_ASCII),
            actual.getBytes(StandardCharsets.US_ASCII));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    // ==================== Base32（RFC 4648） ====================

    static String base32Encode(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buffer = 0;
        int bitsLeft = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                sb.append(BASE32_ALPHABET.charAt((buffer >> (bitsLeft - 5)) & 0x1f));
                bitsLeft -= 5;
            }
        }
        if (bitsLeft > 0) {
            sb.append(BASE32_ALPHABET.charAt((buffer << (5 - bitsLeft)) & 0x1f));
        }
        return sb.toString();
    }

    static byte[] base32Decode(String encoded) {
        // 容忍小写与填充：用户从认证器/URI 复制过来的串可能带 '=' 或大小写不一
        String input = encoded.trim().replace("=", "").toUpperCase(Locale.ROOT);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int buffer = 0;
        int bitsLeft = 0;
        for (int i = 0; i < input.length(); i++) {
            int value = BASE32_ALPHABET.indexOf(input.charAt(i));
            if (value < 0) {
                throw new IllegalArgumentException("非法的 Base32 字符：" + input.charAt(i));
            }
            buffer = (buffer << 5) | value;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                out.write((buffer >> (bitsLeft - 8)) & 0xff);
                bitsLeft -= 8;
            }
        }
        return out.toByteArray();
    }
}
