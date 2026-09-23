package com.billing.license.security;

import com.billing.license.config.AccountProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TOTP 实现验证（plan-7.0 / M6）。
 *
 * <p><b>期望值来源</b>：全部取自 <b>RFC 6238 附录 B 官方测试向量</b>
 * （<a href="https://www.rfc-editor.org/rfc/rfc6238.txt">rfc6238.txt</a>），
 * 而非「跑一遍看输出是什么就写什么」——后者是自证，任何实现（包括错的）都能通过。
 * 官方向量固定了密钥（ASCII {@code "12345678901234567890"}）、时间步 X=30、T0=0、
 * 8 位输出，因此对实现构成真正的独立约束。
 */
class TotpServiceTest {

    /** RFC 6238 附录 B 的共享密钥（ASCII 20 字节） */
    private static final byte[] RFC_SEED_SHA1 =
        "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    private final TotpService totpService = new TotpService(new AccountProperties());

    // ==================== RFC 6238 附录 B 官方向量 ====================

    @Test
    @DisplayName("HOTP 内核逐条匹配 RFC 6238 附录 B 的 6 个 SHA1 官方向量")
    void hotp_shouldMatchRfc6238AppendixB() {
        // 时间(秒) → TOTP(8 位)，逐字抄自 RFC 6238 附录 B 表格
        long[] times = {59L, 1111111109L, 1111111111L, 1234567890L, 2000000000L, 20000000000L};
        String[] expected = {"94287082", "07081804", "14050471", "89005924", "69279037", "65353130"};

        for (int i = 0; i < times.length; i++) {
            long step = times[i] / 30;
            assertEquals(expected[i], TotpService.hotp(RFC_SEED_SHA1, step, 8),
                "RFC 6238 附录 B 向量不符：Time=" + times[i] + "（T=" + step + "）");
        }
    }

    @Test
    @DisplayName("附录 B 的 '07081804' 带前导零，验证输出必须左补零到指定位数")
    void hotp_shouldLeftPadWithZeros() {
        // T=37037036 的官方期望值是 "07081804"——若实现漏了左补零会得到 7 位串
        String code = TotpService.hotp(RFC_SEED_SHA1, 37037036L, 8);
        assertEquals(8, code.length());
        assertEquals("07081804", code);
    }

    // ==================== Base32（RFC 4648） ====================

    @Test
    @DisplayName("Base32 编解码往返保持字节一致")
    void base32_shouldRoundTrip() {
        byte[] original = new byte[64];
        for (int i = 0; i < original.length; i++) {
            original[i] = (byte) (i * 7 + 3);
        }
        byte[] restored = TotpService.base32Decode(TotpService.base32Encode(original));
        assertEquals(original.length, restored.length);
        for (int i = 0; i < original.length; i++) {
            assertEquals(original[i], restored[i], "第 " + i + " 字节不一致");
        }
    }

    @Test
    @DisplayName("Base32 解码容忍小写与 '=' 填充（用户从 URI 复制粘贴的常见形态）")
    void base32Decode_shouldTolerateCaseAndPadding() {
        byte[] data = "hello-totp".getBytes(StandardCharsets.UTF_8);
        String encoded = TotpService.base32Encode(data);

        assertEquals(encoded, TotpService.base32Encode(TotpService.base32Decode(encoded.toLowerCase())));
        assertEquals(encoded, TotpService.base32Encode(TotpService.base32Decode(encoded + "======")));
    }

    @Test
    @DisplayName("Base32 解码遇非法字符必须抛错，不静默产出错误密钥")
    void base32Decode_shouldRejectInvalidCharacter() {
        // '1' 与 '0' 不在 RFC 4648 字母表中（易与 I/O 混淆，故被排除）
        assertThrows(IllegalArgumentException.class, () -> TotpService.base32Decode("ABC1DEF"));
    }

    // ==================== 生成与校验 ====================

    @Test
    @DisplayName("生成的密钥为 20 字节（160 bit）Base32 串，且每次不同")
    void generateSecret_shouldBeRandomAndBase32() {
        String first = totpService.generateSecret();
        String second = totpService.generateSecret();

        assertNotNull(first);
        assertEquals(32, first.length(), "20 字节 Base32 编码后为 32 个字符");
        assertEquals(20, TotpService.base32Decode(first).length, "解码应还原 20 字节");
        assertTrue(first.chars().allMatch(c -> "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".indexOf(c) >= 0),
            "只应包含 RFC 4648 字母表字符");
        org.junit.jupiter.api.Assertions.assertNotEquals(first, second, "两次生成不应相同");
    }

