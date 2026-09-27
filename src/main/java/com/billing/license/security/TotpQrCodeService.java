package com.billing.license.security;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.EnumMap;
import java.util.Map;

/**
 * TOTP 绑定二维码渲染（plan-7.0）。
 *
 * <p><b>为什么必须本地渲染</b>：二维码内容就是 {@code otpauth://...?secret=<TOTP 密钥>}，
 * 交给任何在线二维码服务（{@code api.qrserver.com} / {@code quickchart.io} 之类）代渲染，
 * 等于把二次因子密钥明文发给第三方——密钥即刻失守，二次因子形同虚设。
 * 本服务一律本地出图，且不出网。
 *
 * <p><b>为什么用 PNG data URI 而不是 SVG</b>：实测同一份 otpauth URI（159 字符，240×240），
 * PNG（{@code TYPE_BYTE_BINARY}）850 字节 → base64 1136 字符；行合并优化后的 SVG 仍有 43KB。
 * 更小的还有额外好处：前端只要 {@code <img src="data:...">}，不必 innerHTML 注入，零 XSS 面。
 *
 * <p><b>为什么图像类型是 {@code TYPE_BYTE_BINARY}</b>：二维码只有黑白两色，用 INT_RGB 写出的
 * PNG 是 7368 字节（base64 9824），二值图只有 850 字节，差 9 倍，识别能力完全相同。
 *
 * <p><b>为什么失败返回 null 而不是抛异常</b>：扫码只是「少敲 32 个字符」的便利，
 * 手动输入密钥这条路径始终存在。渲染失败（缺字体/AWT 受限等）不该让 {@code enroll} 整体失败，
 * 否则管理员会因为一张图片而拿不到密钥。故降级为告警 + null，由调用方决定是否展示。
 */
@Slf4j
@Component
public class TotpQrCodeService {

    private static final int SIZE = 240;
    private static final int MARGIN = 2;
    private static final String DATA_URI_PREFIX = "data:image/png;base64,";

    /**
     * 渲染为 PNG data URI；失败返回 {@code null}。
     *
     * @param content 二维码内容（即 {@link TotpService#otpauthUri} 产出的 otpauth URI）
     */
    public String renderDataUri(String content) {
        if (content == null || content.isEmpty()) {
            return null;
        }
        try {
            BitMatrix matrix = new MultiFormatWriter()
                .encode(content, BarcodeFormat.QR_CODE, SIZE, SIZE, hints());
            return DATA_URI_PREFIX + Base64.getEncoder().encodeToString(toPng(matrix));
        } catch (Exception e) {
            log.warn("二维码渲染失败，本次绑定改用手动输入密钥：contentLength={}", content.length(), e);
            return null;
        }
    }

    private static Map<EncodeHintType, ?> hints() {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.MARGIN, MARGIN);
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        return hints;
    }

    private static byte[] toPng(BitMatrix matrix) throws IOException {
        int width = matrix.getWidth();
        int height = matrix.getHeight();
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_BINARY);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, matrix.get(x, y) ? 0x000000 : 0xFFFFFF);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
