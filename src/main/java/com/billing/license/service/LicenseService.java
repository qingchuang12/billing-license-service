package com.billing.license.service;

import com.billing.license.config.BillingProperties;
import com.billing.license.dto.ActivateResponse;
import com.billing.license.dto.LicenseResponse;
import com.billing.license.entity.*;
import com.billing.license.exception.BusinessException;
import com.billing.license.infrastructure.crypto.LicenseIssuer;
import com.billing.license.repository.LicenseEventRepository;
import com.billing.license.repository.LicenseRepository;
import com.billing.license.repository.OrderRepository;
import com.billing.license.repository.UserRepository;
import com.billing.license.service.notification.EmailNotificationService;
import com.billing.license.service.risk.RateLimitService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class LicenseService {
    
    private final LicenseRepository licenseRepository;
    private final OrderRepository orderRepository;
    private final LicenseEventRepository licenseEventRepository;
    private final LicenseIssuer licenseIssuer;
    private final BillingProperties billingProperties;
    private final EmailNotificationService emailNotificationService;
    private final RateLimitService rateLimitService;
    // E3（客户标识邮箱化）：按 userId 解析邮箱，用于签发/重发等管理端出参回填 customerEmail
    private final UserRepository userRepository;
    // C8：机器码首次出现账本——购买签发时顺带登记，供客户端把试用起点回溯到「这台机器最早来过」的时间
    private final MachineRegistryService machineRegistryService;
    
    /**
     * Issue a license bound to a specific machine code
     */
    @Transactional
    public License issueLicense(String orderId, String machineCode) {
        log.info("Issuing license for order: {} with machineCode: {}", orderId, machineCode);

        Order order = orderRepository.findByOrderNumber(orderId)
            .orElseThrow(() -> new BusinessException("ORDER_NOT_FOUND",
                "Order not found: " + orderId));

        if (order.getPaymentStatus() != Order.PaymentStatus.PAID) {
            throw new BusinessException("ORDER_NOT_PAID",
                "Order must be paid before issuing licenses");
        }

        // C2（重复发货幂等）：按订单查存量 License，已签发则复用不二签。
        // Webhook 发货路径与客户端轮询补偿路径分属不同 Bean、无法共享 CheckoutService 私有锁，
        // 故以「按订单存在性检查」作为跨路径/跨实例的持久幂等兜底（与 generateCode 按订单幂等同口径）。
        List<License> existingByOrder = licenseRepository.findByOrder(order);
        if (existingByOrder != null && !existingByOrder.isEmpty()) {
            License reuse = existingByOrder.get(0);
            log.info("订单已存在 License，复用不二签：orderId={}, licenseKey={}", orderId, reuse.getLicenseKey());
            return reuse;
        }

        // Get first product from order items
        Product product = order.getOrderItems().stream()
            .findFirst()
            .map(item -> item.getProduct())
            .orElseThrow(() -> new BusinessException("NO_ORDER_ITEMS", "Order has no items"));

        String licenseKey = generateLicenseKey();
        LocalDateTime issuedAt = LocalDateTime.now();
        LocalDateTime expiresAt = issuedAt.plusDays(product.getLicenseDurationDays());

        License license = License.builder()
            .licenseKey(licenseKey)
            .customerId(order.getCustomerId())
            .order(order)
            .product(product)
            .status(License.LicenseStatus.ACTIVE)
            .issuedAt(issuedAt)
            .expiresAt(expiresAt)
            .build();

        // A1（方案 A）：绑定 + 签名 + 落库 + 登记机器统一走内核，避免与兑换、在线激活三路漂移
        bindToMachine(license, machineCode, MachineRegistryService.SRC_PURCHASE);
        recordLicenseEvent(license, LicenseEvent.EventType.ISSUED, machineCode, "Issued for order " + orderId);
        log.info("License issued successfully: {}", licenseKey);

        return license;
    }

    /**
     * 绑定内核（A1 / 方案 A）：把 License 绑定到机器码、签发签名令牌、落库、登记机器首次出现时间。
     *
     * <p>购买直签（{@link #issueLicense}）、兑换码兑换（{@code RedeemCodeService#doRedeem}）、
     * 在线激活（{@code CredentialBindingService}）三条路径**统一走此处**——绑定与签名只此一份，
     * 否则两套策略必然漂移，最终出现「一边幂等、一边重复签发」的资损级不一致。
     *
     * <p>调用方保留各自职责：License 的构建、事件留痕（{@link #recordLicenseEvent}）、
     * 兑换码状态流转。
     *
     * @param machineCode    机器码；为空表示签发「不绑定设备」的 License（客户端首次启动再激活）
     * @param registrySource 机器码来源标记，见 {@link MachineRegistryService}
     */
    @Transactional
    public License bindToMachine(License license, String machineCode, String registrySource) {
        if (machineCode != null && !machineCode.isBlank()) {
            // 顺序要紧：signedToken 内含绑定信息，必须先写机器码再签名，否则客户端验签拿到的仍是旧绑定
            license.setMachineCode(machineCode);
        }
        license.setSignedToken(licenseIssuer.issueLicense(license));
        licenseRepository.save(license);
        if (machineCode != null && !machineCode.isBlank()) {
            // C8：登记这台机器的首次出现时间（幂等，只刷新 last_seen_at）
            machineRegistryService.touch(machineCode, registrySource);
            // B7 = B（plan-7.0 / D3）：完成正式绑定即「已转正」（幂等、作废不回收）
            machineRegistryService.markConverted(machineCode, registrySource);
        }
        return license;
    }

    /**
     * 客户端「自动上报绑定」（plan-7.0 / D2）。
     *
     * <p><b>要解决的问题</b>：客户网购后拿到兑换码，在客户端激活时若未携带机器码，License 落成
     * 「未绑定」态、功能不可用。客户端随后在**程序启动时**把本机机器码上报一次，本方法把这张
     * 未绑定的授权补绑到该设备（客户端本地只上报一次，成功后不再触发）。
     *
     * <p><b>归属凭证 = {@code signedToken}（E1 = ①）</b>：签名令牌即授权本体，客户端在兑换/激活时
     * 必然持有，故本端点**不需要登录**。只对「未绑定」件生效——已绑他机仍按 {@code MACHINE_MISMATCH}
     * 拒绝（守决策 B6 = A，绝不自动改绑）。
     *
     * @param signedToken 客户端持有的签名令牌（服务端验签）
     * @param machineId   本机机器码
     * @return 与激活端点同构的响应（含补绑后重新签发的 {@code signedToken}）
     */
    @Transactional
    public ActivateResponse reportBinding(String signedToken, String machineId) {
        if (signedToken == null || signedToken.isBlank()) {
            throw new BusinessException("CREDENTIAL_REQUIRED", "signedToken is required");
        }
        if (machineId == null || machineId.isBlank()) {
            throw new BusinessException("MACHINE_ID_REQUIRED", "machineId is required");
        }
        String machine = machineId.trim();

        // D2 风控：按机器码维度限流（复用机器码限流档），防伪造上报冲击服务
        try {
            rateLimitService.checkMachineReport(machine);
        } catch (RateLimitService.RateLimitExceededException e) {
            throw new BusinessException("REPORT_RATE_LIMIT", "上报过于频繁，请稍后再试");
        }

        // 验签：签名令牌是唯一归属凭证；验不过一律按「凭证不认识」处理，不泄露 licenseKey 是否存在
        if (!licenseIssuer.verifyLicense(signedToken)) {
            throw new BusinessException("CREDENTIAL_NOT_FOUND", "Credential is invalid");
        }
        Object key = licenseIssuer.decodePayload(signedToken).get("lic");
        if (key == null) {
            throw new BusinessException("CREDENTIAL_NOT_FOUND", "Credential is invalid");
        }
        License license = licenseRepository.findByLicenseKey(key.toString())
            .orElseThrow(() -> new BusinessException("CREDENTIAL_NOT_FOUND", "Credential is invalid"));

        if (license.getStatus() != License.LicenseStatus.ACTIVE) {
            throw new BusinessException("LICENSE_NOT_ACTIVE",
                "License is not active: " + license.getStatus().name());
        }

        String bound = license.getMachineCode();
        if (bound != null && !bound.isBlank()) {
            if (bound.equals(machine)) {
                // 幂等：同一设备重试直接返回既有状态，不二次签发
                log.info("上报绑定幂等命中（同机）：licenseKey={}", license.getLicenseKey());
                return ActivateResponse.from(license);
            }
            // 守 B6 = A：已绑他机不自动改绑，恢复路径为账号页 / 管理端解绑
            throw new BusinessException("MACHINE_MISMATCH",
                "License is bound to another device; unbind it from your account first");
        }

        bindToMachine(license, machine, MachineRegistryService.SRC_REPORT);
        recordLicenseEvent(license, LicenseEvent.EventType.BOUND_BY_REPORT, machine,
            "Bound by client auto-report");
        log.info("License bound by client auto-report: licenseKey={}", license.getLicenseKey());
        return ActivateResponse.from(license);
    }

    /**
     * Issue licenses for a paid order
     */
    @Transactional
    public List<LicenseResponse> issueLicensesForOrder(UUID orderId) {
        log.info("Issuing licenses for order: {}", orderId);
        
        Order order = orderRepository.findById(orderId)
            .orElseThrow(() -> new BusinessException("ORDER_NOT_FOUND", 
                "Order not found: " + orderId));
        
        if (order.getPaymentStatus() != Order.PaymentStatus.PAID) {
            throw new BusinessException("ORDER_NOT_PAID", 
                "Order must be paid before issuing licenses");
        }
        
        // Get order items and issue licenses
        List<License> licenses = order.getOrderItems().stream()
            .flatMap(item -> {
                // Issue one license per quantity
                return java.util.stream.IntStream.range(0, item.getQuantity())
                    .mapToObj(i -> createLicense(order, item.getProduct()));
            })
            .collect(Collectors.toList());
        
        licenseRepository.saveAll(licenses);
        
        log.info("Issued {} licenses for order: {}", licenses.size(), orderId);
        
        // E3：本订单下所有 License 同属一个 customerId，单次解析邮箱即可（避免逐条查库）
        String customerEmail = resolveEmail(order.getCustomerId());
        return licenses.stream()
            .map(l -> mapToResponse(l, customerEmail))
            .collect(Collectors.toList());
    }
    
    /**
     * Verify a license by key
     */
    @Transactional
    public LicenseResponse verifyLicense(String licenseKey) {
        log.info("Verifying license: {}", licenseKey);
        
        License license = licenseRepository.findByLicenseKey(licenseKey)
            .orElseThrow(() -> new BusinessException("LICENSE_NOT_FOUND", 
                "License not found: " + licenseKey));
        
        // Check status
        if (license.getStatus() != License.LicenseStatus.ACTIVE) {
            recordLicenseEvent(license, LicenseEvent.EventType.VERIFY_FAILED, license.getMachineCode(),
                "Inactive status: " + license.getStatus().name());
            throw new BusinessException("LICENSE_INVALID", 
                "License is not active: " + license.getStatus().name());
        }
        
        // Check expiration
        if (license.getExpiresAt() != null && 
            LocalDateTime.now().isAfter(license.getExpiresAt())) {
            license.setStatus(License.LicenseStatus.EXPIRED);
            licenseRepository.save(license);
            recordLicenseEvent(license, LicenseEvent.EventType.VERIFY_FAILED, license.getMachineCode(),
                "Expired at " + license.getExpiresAt());
            throw new BusinessException("LICENSE_EXPIRED", 
                "License has expired");
        }

        // A1：纵深防御——服务端校验点同样重验签名，避免库内 token 被篡改/伪造后仅凭状态即放行
        String signedToken = license.getSignedToken();
        if (signedToken == null || signedToken.isBlank() || !licenseIssuer.verifyLicense(signedToken)) {
            recordLicenseEvent(license, LicenseEvent.EventType.VERIFY_FAILED, license.getMachineCode(),
                "签名校验失败：signedToken 缺失或签名不匹配");
            throw new BusinessException("LICENSE_INVALID", "License signature verification failed");
        }

        // K7（2026-09-18）：lastVerifiedAt 语义为「最后一次**成功**校验」，故放在全部判定通过之后再写。
        // 此前实现是「先写后判」（旧 138-140 行），校验失败也会刷新该字段，导致数据失真。
        license.setLastVerifiedAt(LocalDateTime.now());
        licenseRepository.save(license);

        // E3：verify 为**公开端点**——不回显客户邮箱（否则任何持 licenseKey 者可看到归属邮箱）
        return mapToResponse(license, null);
    }
    
    /**
     * 管理端作废（plan-7.0 / D6，2026-09-23 收敛到**唯一一处**）：置 {@code REVOKED} 终态，并**写
     * {@code license_events} 留痕**（含操作原因）。
     *
     * <p>本方法取代此前两处分散实现——{@code AdminService#revokeLicense}（线上在用但**不写事件**，
     * 导致 {@code license_events} 永远查不到作废历史，售后按事件排查会得出「该件从未被作废」的错误结论）
     * 与旧的无参版本（写事件但**生产零调用**，仅单测引用）。形状对齐
     * {@link #unbindByAdmin} / {@link #reissueLicense}：查件 → 置状态 → 记事件（含原因）。
     *
     * <p>「作废」为**终态不可逆**，与「解绑」（只清机器码、保留授权、可逆）语义不同，不可混用。
     *
     * @param licenseKey 许可证密钥
     * @param reason     作废原因，写入事件 detail 供售后追溯
     */
    @Transactional
    public void revokeLicense(String licenseKey, String reason) {
        License license = licenseRepository.findByLicenseKey(licenseKey)
            .orElseThrow(() -> new BusinessException("LICENSE_NOT_FOUND", 
                "License not found: " + licenseKey));

        String boundMachineCode = license.getMachineCode();
        license.setStatus(License.LicenseStatus.REVOKED);
        license.setRevokedAt(LocalDateTime.now());
        licenseRepository.save(license);
        recordLicenseEvent(license, LicenseEvent.EventType.REVOKED, boundMachineCode,
            "Revoked. reason=" + (reason != null ? reason : ""));
        log.info("Revoked license: {} (reason={})", licenseKey, reason);
    }

    /**
     * 账号自助解绑（plan-7.0 决策 B6 的换机恢复路径）：登录用户释放**本人** License 的设备绑定。
     *
     * <p>归属凭证为「登录态 + 归属」（{@code License.customerId == 登录 userId}）：供账号页在
     * 「授权已绑到别的机器」时手动解绑——此后即可重新激活到新设备。
     *
     * <p>此前另有一条凭「旧授权签名令牌」解绑的公开端点（{@code POST /api/licenses/unbind}），
     * 因**不在安全白名单内而始终 403 不可达**，已按 plan-7.0 / B10 删除（端点与
     * {@code unbindDevice} 一并移除）；同机换绑场景统一走本方法（账号侧）或管理端解绑。
     *
     * <p>只清 {@code machineCode}，不吊销授权本身、不动 {@code REVOKED} 状态。
     *
     * @return 解绑前所绑定的机器码；本就未绑定时返回 {@code null}（幂等，不重复留痕）
     */
    @Transactional
    public String unbindByOwner(String licenseKey, UUID ownerId) {
        // 越权与密钥不存在**同码返回**，不泄露他人 License 是否存在
        License license = licenseRepository.findByLicenseKey(licenseKey)
            .filter(l -> ownerId != null && ownerId.equals(l.getCustomerId()))
            .orElseThrow(() -> new BusinessException("LICENSE_NOT_FOUND",
                "License not found: " + licenseKey));

        if (license.getStatus() == License.LicenseStatus.REVOKED) {
            throw new BusinessException("LICENSE_REVOKED", "Cannot unbind a revoked license");
        }

        String previous = license.getMachineCode();
        if (previous == null || previous.isBlank()) {
            return null;
        }

        license.setMachineCode(null);
        licenseRepository.save(license);
        recordLicenseEvent(license, LicenseEvent.EventType.UNBOUND, previous,
            "Device binding released (account self-service)");
        log.info("License device binding released by owner: licenseKey={}", licenseKey);
        return previous;
    }

    /**
     * 管理端解绑：清空指定 License 的 {@code machineCode}，**不吊销授权本身**。
     *
     * <p>与账号侧 {@link #unbindByOwner} 的分工：那条要求「登录态 + 归属」（用户自助），
     * 本方法供**管理员**处置——典型场景是用户换机后旧令牌随旧机器失效、登不进账号页，
     * 客服只能代其释放绑定。故本方法**不做归属校验**，把门交给 {@code /api/admin/**}
     * 的 {@code ROLE_ADMIN} 框架授权。
     *
     * <p>语义边界（三者不可混）：<b>解绑</b>只让授权脱离当前设备（可换机复用）；
     * <b>作废</b>是终态不可逆；<b>换机重发</b>会签发新 key 并把旧证置 {@code REISSUED}。
     *
     * @param reason 处置原因（写入 {@code license_events.detail} 供售后追溯；可为空）
     * @return 解绑前的机器码；本就未绑定时返回 {@code null}（幂等，不落库、不留痕）
     */
    @Transactional
    public String unbindByAdmin(String licenseKey, String reason) {
        License license = licenseRepository.findByLicenseKey(licenseKey)
            .orElseThrow(() -> new BusinessException("LICENSE_NOT_FOUND",
                "License not found: " + licenseKey));

        if (license.getStatus() == License.LicenseStatus.REVOKED) {
            throw new BusinessException("LICENSE_REVOKED", "Cannot unbind a revoked license");
        }

        String previous = license.getMachineCode();
        if (previous == null || previous.isBlank()) {
            return null;
        }

        license.setMachineCode(null);
        licenseRepository.save(license);
        recordLicenseEvent(license, LicenseEvent.EventType.UNBOUND, previous,
            "Device binding released by admin. reason=" + (reason != null ? reason : ""));
        log.info("License device binding released by admin: licenseKey={}, reason={}", licenseKey, reason);
        return previous;
    }

    /**
     * 换机重发 - 为已绑定设备的 License 重新签发一个新 License 绑定到新机器码（架构十一.5）
     * 原 License 标记为 REISSUED，新 License 通过 reissuedFrom 指回原 License。
     *
     * @param newMachineId 新机器码；**可为空**（此时只失效并重发、不绑任何设备，
     *                     用户随后可自行在新机激活）
     * @param force        管理端豁免：为 {@code true} 时**越过** {@code licenseReissueMax}
     *                     重发上限。客服面对真实售后不应被风控锁死；越限放行会在事件
     *                     detail 留 {@code forced} 标记，使审计能区分常规重发与人工越限
     */
    @Transactional
    public LicenseResponse reissueLicense(String licenseKey, String newMachineId, String reason,
                                          boolean force) {
        log.info("Reissuing license {} to new machine {} (force={})", licenseKey, newMachineId, force);

        License original = licenseRepository.findByLicenseKey(licenseKey)
            .orElseThrow(() -> new BusinessException("LICENSE_NOT_FOUND",
                "License not found: " + licenseKey));

        if (original.getStatus() == License.LicenseStatus.REVOKED) {
            throw new BusinessException("LICENSE_REVOKED", "Cannot reissue a revoked license");
        }

        // 风控：同一机器码窗口内频繁换机（架构十七）
        if (newMachineId != null && !newMachineId.isEmpty()) {
            try {
                rateLimitService.checkMachineReissue(newMachineId);
            } catch (RateLimitService.RateLimitExceededException e) {
                throw new BusinessException("MACHINE_REISSUE_LIMIT", "该设备换机重发过于频繁，请稍后再试");
            }
        }

        // 风控：单个 License 累计重发次数上限（架构十七：License 重发次数限制）。
        // 管理端可经 force=true 显式豁免——默认仍拦，避免误点把一张证无限重发。
        int reissueMax = billingProperties.getRisk().getLicenseReissueMax();
        long reissuedCount = licenseEventRepository.findByLicenseKey(licenseKey).stream()
            .filter(e -> e.getEventType() == LicenseEvent.EventType.REISSUED)
            .count();
        boolean overLimit = reissuedCount >= reissueMax;
        if (overLimit && !force) {
            throw new BusinessException("LICENSE_REISSUE_LIMIT",
                "License 重发次数已达上限：" + reissueMax);
        }

        // 原 License 标记为 REISSUED
        // D3（2026-09-14）：换机失效与退款吊销语义不同，改用 REISSUED（原先误置 REVOKED，
        // 与注释矛盾且会让售后/审计无法区分换机与退款）；换机不写 revokedAt。
        original.setStatus(License.LicenseStatus.REISSUED);
        licenseRepository.save(original);
        recordLicenseEvent(original, LicenseEvent.EventType.REISSUED, newMachineId,
            "Reissued to new machine. reason=" + (reason != null ? reason : "")
                + (overLimit ? " [forced over limit=" + reissueMax + "]" : ""));

        // 签发新的绑定新机器码的 License
        LocalDateTime issuedAt = LocalDateTime.now();
        LocalDateTime expiresAt = original.getExpiresAt() != null
            ? original.getExpiresAt() : issuedAt.plusDays(billingProperties.getDefaultLicenseDurationDays());

        License newLicense = License.builder()
            .licenseKey(generateLicenseKey())
            .customerId(original.getCustomerId())
            .order(original.getOrder())
            .product(original.getProduct())
            .status(License.LicenseStatus.ACTIVE)
            .issuedAt(issuedAt)
            .expiresAt(expiresAt)
            .machineCode(newMachineId)
            .reissuedFrom(original.getId())
            .build();

        String signedToken = licenseIssuer.issueLicense(newLicense);
        newLicense.setSignedToken(signedToken);

        licenseRepository.save(newLicense);
        recordLicenseEvent(newLicense, LicenseEvent.EventType.ISSUED, newMachineId,
            "Reissued license for original " + licenseKey);

        log.info("Reissued license created: {}", newLicense.getLicenseKey());
        return mapToResponse(newLicense, resolveEmail(newLicense.getCustomerId()));
    }

    /**
     * 记录 License 事件用于审计。
     *
     * <p>公开供各绑定路径按自身语义留痕（本类内部为 {@code ISSUED}/{@code REISSUED}/{@code REVOKED}，
     * 兑换路径为 {@code REDEEMED}，在线激活为 {@code ACTIVATED}）——内核
     * {@link #bindToMachine} 不做事件记录，避免把不同路径的语义塞进同一个方法。
     *
     * <p>失败只记日志不上抛：审计留痕不应阻断主流程。
     */
    public void recordLicenseEvent(License license, LicenseEvent.EventType type, String machineId, String detail) {
        try {
            LicenseEvent event = LicenseEvent.builder()
                .licenseId(license.getId())
                .licenseKey(license.getLicenseKey())
                .eventType(type)
                .machineId(machineId)
                .detail(detail)
                .build();
            licenseEventRepository.save(event);
        } catch (Exception e) {
            log.error("记录 License 事件失败：type={}, licenseKey={}", type, license.getLicenseKey(), e);
        }
    }
    
    private License createLicense(Order order, Product product) {
        String licenseKey = generateLicenseKey();
        LocalDateTime issuedAt = LocalDateTime.now();
        LocalDateTime expiresAt = issuedAt.plusDays(product.getLicenseDurationDays());
        
        License license = License.builder()
            .licenseKey(licenseKey)
            .customerId(order.getCustomerId())
            .order(order)
            .product(product)
            .status(License.LicenseStatus.ACTIVE)
            .issuedAt(issuedAt)
            .expiresAt(expiresAt)
            .build();
        
        // Sign the license
        String signedToken = licenseIssuer.issueLicense(license);
        license.setSignedToken(signedToken);
        
        return license;
    }
    
    private String generateLicenseKey() {
        // Format: XXXX-XXXX-XXXX-XXXX
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            if (i > 0) sb.append("-");
            String segment = UUID.randomUUID().toString().substring(0, 4).toUpperCase();
            sb.append(segment);
        }
        return sb.toString();
    }
    
    /** E3：构建 License 响应；{@code customerEmail} 由调用方按端点口径解析后传入（公开端点传 null）。 */
    private LicenseResponse mapToResponse(License license, String customerEmail) {
        return LicenseResponse.builder()
            .id(license.getId())
            .licenseKey(license.getLicenseKey())
            .customerEmail(customerEmail)
            .productSku(license.getProduct().getSku())
            .status(license.getStatus().name())
            .issuedAt(license.getIssuedAt())
            .expiresAt(license.getExpiresAt())
            .lastVerifiedAt(license.getLastVerifiedAt())
            .reissuedFrom(license.getReissuedFrom())
            .signedToken(license.getSignedToken())
            .build();
    }

    /** E3：按 userId 解析邮箱；匿名历史件或查不到返回 null。 */
    private String resolveEmail(UUID customerId) {
        if (customerId == null) {
            return null;
        }
        return userRepository.findById(customerId).map(User::getEmail).orElse(null);
    }
}
