package com.billing.license.controller;

import com.billing.license.dto.ProductPublicDto;
import com.billing.license.repository.ProductRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 公开产品目录（K16）。
 * 收银台页面用它取 SKU / 双档价格 / 档位 / 周期 / 权益，零鉴权（SecurityConfig 已 permitAll）。
 */
@Tag(name = "产品目录", description = "公开产品目录（无需鉴权），供收银台页面拉取档位与价格")
@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductRepository productRepository;

    @Operation(summary = "列出在售产品",
            description = "返回全部 active=true 的产品（SKU / 双档价格 / 档位 / 周期 / 权益）。公开端点，无需鉴权")
    @GetMapping
    public ResponseEntity<List<ProductPublicDto>> list() {
        return ResponseEntity.ok(productRepository.findByActiveTrue().stream()
                .map(ProductPublicDto::from)
                .toList());
    }
}
