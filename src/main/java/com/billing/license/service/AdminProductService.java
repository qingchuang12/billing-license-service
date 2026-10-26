package com.billing.license.service;

import com.billing.license.config.BillingProperties;
import com.billing.license.dto.AdminProductMetaView;
import com.billing.license.dto.AdminProductRequest;
import com.billing.license.dto.AdminProductView;
import com.billing.license.entity.Currency;
import com.billing.license.entity.PlanTier;
import com.billing.license.entity.Product;
import com.billing.license.exception.BusinessException;
import com.billing.license.repository.ProductProfileRepository;
import com.billing.license.repository.ProductRepository;
import com.billing.license.service.payment.impl.PaymentServiceFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 管理端产品套餐服务（产品 → 套餐两层中的套餐层）。
 *
 * <p>边界：SKU 创建后不可改；不提供物理删除（订单 / License / 兑换码均以外键引用产品），
 * 停售走上下架。权益与产品级名称/描述在产品档案上维护，本服务只维护周期、价格与授权参数。
 * 数据修改只在前端做一次确认弹窗，不额外要求第二因子（MFA 仅用于登录）。
 */
@Service
@RequiredArgsConstructor
public class AdminProductService {

    private final ProductRepository productRepository;
    private final ProductProfileRepository productProfileRepository;
    private final BillingProperties billingProperties;
    private final PaymentServiceFactory paymentServiceFactory;

    /** 套餐列表：产品表为数十行量级，按 SKU 升序全量取回后内存过滤，避免为组合条件新增查询方法。 */
    @Transactional(readOnly = true)
    public List<AdminProductView> list(String keyword, Boolean active) {
        String kw = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        return productRepository.findAll(Sort.by(Sort.Direction.ASC, "sku")).stream()
            .filter(p -> active == null || Boolean.TRUE.equals(p.getActive()) == active.booleanValue())
            .filter(p -> kw.isEmpty() || matches(p, kw))
            .map(AdminProductView::from)
            .toList();
    }

    /** 表单元数据：周期 / 档位枚举 + 配置驱动的权益标签（产品档案表单用）。 */
    public AdminProductMetaView meta() {
        List<AdminProductMetaView.FeatureOption> features = new ArrayList<>();
        Map<String, BillingProperties.FeatureLabel> labels = billingProperties.getFeatureLabels();
        if (labels != null) {
            labels.forEach((key, label) -> features.add(AdminProductMetaView.FeatureOption.builder()
                .key(key)
                .labelZh(label != null && label.getZh() != null ? label.getZh() : key)
                .labelEn(label != null && label.getEn() != null ? label.getEn() : key)
                .build()));
        }
        features.sort((a, b) -> a.getKey().compareTo(b.getKey()));
        return AdminProductMetaView.builder()
            .billingCycles(Arrays.stream(Product.BillingCycle.values()).map(Enum::name).toList())
            .tiers(Arrays.stream(PlanTier.values()).map(Enum::name).toList())
            .features(features)
            .channels(paymentServiceFactory.getChannelStatuses())
            .build();
    }

    @Transactional
    public AdminProductView create(AdminProductRequest request) {
        String sku = requireSku(request.getSku());
        if (productRepository.existsBySku(sku)) {
            throw new BusinessException("SKU_EXISTS", "SKU 已存在：" + sku);
        }
        Product product = Product.builder()
            .sku(sku)
            .productCode(requireProfileCode(request.getProductCode()))
            .name(request.getName().trim())
            .nameEn(trimToNull(request.getNameEn()))
            .description(trimToNull(request.getDescription()))
            .descriptionEn(trimToNull(request.getDescriptionEn()))
            .price(request.getPriceUsd())
            .currency(Currency.USD)
            .priceCny(request.getPriceCny())
            .priceUsd(request.getPriceUsd())
            .billingCycle(parseBillingCycle(request.getBillingCycle()))
            .tier(parseTier(request.getTier()))
            .licenseDurationDays(request.getLicenseDurationDays())
            .updateUntilDays(request.getUpdateUntilDays())
            .maxMajorVersion(request.getMaxMajorVersion())
            .stripePriceId(trimToNull(request.getStripePriceId()))
            .paddlePriceId(trimToNull(request.getPaddlePriceId()))
            .active(request.getActive() == null || request.getActive())
            .recommended(Boolean.TRUE.equals(request.getRecommended()))
            .build();
        return AdminProductView.from(productRepository.save(product));
    }