    @Test
    @DisplayName("当前时间步的动态码可被校验通过，并返回该时间步")
    void verify_shouldAcceptCurrentStepCode() {
        String secret = totpService.generateSecret();
        long step = totpService.currentStep();
        String code = totpService.code(secret, step);

        assertEquals(6, code.length(), "默认输出 6 位");
        Long matched = totpService.verify(secret, code, null);

        assertNotNull(matched, "当前步的码必须校验通过");
        assertTrue(Math.abs(matched - step) <= 1, "命中的步应落在容错窗口内");
    }

    @Test
    @DisplayName("同一时间步的码用过一次后必须被拒（RFC 6238 §5.2 一次性要求）")
    void verify_shouldRejectReplayedStep() {
        String secret = totpService.generateSecret();
        long step = totpService.currentStep();
        String code = totpService.code(secret, step);

        Long first = totpService.verify(secret, code, null);
        assertNotNull(first, "首次校验应通过");

        // 把命中的步回传为 lastUsedStep 后，同码必须被拒——这是防「截获后在窗口内重放」的关键
        assertNull(totpService.verify(secret, code, first), "同一时间步的码不得二次接受");
    }

    @Test
    @DisplayName("容错窗口内（前后各 1 步）的码可通过，窗口外（±2 步）不可通过")
    void verify_shouldHonorWindowBoundary() {
        String secret = totpService.generateSecret();
        long step = totpService.currentStep();

        // 窗口内：±1 步
        assertNotNull(totpService.verify(secret, totpService.code(secret, step + 1), null),
            "后 1 步应在容错窗口内");
        assertNotNull(totpService.verify(secret, totpService.code(secret, step - 1), null),
            "前 1 步应在容错窗口内");
        // 窗口外：±2 步
        assertNull(totpService.verify(secret, totpService.code(secret, step + 2), null),
            "后 2 步超出容错窗口，必须拒绝");
        assertNull(totpService.verify(secret, totpService.code(secret, step - 2), null),
            "前 2 步超出容错窗口，必须拒绝");
    }

    @Test
    @DisplayName("畸形输入一律返回 null，不抛异常（避免把客户端输入错误变成 500）")
    void verify_shouldRejectMalformedInput() {
        String secret = totpService.generateSecret();
        String valid = totpService.code(secret, totpService.currentStep());

        assertNull(totpService.verify(secret, null, null), "null 输入");
        assertNull(totpService.verify(secret, "", null), "空串");
        assertNull(totpService.verify(secret, "12345", null), "位数不足");
        assertNull(totpService.verify(secret, "1234567", null), "位数过多");
        assertNull(totpService.verify(secret, "12345a", null), "含非数字");
        assertNull(totpService.verify(secret, valid.substring(0, 5) + "9", null), "正确长度但错误的码");
        assertNull(totpService.verify(null, valid, null), "密钥缺失");
    }

    @Test
    @DisplayName("校验前去除首尾空白（用户粘贴时常带空格）")
    void verify_shouldTrimWhitespace() {
        String secret = totpService.generateSecret();
        String code = totpService.code(secret, totpService.currentStep());

        assertNotNull(totpService.verify(secret, "  " + code + "  ", null));
    }

    // ==================== otpauth URI ====================

    @Test
    @DisplayName("otpauth URI 含密钥、签发者与标准参数，供认证器手动录入")
    void otpauthUri_shouldBeWellFormed() {
        String secret = totpService.generateSecret();
        String uri = totpService.otpauthUri("admin@example.com", secret);

        assertTrue(uri.startsWith("otpauth://totp/"), "缺少 scheme 与类型");
        assertTrue(uri.contains("secret=" + secret), "缺少密钥");
        assertTrue(uri.contains("issuer=BillingLicenseService"), "缺少签发者");
        assertTrue(uri.contains("algorithm=SHA1"), "应声明 SHA1 算法");
        assertTrue(uri.contains("digits=6"), "应声明 6 位");
        assertTrue(uri.contains("period=30"), "应声明 30 秒步长");
    }

    @Test
    @DisplayName("otpauth URI 对邮箱中的 '@' 做百分号编码，不破坏 URI 结构")
    void otpauthUri_shouldEncodeAccount() {
        String uri = totpService.otpauthUri("admin@example.com", totpService.generateSecret());

        // label 形如 issuer:account（冒号按 Google Authenticator Key URI 通例保留字面量，
        // 与官方示例 otpauth://totp/Example:alice@google.com 一致），账号部分须编码
        assertTrue(uri.contains("BillingLicenseService:admin%40example.com"),
            "label 应为「签发者:账号」，账号经百分号编码，实际为 " + uri);
    }
}
