package com.billing.license.controller;

import com.billing.license.dto.ProductPublicDto;
import com.billing.license.dto.ProviderCapability;
import com.billing.license.entity.Product;
import com.billing.license.repository.ProductRepository;
import com.billing.license.service.payment.impl.PaymentServiceFactory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;

/**
 * 公开产品目录（K16）。
 * 收银台页面用它取 SKU / 双档价格 / 档位 / 周期与可售渠道，零鉴权（SecurityConfig 已 permitAll）。
 */
@Tag(name = "产品目录", description = "公开产品目录（无需鉴权），供收银台页面拉取档位与价格")
@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    /** 目录顺序：推荐位优先，其余按 SKU 升序——保证接入方每次拿到相同顺序，不依赖数据库返回序。 */
    private static final Comparator<Product> CATALOG_ORDER = Comparator
        .comparing((Product p) -> Boolean.TRUE.equals(p.getRecommended())).reversed()
        .thenComparing(Product::getSku);

    private final ProductRepository productRepository;
    private final PaymentServiceFactory paymentServiceFactory;

    @Operation(summary = "列出在售产品",
            description = "返回全部 active=true 的产品（SKU / 产品码 / 双档价格 / 档位 / 周期 / 可售渠道）。"
                    + "可选 product 参数按产品码（products.product_code，大小写不敏感）过滤目录，缺省或空白返回全量。公开端点，无需鉴权")
    @GetMapping
    public ResponseEntity<List<ProductPublicDto>> list(
            @RequestParam(name = "product", required = false) String product) {
        // 产品维度（V10）：product 仅作目录过滤条件；空白 = 全量，保持无参调用行为不变
        String productCode = product == null ? "" : product.trim();
        List<Product> catalog = productCode.isEmpty()
                ? productRepository.findByActiveTrue()
                : productRepository.findByActiveTrueAndProductCodeIgnoreCase(productCode);
        catalog = catalog.stream().sorted(CATALOG_ORDER).toList();
        return ResponseEntity.ok(catalog.stream()
                .map(p -> {
                    ProductPublicDto dto = ProductPublicDto.from(p);
                    dto.setProviders(providerCapabilities(p));
                    return dto;
                })
                .toList());
    }

    /**
     * 当前部署下可售该套餐的渠道：启用 + 周期 + **渠道商品映射**三重过滤。
     *
     * <p>只按 enabled+cycle 宣称可售会让收银台展示一个必然下单失败的渠道
     * （Creem 缺 creem_product_id、Paddle 订阅缺 paddle_price_id）。
     */
    private List<ProviderCapability> providerCapabilities(Product product) {
        boolean recurring = product.getChargeMode() == com.billing.license.entity.ChargeMode.AUTO_RECURRING;
        return paymentServiceFactory.getEnabledMethods(product.getChargeMode()).stream()
            .filter(method -> switch (method) {
                case CREEM -> hasText(product.getCreemProductId());
                case PADDLE -> !recurring || hasText(product.getPaddlePriceId());
                default -> true;
            })
            .map(ProviderCapability::from)
            .toList();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
