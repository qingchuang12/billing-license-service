package com.billing.license.service;

import com.billing.license.dto.ActivateRequest;
import com.billing.license.dto.ActivateResponse;
import com.billing.license.dto.RedeemCodeRequest;
import com.billing.license.entity.License;
import com.billing.license.entity.LicenseEvent;
import com.billing.license.entity.User;
import com.billing.license.exception.BusinessException;
import com.billing.license.repository.LicenseRepository;
import com.billing.license.repository.UserRepository;
import com.billing.license.service.risk.RateLimitService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 凭证激活统一入口（plan-7.0 方案 A）。
 *
 * <p><b>为什么要有这个类</b>：此前「绑定设备 + 拿到签名令牌」有四条入口
 * （购买直签、兑换码、在线校验、计划中的激活），客户端要认四种。本类收敛为
 * <b>一个端点 + 一套返回</b>，凭证类型由服务端按格式自动识别：
 *
 * <ul>
 *   <li><b>兑换码</b>（{@code RC-} 前缀）：整段委托 {@link RedeemCodeService#redeemCode}——
 *       沿用「持码即持有人」语义与既有 B9 幂等、IP 风控，<b>匿名可调</b>（离线/礼品场景不破坏）。</li>
 *   <li><b>许可证密钥</b>（其余）：<b>必须登录</b>。归属判定取登录用户 id
 *       （{@code License.customerId == users.id}），与「我的授权」页同一套身份口径。</li>
 * </ul>
 *
 * <p><b>安全红线</b>：密钥分支<b>绝不</b>允许匿名绑定。账号页会明文展示 licenseKey，
 * 若「仅凭 key 即可绑」成立，明文泄漏即等于「谁先抢到算谁的」。归属校验取登录身份后，
 * 明文 key <b>单独</b>泄漏不足以完成绑定（还需同时持有受害人登录态）。
 *
 * <p>绑定与签名统一走 {@link LicenseService#bindToMachine} 内核，本类不自行签发。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CredentialBindingService {

    /** 兑换码前缀：与 {@link RedeemCodeService} 生成的码形一致（如 {@code RC-8F3C-1D2E-9A4B}）。
     *  许可证密钥是 4 段十六进制（A-F/0-9），不可能出现 {@code RC-}，故前缀判定无歧义。 */
    private static final String REDEEM_CODE_PREFIX = "RC-";

    private final LicenseRepository licenseRepository;
    private final UserRepository userRepository;
    private final RedeemCodeService redeemCodeService;
    private final LicenseService licenseService;
    private final RateLimitService rateLimitService;

    /**
     * 统一激活：按凭证格式分流。
     *
     * @param currentUserId 当前登录用户 id；未登录为 {@code null}（仅兑换码分支允许，密钥分支拒绝）
     */
    @Transactional
    public ActivateResponse activate(ActivateRequest request, UUID currentUserId) {
        String credential = request.getCredential() == null ? "" : request.getCredential().trim();
        String machineId = request.getMachineId() == null ? "" : request.getMachineId().trim();
        if (credential.isEmpty()) {
            throw new BusinessException("CREDENTIAL_REQUIRED", "credential is required");
        }
        if (machineId.isEmpty()) {
            throw new BusinessException("MACHINE_ID_REQUIRED", "machineId is required");
        }

        if (isRedeemCode(credential)) {
            // 整段委托兑换服务：B9 幂等与 IP 限流/失败计数（checkRedeemIp / recordRedeemFailure）
            // 已在其中，此处**不得**重复计数，否则阈值会被打对折。
            RedeemCodeRequest redeem = RedeemCodeRequest.builder()
                .code(credential)
                .machineId(machineId)
                .customerEmail(resolveEmail(request, currentUserId))
                .clientIp(request.getClientIp())
                .build();
            return ActivateResponse.from(redeemCodeService.redeemCode(redeem));
        }

        return activateByLicenseKey(credential, machineId, currentUserId, request.getClientIp());
    }

    /**
     * 密钥分支：登录态 + 归属 + 幂等 + 换机边界。
     */
    private ActivateResponse activateByLicenseKey(String licenseKey, String machineId,
                                                  UUID currentUserId, String clientIp) {
        // A5：必须登录。未登录引导先登录，绝不放行匿名绑定。
        if (currentUserId == null) {
            throw new BusinessException("LOGIN_REQUIRED",
                "Activating a license key requires sign-in");
        }
        checkIpLimit(clientIp);
        try {
            // 归属不匹配与密钥不存在**同码返回**，不泄露他人 License 是否存在。
            // B4（2026-09-23）：本码与兑换码分支的「凭证不认识」已统一为 CREDENTIAL_NOT_FOUND
            // （`RedeemCodeService.doRedeem` 同名同码），客户端/集成方对接两个端点时只需认一个码。
            License license = licenseRepository.findByLicenseKey(licenseKey)
                .filter(l -> currentUserId.equals(l.getCustomerId()))
                .orElseThrow(() -> new BusinessException("CREDENTIAL_NOT_FOUND", "Credential is invalid"));

            if (license.getStatus() != License.LicenseStatus.ACTIVE) {
                throw new BusinessException("LICENSE_NOT_ACTIVE",
                    "License is not active: " + license.getStatus().name());
            }

            String bound = license.getMachineCode();
            if (bound != null && !bound.isBlank()) {
                if (bound.equals(machineId)) {
                    // A3：幂等——同一设备重试直接返回既有签名令牌，绝不二次签发
                    log.info("激活幂等命中（同机重试）：licenseKey={}", licenseKey);
                    return ActivateResponse.from(license);
                }
                // 换机恢复路径（决策 B6）：用户登录账号页手动解绑后再激活，不在此处自动改绑
                throw new BusinessException("MACHINE_MISMATCH",
                    "License is bound to another device; unbind it from your account first");
            }

            licenseService.bindToMachine(license, machineId, MachineRegistryService.SRC_ACTIVATE);
            licenseService.recordLicenseEvent(license, LicenseEvent.EventType.ACTIVATED, machineId,
                "Activated by license key");
            log.info("License activated: licenseKey={}", licenseKey);
            return ActivateResponse.from(license);
        } catch (BusinessException e) {
            // 密钥/兑换码同为「可猜测凭证」，失败一律计入暴力猜测风控（与兑换侧同口径）
            recordFailure(clientIp);
            throw e;
        }
    }

    private boolean isRedeemCode(String credential) {
        return credential.regionMatches(true, 0, REDEEM_CODE_PREFIX, 0, REDEEM_CODE_PREFIX.length());
    }

    /**
     * 兑换码分支的兑换人身份：已登录优先取登录账号邮箱（与密钥分支同口径，避免同一用户在
     * 两条分支被识别成两个客户），未登录才回落到请求体邮箱。
     */
    private String resolveEmail(ActivateRequest request, UUID currentUserId) {
        if (currentUserId != null) {
            String email = userRepository.findById(currentUserId).map(User::getEmail).orElse(null);
            if (email != null && !email.isBlank()) {
                return email;
            }
        }
        return request.getCustomerEmail();
    }

    /** A4：合并通道后按最短板取严档——兑换侧现值（20 次/10 分钟）即全仓最严，密钥分支沿用同组阈值。 */
    private void checkIpLimit(String clientIp) {
        if (clientIp == null || clientIp.isEmpty()) {
            return;
        }
        try {
            rateLimitService.checkRedeemIp(clientIp);
        } catch (RateLimitService.RateLimitExceededException e) {
            throw new BusinessException("REDEEM_IP_LIMIT", "激活请求过于频繁，请稍后再试");
        }
    }

    private void recordFailure(String clientIp) {
        if (clientIp == null || clientIp.isEmpty()) {
            return;
        }
        try {
            rateLimitService.recordRedeemFailure(clientIp);
        } catch (RateLimitService.RateLimitExceededException e) {
            throw new BusinessException("REDEEM_BRUTE_FORCE", "失败次数过多，已临时锁定");
        }
    }
}
