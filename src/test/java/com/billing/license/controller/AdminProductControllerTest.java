package com.billing.license.controller;

import com.billing.license.entity.Product;
import com.billing.license.entity.ProductProfile;
import com.billing.license.entity.User;
import com.billing.license.repository.ProductProfileRepository;
import com.billing.license.repository.ProductRepository;
import com.billing.license.repository.UserRepository;
import com.billing.license.security.JwtTokenService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 管理端产品配置接口契约 / 鉴权测试。
 *
 * <p>覆盖：新端点沿用 {@code /api/admin/**} 的 ROLE_ADMIN 规则；SKU 建后不可改、
 * 特征键归一化去重；MFA 仅用于登录——已开启 MFA 的管理员修改数据也不再索要动态码。
 * 走真实安全过滤器链（手动装配 MockMvc，原因同 AdminUserControllerTest）。
 */
@SpringBootTest
@ActiveProfiles("test")
class AdminProductControllerTest {

    private static final String ADMIN_EMAIL = "admin-product@example.com";
    private static final String USER_EMAIL = "user-product@example.com";
    private static final String MFA_ADMIN_EMAIL = "mfa-admin-product@example.com";

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private ProductProfileRepository productProfileRepository;
    @Autowired private JwtTokenService jwtTokenService;
    @Autowired private PasswordEncoder passwordEncoder;

    private MockMvc mockMvc;
    private User admin;
    private User normalUser;
    /** 本用例创建的 SKU，便于精确清理（避免影响同库其它用例） */
    private final List<String> createdSkus = new ArrayList<>();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters(context.getBean(FilterChainProxy.class))
            .build();
        userRepository.deleteAll();
        admin = userRepository.save(User.builder()
            .email(ADMIN_EMAIL).passwordHash(passwordEncoder.encode("Passw0rd1"))
            .role(User.UserRole.ADMIN).status(User.UserStatus.ACTIVE).build());
        normalUser = userRepository.save(User.builder()
            .email(USER_EMAIL).passwordHash(passwordEncoder.encode("Passw0rd1"))
            .role(User.UserRole.USER).status(User.UserStatus.ACTIVE).build());
        // 套餐必须挂到产品档案：所有套餐用例共用一个测试档案
        if (!productProfileRepository.existsByProductCodeIgnoreCase("ai-tools")) {
            productProfileRepository.save(ProductProfile.builder()
                .productCode("ai-tools").name("AI-Tools")
                .features("[\"OFFLINE\",\"MULTI_DEVICE\"]").build());
        }
    }

    @AfterEach
    void tearDown() {
        for (String sku : createdSkus) {
            productRepository.findBySku(sku).ifPresent(productRepository::delete);
        }
        createdSkus.clear();
        userRepository.deleteAll();
    }

    private String tokenFor(User user) {
        User fresh = userRepository.findById(user.getId()).orElseThrow();
        return jwtTokenService.issue(fresh.getId(), fresh.getTokenVersion());
    }

    private User createMfaAdmin() {
        User mfaAdmin = userRepository.save(User.builder()
            .email(MFA_ADMIN_EMAIL).passwordHash(passwordEncoder.encode("Passw0rd1"))
            .role(User.UserRole.ADMIN).status(User.UserStatus.ACTIVE).build());
        mfaAdmin.setMfaEnabled(true);
        return userRepository.save(mfaAdmin);
    }

    private String payload(String sku) {
        return "{"
            + "\"sku\":\"" + sku + "\","
            + "\"productCode\":\"ai-tools\","
            + "\"name\":\"测试产品\","
            + "\"nameEn\":\"Test Product\","
            + "\"description\":\"中文描述\","
            + "\"descriptionEn\":\"English description\","
            + "\"priceCny\":99.00,"
            + "\"priceUsd\":14.99,"
            + "\"billingCycle\":\"ONE_TIME\","
            + "\"tier\":\"PRO\","
            + "\"licenseDurationDays\":365,"
            + "\"updateUntilDays\":730,"
            + "\"maxMajorVersion\":1"
            + "}";
    }

    private void createProduct(String sku) throws Exception {
        createdSkus.add(sku);
        mockMvc.perform(post("/api/admin/products")
                .header("Authorization", "Bearer " + tokenFor(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload(sku)))
            .andExpect(status().isOk());
    }

    // ==================== 鉴权 ====================

    @Test
    @DisplayName("匿名访问产品配置 → 401；普通用户 → 403")
    void productEndpoints_enforceAdminRole() throws Exception {
        mockMvc.perform(get("/api/admin/products")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/products")
                .header("Authorization", "Bearer " + tokenFor(normalUser)))
            .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/products")
                .header("Authorization", "Bearer " + tokenFor(normalUser))
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload("user-created-product")))
            .andExpect(status().isForbidden());
    }

    // ==================== 元数据与列表 ====================

    @Test
    @DisplayName("元数据返回周期 / 档位枚举与配置驱动的权益标签")
    void meta_admin_returnsEnumsAndFeatureLabels() throws Exception {
        mockMvc.perform(get("/api/admin/products/meta")
                .header("Authorization", "Bearer " + tokenFor(admin)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.billingCycles").isArray())
            .andExpect(jsonPath("$.data.tiers[0]").exists())
            .andExpect(jsonPath("$.data.features").isArray());
    }

    // ==================== 新增 ====================

    @Test
    @DisplayName("新增套餐：成功、归属产品档案、默认上架且不带权益字段")
    void create_admin_shouldSucceed() throws Exception {
        String sku = "test-product-create";
        createdSkus.add(sku);

        mockMvc.perform(post("/api/admin/products")
                .header("Authorization", "Bearer " + tokenFor(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload(sku)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.sku").value(sku))
            .andExpect(jsonPath("$.data.productCode").value("ai-tools"))
            .andExpect(jsonPath("$.data.active").value(true))
            .andExpect(jsonPath("$.data.recommended").value(false))
            // 权益属于产品档案，套餐视图不再携带 features
            .andExpect(jsonPath("$.data.features").doesNotExist());

        Product saved = productRepository.findBySku(sku).orElseThrow();
        assertEquals(0, saved.getPriceCny().compareTo(new BigDecimal("99.00")));
        assertEquals(0, saved.getPriceUsd().compareTo(new BigDecimal("14.99")));
        // 兼容字段与 USD 价同源
        assertEquals(0, saved.getPrice().compareTo(new BigDecimal("14.99")));
        assertEquals("USD", saved.getCurrency().name());
    }

    @Test
    @DisplayName("重复 SKU → SKU_EXISTS")
    void create_duplicateSku_shouldReject() throws Exception {
        String sku = "test-product-duplicate";
        createProduct(sku);

        mockMvc.perform(post("/api/admin/products")
                .header("Authorization", "Bearer " + tokenFor(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload(sku)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("SKU_EXISTS"));
    }

    @Test
    @DisplayName("缺少人民币价 → 参数校验失败")
    void create_missingPrice_shouldFailValidation() throws Exception {
        String body = payload("test-product-no-price")
            .replace("\"priceCny\":99.00,", "");
        mockMvc.perform(post("/api/admin/products")
                .header("Authorization", "Bearer " + tokenFor(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    // ==================== 编辑 ====================

    @Test
    @DisplayName("编辑时携带不同 SKU → SKU_IMMUTABLE")
    void update_differentSku_shouldReject() throws Exception {
        String sku = "test-product-sku-lock";
        createProduct(sku);
        Product product = productRepository.findBySku(sku).orElseThrow();

        mockMvc.perform(patch("/api/admin/products/" + product.getId())
                .header("Authorization", "Bearer " + tokenFor(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload("another-sku")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("SKU_IMMUTABLE"));
    }

    @Test
    @DisplayName("未开 MFA 的管理员改价直接生效")
    void update_price_withoutMfaAdmin_shouldSucceed() throws Exception {
        String sku = "test-product-price-no-mfa";
        createProduct(sku);
        Product product = productRepository.findBySku(sku).orElseThrow();

        mockMvc.perform(patch("/api/admin/products/" + product.getId())
                .header("Authorization", "Bearer " + tokenFor(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload(sku).replace("\"priceCny\":99.00", "\"priceCny\":129.00")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.priceCny").exists());

        Product updated = productRepository.findBySku(sku).orElseThrow();
        assertEquals(0, updated.getPriceCny().compareTo(new BigDecimal("129.00")));
    }

    @Test
    @DisplayName("已开启 MFA 的管理员编辑产品：不再索要动态码，直接生效")
    void update_mfaAdmin_shouldNotRequireStepUp() throws Exception {
        String sku = "test-product-price-mfa";
        createProduct(sku);
        Product product = productRepository.findBySku(sku).orElseThrow();
        String token = tokenFor(createMfaAdmin());

        mockMvc.perform(patch("/api/admin/products/" + product.getId())
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload(sku).replace("测试产品", "改名产品")
                    .replace("\"priceCny\":99.00", "\"priceCny\":199.00")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.name").value("改名产品"));

        Product updated = productRepository.findBySku(sku).orElseThrow();
        assertEquals(0, updated.getPriceCny().compareTo(new BigDecimal("199.00")));
    }

    // ==================== 上下架 ====================

    @Test
    @DisplayName("已开启 MFA 的管理员上下架：不再索要动态码，直接生效")
    void changeActive_mfaAdmin_shouldNotRequireStepUp() throws Exception {
        String sku = "test-product-toggle";
        createProduct(sku);
        Product product = productRepository.findBySku(sku).orElseThrow();
        String token = tokenFor(createMfaAdmin());

        mockMvc.perform(patch("/api/admin/products/" + product.getId() + "/active")
                .param("active", "false")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.active").value(false));

        assertFalse(productRepository.findBySku(sku).orElseThrow().getActive());
    }

    // ==================== 列表过滤 ====================

    @Test
    @DisplayName("列表支持关键字与上下架状态过滤，且包含已下架产品")
    void list_filterByKeywordAndActive() throws Exception {
        String sku = "test-product-list-filter";
        createProduct(sku);

        mockMvc.perform(get("/api/admin/products")
                .param("keyword", "LIST-FILTER")
                .header("Authorization", "Bearer " + tokenFor(admin)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].sku").value(sku));

        mockMvc.perform(get("/api/admin/products")
                .param("active", "false")
                .header("Authorization", "Bearer " + tokenFor(admin)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(0));
    }

    // ==================== 产品档案 ====================

    @Test
    @DisplayName("产品档案：创建、权益归一化、产品码锁定、重复拒绝、挂载套餐数")
    void productProfiles_crudContract() throws Exception {
        String code = "test-profile-" + UUID.randomUUID().toString().substring(0, 8);
        String createBody = "{"
            + "\"productCode\":\"" + code + "\","
            + "\"name\":\"测试产品\","
            + "\"nameEn\":\"Test Product\","
            + "\"features\":[\"offline\",\"CLOUD_SYNC\",\"offline\"]"
            + "}";

        mockMvc.perform(post("/api/admin/product-profiles")
                .header("Authorization", "Bearer " + tokenFor(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.productCode").value(code))
            .andExpect(jsonPath("$.data.features.length()").value(2))
            .andExpect(jsonPath("$.data.features[0]").value("OFFLINE"))
            .andExpect(jsonPath("$.data.features[1]").value("CLOUD_SYNC"));

        mockMvc.perform(post("/api/admin/product-profiles")
                .header("Authorization", "Bearer " + tokenFor(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("PRODUCT_PROFILE_EXISTS"));

        ProductProfile profile = productProfileRepository.findByProductCodeIgnoreCase(code).orElseThrow();
        mockMvc.perform(patch("/api/admin/product-profiles/" + profile.getId())
                .header("Authorization", "Bearer " + tokenFor(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody.replace(code, code + "-new")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("PRODUCT_PROFILE_CODE_IMMUTABLE"));

        mockMvc.perform(get("/api/admin/product-profiles")
                .param("keyword", code)
                .header("Authorization", "Bearer " + tokenFor(admin)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].planCount").value(0));

        productProfileRepository.delete(profile);
    }

    @Test
    @DisplayName("套餐挂到不存在的产品档案 → PRODUCT_PROFILE_NOT_FOUND")
    void createProduct_unknownProfile_shouldReject() throws Exception {
        String sku = "test-product-orphan";
        createdSkus.add(sku);
        mockMvc.perform(post("/api/admin/products")
                .header("Authorization", "Bearer " + tokenFor(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload(sku).replace("\"ai-tools\"", "\"no-such-profile\"")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("PRODUCT_PROFILE_NOT_FOUND"));
    }
}
