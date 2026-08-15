package com.billing.license.controller;

import com.billing.license.dto.RedeemCodeRequest;
import com.billing.license.entity.License;
import com.billing.license.service.RedeemCodeService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 兑换码控制器
 * 提供兑换码生成、兑换和撤销功能
 */
@RestController
@RequestMapping("/api/v1/redeem")
@RequiredArgsConstructor
public class RedeemCodeController {
    
    private final RedeemCodeService redeemCodeService;
    
    /**
     * 批量生成兑换码（管理员权限）
     * @param productSku 产品 SKU
     * @param count 生成数量
     * @param expiresAt 过期时间（可选）
     * @return 生成的兑换码数量
     */
    @PostMapping("/generate")
    public ResponseEntity<Map<String, Object>> generateCodes(
            @RequestParam String productSku,
            @RequestParam int count,
            @RequestParam(required = false) LocalDateTime expiresAt) {
        
        int created = redeemCodeService.generateCodes(productSku, count, expiresAt);
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("count", created);
        return ResponseEntity.ok(response);
    }
    
    /**
     * 兑换兑换码获取 License
     * @param request 兑换请求，包含兑换码和客户 ID
     * @return 兑换成功后返回 License 信息
     */
    @PostMapping("/redeem")
    public ResponseEntity<Map<String, Object>> redeemCode(
            @RequestBody RedeemCodeRequest request,
            HttpServletRequest httpRequest) {
        // 解析客户端真实 IP（支持反向代理 X-Forwarded-For）
        String clientIp = parseClientIp(httpRequest);
        if (request.getClientIp() == null) {
            request.setClientIp(clientIp);
        }
        License license = redeemCodeService.redeemCode(request);
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("licenseKey", license.getLicenseKey());
        response.put("signedToken", license.getSignedToken());
        response.put("expiresAt", license.getExpiresAt());
        return ResponseEntity.ok(response);
    }
    
    /**
     * 撤销兑换码（管理员权限）
     * @param code 要撤销的兑换码
     * @return 操作结果
     */
    @PostMapping("/revoke/{code}")
    public ResponseEntity<Map<String, Object>> revokeCode(@PathVariable String code) {
        redeemCodeService.revokeCode(code);
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        return ResponseEntity.ok(response);
    }

    /**
     * 解析客户端真实 IP（优先 X-Forwarded-For，其次 X-Real-IP，最后 remoteAddr）
     */
    private String parseClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isEmpty()) {
            // 取第一个（最原始客户端）
            return forwarded.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isEmpty()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }
}
