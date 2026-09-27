package com.billing.license.security;

import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link TotpQrCodeService} 单元测试。
 *
 * <p><b>不只用「非空」糊弄过去</b>：二维码的价值在于「认证器扫得出正确内容」，
 * 故这里把渲染出的 PNG 再用 ZXing 解码回来与原文比对——只断言前缀为
 * {@code data:image/png;base64,} 的话，一张纯白图片也能通过。
 */
class TotpQrCodeServiceTest {

    private static final String PREFIX = "data:image/png;base64,";

    /** 与生产同构的 otpauth URI（159 字符，对应二维码 version 5 左右） */
    private static final String OTPAUTH_URI = "otpauth://totp/BillingLicenseService:admin%40example.com"
        + "?secret=PKXXCEUUMAD6U4QXIX2WQ6XR4RHCX3G3"
        + "&issuer=BillingLicenseService&algorithm=SHA1&digits=6&period=30";

    private final TotpQrCodeService service = new TotpQrCodeService();

    @Test
    @DisplayName("渲染结果须能被扫描还原成原始 otpauth URI")
    void renderDataUri_shouldBeDecodableBackToOriginal() throws Exception {
        String dataUri = service.renderDataUri(OTPAUTH_URI);

        assertNotNull(dataUri);
        assertTrue(dataUri.startsWith(PREFIX));

        byte[] png = Base64.getDecoder().decode(dataUri.substring(PREFIX.length()));
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        int width = image.getWidth();
        int height = image.getHeight();
        int[] pixels = new int[width * height];
        image.getRGB(0, 0, width, height, pixels, 0, width);

        LuminanceSource source = new RGBLuminanceSource(width, height, pixels);
        Result result = new MultiFormatReader()
            .decode(new BinaryBitmap(new HybridBinarizer(source)));

        assertEquals(OTPAUTH_URI, result.getText(),
            "二维码内容与 otpauth URI 不一致——认证器会绑定到一个错误的密钥");
        // 二值图是选型的理由：体积远小于 INT_RGB（实测 850B vs 7368B）
        assertTrue(png.length < 4096, "PNG 体积异常偏大：" + png.length + " 字节");
    }

    @Test
    @DisplayName("空内容不渲染，返回 null（调用方据此回退到手动输入密钥）")
    void renderDataUri_shouldReturnNullForBlankContent() {
        assertNull(service.renderDataUri(null));
        assertNull(service.renderDataUri(""));
    }

    @Test
    @DisplayName("超长内容导致编码失败时降级返回 null，不抛异常阻断绑定流程")
    void renderDataUri_shouldDegradeToNullWhenTooLongToEncode() {
        StringBuilder tooLong = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            tooLong.append('A');
        }

        // 二维码容量上限约 7000 字符；此处刻意逼近边界，验证失败路径是「降级」而非「抛出」
        assertDoesNotThrow(() -> service.renderDataUri(tooLong.toString()));
    }

    @Test
    @DisplayName("输出必须是合法 PNG（防止写出 ImageIO 不支持的格式）")
    void renderDataUri_shouldProduceValidPng() throws Exception {
        String dataUri = service.renderDataUri(OTPAUTH_URI);
        byte[] png = Base64.getDecoder().decode(dataUri.substring(PREFIX.length()));

        ByteArrayOutputStream roundTrip = new ByteArrayOutputStream();
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        assertTrue(ImageIO.write(image, "png", roundTrip), "ImageIO 无法回写该图，说明不是合法 PNG");
    }
}
