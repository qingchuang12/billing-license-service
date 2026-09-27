package com.billing.license.service;

import com.billing.license.dto.AdminUserView;
import com.billing.license.entity.User;
import com.billing.license.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 用户列表查询的<b>真实库证伪</b>（QA 独立复核，plan-7.0 P0 / T02）。
 *
 * <p>工程师的用例断言的是「传给 JPA 的 pattern 字符串长什么样」（用 mock CriteriaBuilder 接住
 * {@code cb.like(...)} 的参数）。这能证明转义函数写对了，但证明不了
 * <b>Hibernate 最终生成的 SQL 里 ESCAPE 子句真的生效</b>——转义符被吃掉、方言不认 {@code ESCAPE '\'}、
 * 参数绑定把反斜杠吃掉，都能让 {@code %} 重新变回通配符而那套 mock 用例依然全绿。
 *
 * <p>故这里全部走真实 H2：造出「含 {@code %} / {@code _} / {@code \} 的邮箱 + 若干近似邮箱」的夹具，
 * 用命中条数与命中对象反推通配符是否真的被转义了。
 */
@SpringBootTest
@ActiveProfiles("test")
class AdminUserListQueryDbTest {

    @Autowired private WebApplicationContext context;
    @Autowired private AdminUserService adminUserService;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;
    private User admin;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters(context.getBean(FilterChainProxy.class))
            .build();
        userRepository.deleteAll();
        admin = userRepository.save(User.builder()
            .email("qa-list-admin@example.com")
            .passwordHash(passwordEncoder.encode("Passw0rd1"))
            .role(User.UserRole.ADMIN).status(User.UserStatus.ACTIVE).build());
    }

    @AfterEach
    void tearDown() {
        userRepository.deleteAll();
    }

    private User save(String email, User.UserRole role, User.UserStatus status) {
        return userRepository.save(User.builder()
            .email(email)
            .passwordHash(passwordEncoder.encode("Passw0rd1"))
            .role(role).status(status)
            .build());
    }

    private List<String> emailsMatching(String term) {
        Page<AdminUserView> page = adminUserService.listUsers(term, null, null, PageRequest.of(0, 200));
        return page.getContent().stream().map(AdminUserView::getEmail).toList();
    }

    // ==================== 1. LIKE 通配符转义 ====================

    /** 若 % 未被转义，这条查询会把全部 4 个 wild* 邮箱都命中（% 匹配任意串）。 */
    @Test
    @DisplayName("email 中的 % 必须按普通字符匹配，不能被当成通配符")
    void percentIsEscaped() {
        save("wild%pct@example.com", User.UserRole.USER, User.UserStatus.ACTIVE);
        save("wildXpct@example.com", User.UserRole.USER, User.UserStatus.ACTIVE);
        save("wild_us@example.com", User.UserRole.USER, User.UserStatus.ACTIVE);
        save("wildYus@example.com", User.UserRole.USER, User.UserStatus.ACTIVE);

        List<String> hits = emailsMatching("%");
        assertEquals(List.of("wild%pct@example.com"), hits,
            "% 未转义时会命中全部 4 条；实际命中=" + hits);
    }

    /** 若 _ 未被转义，它会匹配任意一个字符，从而命中 wildXpct / wildYus。 */
    @Test
    @DisplayName("email 中的 _ 必须按普通字符匹配，不能被当成单字符通配符")
    void underscoreIsEscaped() {
        save("wild%pct@example.com", User.UserRole.USER, User.UserStatus.ACTIVE);
        save("wildXpct@example.com", User.UserRole.USER, User.UserStatus.ACTIVE);
        save("wild_us@example.com", User.UserRole.USER, User.UserStatus.ACTIVE);
        save("wildYus@example.com", User.UserRole.USER, User.UserStatus.ACTIVE);

        List<String> hits = emailsMatching("_");
        assertEquals(List.of("wild_us@example.com"), hits,
            "_ 未转义时会命中 3 条；实际命中=" + hits);
    }

    /** 反斜杠是转义符本身：用户输入它时必须先被转义，否则会吞掉后面的字符。 */
    @Test
    @DisplayName("email 中的反斜杠必须按普通字符匹配（转义符自身也要转义）")
    void backslashIsEscaped() {
        save("wild\\bs@example.com", User.UserRole.USER, User.UserStatus.ACTIVE);
        save("wildZbs@example.com", User.UserRole.USER, User.UserStatus.ACTIVE);

        List<String> hits = emailsMatching("\\");
        assertEquals(List.of("wild\\bs@example.com"), hits, "实际命中=" + hits);
    }

    // ==================== 2. 大小写与空白 ====================

    /**
     * 库内邮箱一律按归一化小写存（注册 / 登录 / bootstrap 写入前都过 {@code normalizeEmail}），
     * 查询入参再 trim + lowercase，故「大写入参查小写库」必须命中。
     */
    @Test
    @DisplayName("email 查询大小写不敏感（入参 trim + lowercase，库内按归一化小写存）")
    void emailSearchIsCaseInsensitiveAndTrimmed() {
        save("mixedcase@example.com", User.UserRole.USER, User.UserStatus.ACTIVE);

        assertEquals(List.of("mixedcase@example.com"), emailsMatching("  MIXEDCASE@EXAMPLE.COM  "));
        assertEquals(List.of("mixedcase@example.com"), emailsMatching("MixedCase"));
    }

    // ==================== 3. 组合过滤 ====================

    /**
     * 组合过滤：全部带 {@code comb-} 前缀，把 setUp 里那名 ADMIN 排除在外
     * （否则「ADMIN 共几条」会把 setUp 的管理员也数进去）。
     */
    @Test
    @DisplayName("role / status 组合过滤生效，且可与 email 叠加")
    void roleAndStatusFiltersCombine() {
        save("comb-a@example.com", User.UserRole.USER, User.UserStatus.ACTIVE);
        save("comb-b@example.com", User.UserRole.USER, User.UserStatus.DISABLED);
        save("comb-c@example.com", User.UserRole.ADMIN, User.UserStatus.ACTIVE);
        save("comb-d@example.com", User.UserRole.ADMIN, User.UserStatus.DISABLED);

        assertEquals(4, count("comb-", null, null));
        assertEquals(2, count("comb-", User.UserRole.ADMIN, null));
        assertEquals(2, count("comb-", null, User.UserStatus.DISABLED));
        assertEquals(1, count("comb-", User.UserRole.ADMIN, User.UserStatus.DISABLED));
        assertEquals(1, count("comb-", User.UserRole.USER, User.UserStatus.ACTIVE));

        List<String> combined = emails("comb-", User.UserRole.ADMIN, User.UserStatus.ACTIVE);
        assertEquals(List.of("comb-c@example.com"), combined);
    }

    private long count(String email, User.UserRole role, User.UserStatus status) {
        return adminUserService.listUsers(email, role, status, PageRequest.of(0, 200)).getTotalElements();
    }

    private List<String> emails(String email, User.UserRole role, User.UserStatus status) {
        return adminUserService.listUsers(email, role, status, PageRequest.of(0, 200))
            .getContent().stream().map(AdminUserView::getEmail).toList();
    }

    // ==================== 4. 排序稳定性 ====================

    @Test
    @DisplayName("按 createdAt 倒序（新的在前）")
    void sortedByCreatedAtDesc() {
        User old = save("sort-old@example.com", User.UserRole.USER, User.UserStatus.ACTIVE);
        User mid = save("sort-mid@example.com", User.UserRole.USER, User.UserStatus.ACTIVE);
        User neo = save("sort-new@example.com", User.UserRole.USER, User.UserStatus.ACTIVE);

        LocalDateTime base = LocalDateTime.of(2026, 1, 1, 0, 0, 0);
        setCreatedAt(old.getId(), base);
        setCreatedAt(mid.getId(), base.plusDays(1));
        setCreatedAt(neo.getId(), base.plusDays(2));

        List<String> order = adminUserService.listUsers("sort-", null, null, PageRequest.of(0, 200))
            .getContent().stream().map(AdminUserView::getEmail).toList();
        assertEquals(List.of("sort-new@example.com", "sort-mid@example.com", "sort-old@example.com"), order);
    }

    /** 同 createdAt（批量导入）时按 id DESC 兜底，翻页才不会重复 / 漏行。 */
    @Test
    @DisplayName("同 createdAt 时按 id DESC 兜底，次序稳定")
    void sortedByIdDescWhenCreatedAtTies() {
        // 显式指定 id，使排序结果在 Java / SQL 两个口径下都确定（msb 取 0，符号位不影响比较）
        User u1 = saveWithId(new UUID(0L, 1L), "tie-1@example.com");
        User u2 = saveWithId(new UUID(0L, 2L), "tie-2@example.com");
        User u3 = saveWithId(new UUID(0L, 3L), "tie-3@example.com");
        LocalDateTime same = LocalDateTime.of(2026, 2, 2, 2, 2, 2);
        setCreatedAt(u1.getId(), same);
        setCreatedAt(u2.getId(), same);
        setCreatedAt(u3.getId(), same);

        List<String> order = adminUserService.listUsers("tie-", null, null, PageRequest.of(0, 200))
            .getContent().stream().map(AdminUserView::getEmail).toList();
        assertEquals(List.of("tie-3@example.com", "tie-2@example.com", "tie-1@example.com"), order,
            "同时间戳时须按 id 倒序；实际=" + order);
    }

    /**
     * Hibernate 会为 {@code @GeneratedValue(UUID)} 覆盖写入时指定的 id，
     * 故先用 save 落行、再用原生 SQL 把主键改成确定值——这样「id DESC」的期望次序
     * 在 Java 比较、SQL 比较与字符串比较三个口径下完全一致（msb 取 0，不含符号位翻转）。
     */
    private User saveWithId(UUID id, String email) {
        User saved = save(email, User.UserRole.USER, User.UserStatus.ACTIVE);
        int changed = jdbcTemplate.update("update users set id = ? where id = ?", id, saved.getId());
        assertEquals(1, changed, "主键回填失败，夹具不成立");
        User reloaded = userRepository.findById(id).orElseThrow();
        assertEquals(id, reloaded.getId(), "主键未按期望值写入，本用例的确定性前提不成立");
        return reloaded;
    }

    private void setCreatedAt(UUID id, LocalDateTime ts) {
        int updated = jdbcTemplate.update(
            "update users set created_at = ? where id = ?", Timestamp.valueOf(ts), id);
        assertEquals(1, updated, "created_at 回填失败，夹具不成立");
    }

    // ==================== 5. 控制器层参数收敛与非法值 ====================

    @Test
    @DisplayName("size=0 收敛为 1、size=9999 收敛为 200、page=-1 收敛为 0")
    void pageParamsAreClamped() throws Exception {
        mockMvc.perform(get("/api/admin/users").param("size", "0")
                .header("Authorization", "Bearer " + tokenFor(admin)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.size").value(1));

        mockMvc.perform(get("/api/admin/users").param("size", "9999")
                .header("Authorization", "Bearer " + tokenFor(admin)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.size").value(200));

        mockMvc.perform(get("/api/admin/users").param("page", "-1")
                .header("Authorization", "Bearer " + tokenFor(admin)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.number").value(0));
    }

    @Test
    @DisplayName("非法 role / status 返回业务码而非 500")
    void invalidEnumsReturnBusinessCode() throws Exception {
        mockMvc.perform(get("/api/admin/users").param("role", "BOSS")
                .header("Authorization", "Bearer " + tokenFor(admin)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_USER_ROLE"));

        mockMvc.perform(get("/api/admin/users").param("status", "DELETED")
                .header("Authorization", "Bearer " + tokenFor(admin)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_USER_STATUS"));
    }

    @Test
    @DisplayName("空筛选值归一为不过滤（不能拼出恒假条件）")
    void blankFiltersMeanNoFilter() throws Exception {
        mockMvc.perform(get("/api/admin/users")
                .param("email", "   ").param("role", "").param("status", "")
                .header("Authorization", "Bearer " + tokenFor(admin)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    @DisplayName("列表视图不泄漏敏感字段")
    void listViewHasNoSensitiveFields() throws Exception {
        mockMvc.perform(get("/api/admin/users").header("Authorization", "Bearer " + tokenFor(admin)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content[0].passwordHash").doesNotExist())
            .andExpect(jsonPath("$.data.content[0].tokenVersion").doesNotExist())
            .andExpect(jsonPath("$.data.content[0].mfaSecretCipher").doesNotExist());
    }

    @Autowired private com.billing.license.security.JwtTokenService jwtTokenService;

    private String tokenFor(User user) {
        User fresh = userRepository.findById(user.getId()).orElseThrow();
        return jwtTokenService.issue(fresh.getId(), fresh.getTokenVersion());
    }

    @Test
    @DisplayName("夹具自检：确认真有含通配符的邮箱被存进库，否则转义用例等于空跑")
    void fixtureSanityCheck() {
        save("sanity%a_b@example.com", User.UserRole.USER, User.UserStatus.ACTIVE);
        assertTrue(userRepository.findAll().stream()
            .anyMatch(u -> u.getEmail().contains("%") && u.getEmail().contains("_")));
    }
}