    @Transactional
    public AdminProductView update(UUID productId, AdminProductRequest request) {
        Product product = requireProduct(productId);
        String requestedSku = trimToNull(request.getSku());
        if (requestedSku != null && !requestedSku.equalsIgnoreCase(product.getSku())) {
            throw new BusinessException("SKU_IMMUTABLE", "SKU 创建后不可修改：" + product.getSku());
        }
        product.setProductCode(requireProfileCode(request.getProductCode()));
        product.setName(request.getName().trim());
        product.setNameEn(trimToNull(request.getNameEn()));
        product.setDescription(trimToNull(request.getDescription()));
        product.setDescriptionEn(trimToNull(request.getDescriptionEn()));
        product.setPriceCny(request.getPriceCny());
        product.setPriceUsd(request.getPriceUsd());
        /* 兼容字段与 USD 价同源：历史读取方仍取 price / currency */
        product.setPrice(request.getPriceUsd());
        product.setCurrency(Currency.USD);
        product.setBillingCycle(parseBillingCycle(request.getBillingCycle()));
        product.setTier(parseTier(request.getTier()));
        product.setLicenseDurationDays(request.getLicenseDurationDays());
        product.setUpdateUntilDays(request.getUpdateUntilDays());
        product.setMaxMajorVersion(request.getMaxMajorVersion());
        product.setStripePriceId(trimToNull(request.getStripePriceId()));
        product.setPaddlePriceId(trimToNull(request.getPaddlePriceId()));
        product.setRecommended(Boolean.TRUE.equals(request.getRecommended()));
        return AdminProductView.from(productRepository.save(product));
    }

    @Transactional
    public AdminProductView changeActive(UUID productId, boolean active) {
        Product product = requireProduct(productId);
        if (Boolean.TRUE.equals(product.getActive()) == active) {
            return AdminProductView.from(product);
        }
        product.setActive(active);
        return AdminProductView.from(productRepository.save(product));
    }

    // ==================== 内部转换与护栏 ====================

    private Product requireProduct(UUID productId) {
        return productRepository.findById(productId)
            .orElseThrow(() -> new BusinessException("PRODUCT_NOT_FOUND", "产品不存在：" + productId));
    }

    /** 套餐必须挂到已存在的产品档案，否则权益/产品级描述无从读取。 */
    private String requireProfileCode(String rawCode) {
        String code = normalizeCode(rawCode);
        if (code == null || !productProfileRepository.existsByProductCodeIgnoreCase(code)) {
            throw new BusinessException("PRODUCT_PROFILE_NOT_FOUND", "产品档案不存在：" + rawCode);
        }
        return code;
    }

    private boolean matches(Product product, String keyword) {
        return containsIgnoreCase(product.getSku(), keyword)
            || containsIgnoreCase(product.getName(), keyword)
            || containsIgnoreCase(product.getNameEn(), keyword)
            || containsIgnoreCase(product.getProductCode(), keyword);
    }

    private static boolean containsIgnoreCase(String value, String keyword) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(keyword);
    }

    private static String requireSku(String rawSku) {
        String sku = trimToNull(rawSku);
        if (sku == null) {
            throw new BusinessException("SKU_REQUIRED", "SKU 不能为空");
        }
        return sku.toLowerCase(Locale.ROOT);
    }

    private static String normalizeCode(String rawCode) {
        String code = trimToNull(rawCode);
        return code == null ? null : code.toLowerCase(Locale.ROOT);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static Product.BillingCycle parseBillingCycle(String raw) {
        try {
            return Product.BillingCycle.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BusinessException("INVALID_BILLING_CYCLE", "非法的计费周期：" + raw);
        }
    }

    private static PlanTier parseTier(String raw) {
        try {
            return PlanTier.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BusinessException("INVALID_PLAN_TIER", "非法的产品档位：" + raw);
        }
    }
}
