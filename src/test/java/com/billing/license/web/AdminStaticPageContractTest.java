package com.billing.license.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 管理台静态页（原生 ES5，无构建期校验）的<b>契约自证</b>（QA 独立复核，plan-7.0 P0 / T03）。
 *
 * <p>静态页没有编译器，{@code $('xxx')} 取到 null 只在运行时炸，且往往表现为「点了没反应」——
 * 手工核对 76 处也没有回归保护。这里把核对做成用例：
 * <ol>
 *   <li>{@code admin.js} 里出现的每一个 DOM id 字面量，都必须在 {@code index.html} 里存在；</li>
 *   <li>动态拼接的 id（{@code renderPanel(prefix + 'Error')}）按前缀逐项核对；</li>
 *   <li>每个 {@code data-tab} 都要有对应 {@code panel-<tab>} 面板；</li>
 *   <li>前端调用的路径 / 参数 / 请求体与后端契约一致，且<b>密码不出现在查询串里</b>；</li>
 *   <li>新标记用到的 CSS 类必须在 {@code admin.css} 中有定义（否则弹层会裸奔）。</li>
 * </ol>
 */
class AdminStaticPageContractTest {

    private static final Pattern ID_IN_HTML = Pattern.compile("\\bid=\"([^\"]+)\"");
    private static final Pattern DOLLAR_REF = Pattern.compile("\\$\\(\\s*'([^']+)'\\s*\\)");
    private static final Pattern DOLLAR_REF_DQ = Pattern.compile("\\$\\(\\s*\"([^\"]+)\"\\s*\\)");
    private static final Pattern BY_ID = Pattern.compile("getElementById\\(\\s*'([^']+)'\\s*\\)");
    private static final Pattern PANEL_PREFIX_CALL = Pattern.compile("renderPanel\\(\\s*'([A-Za-z0-9_-]+)'");
    private static final Pattern DATA_TAB = Pattern.compile("data-tab=\"([^\"]+)\"");
    private static final Pattern CLASS_ATTR = Pattern.compile("class=\"([^\"]+)\"");

    private static String read(String classpath) throws IOException {
        ClassPathResource resource = new ClassPathResource(classpath);
        assertTrue(resource.exists(), "静态资源缺失：" + classpath);
        return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    /** 去掉注释，避免注释里的示例代码造成假阳性 / 假阴性。 */
    private static String stripComments(String js) {
        String noBlock = js.replaceAll("(?s)/\\*.*?\\*/", "");
        return noBlock.replaceAll("//[^\\n]*", "");
    }

    private static Set<String> findAll(Pattern pattern, String text) {
        Set<String> found = new LinkedHashSet<>();
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            found.add(m.group(1));
        }
        return found;
    }

    @Test
    @DisplayName("admin.js 引用的每一个 DOM id 都必须在 index.html 中存在")
    void everyDomIdReferencedByScriptExistsInHtml() throws IOException {
        String html = read("static/admin/index.html");
        String js = stripComments(read("static/admin/admin.js"));

        Set<String> htmlIds = findAll(ID_IN_HTML, html);
        Set<String> refs = new LinkedHashSet<>();
        refs.addAll(findAll(DOLLAR_REF, js));
        refs.addAll(findAll(DOLLAR_REF_DQ, js));
        refs.addAll(findAll(BY_ID, js));

        assertFalse(refs.isEmpty(), "未解析到任何 id 引用，正则失效了");
        for (String id : refs) {
            assertTrue(htmlIds.contains(id),
                "admin.js 引用了 index.html 中不存在的 id：" + id);
        }
        // 与工程师的自查口径对齐（其称 76 处），这里独立复核
        assertTrue(refs.size() >= 70, "解析到的 id 引用数异常少：" + refs.size());
    }

    @Test
    @DisplayName("动态拼接的面板 id（prefix + Error/TableWrap/Empty）逐项存在")
    void dynamicallyBuiltPanelIdsExist() throws IOException {
        String html = read("static/admin/index.html");
        String js = stripComments(read("static/admin/admin.js"));
        Set<String> htmlIds = findAll(ID_IN_HTML, html);

        Set<String> prefixes = findAll(PANEL_PREFIX_CALL, js);
        assertFalse(prefixes.isEmpty(), "未找到 renderPanel 调用");
        for (String prefix : prefixes) {
            for (String suffix : List.of("Error", "TableWrap", "Empty")) {
                assertTrue(htmlIds.contains(prefix + suffix),
                    "index.html 缺少动态拼接所需的 id：" + prefix + suffix);
            }
        }
    }

    @Test
    @DisplayName("每个 data-tab 都有对应的 panel-<tab> 面板")
    void everyTabHasPanelSection() throws IOException {
        String html = read("static/admin/index.html");
        Set<String> htmlIds = findAll(ID_IN_HTML, html);
        Set<String> tabs = findAll(DATA_TAB, html);

        assertTrue(tabs.contains("users"), "新增的「用户管理」tab 必须存在");
        for (String tab : tabs) {
            assertTrue(htmlIds.contains("panel-" + tab), "缺少面板：panel-" + tab);
        }
    }

    // ==================== 前后端契约 ====================

    @Test
    @DisplayName("用户管理相关调用的路径 / 参数 / 请求体与后端契约一致")
    void userManagementApiContractMatchesBackend() throws IOException {
        String js = stripComments(read("static/admin/admin.js"));

        // GET /api/admin/users?email=&role=&status=&page=&size=
        assertTrue(js.contains("/api/admin/users?"), "列表查询入口缺失");
        assertTrue(js.contains("'email='"), "缺少 email 参数");
        assertTrue(js.contains("'role='"), "缺少 role 参数");
        assertTrue(js.contains("'status='"), "缺少 status 参数");
        assertTrue(js.contains("'page='"), "缺少 page 参数");
        assertTrue(js.contains("'size='"), "缺少 size 参数");

        // PATCH .../role?role=  与  PATCH .../status?status=
        assertTrue(js.contains("+ '/role?role='"), "改角色路径与后端 @PatchMapping(\"/users/{userId}/role\") 不一致");
        assertTrue(js.contains("+ '/status?status='"), "改状态路径与后端 @PatchMapping(\"/users/{userId}/status\") 不一致");

        // POST .../password/reset，body 只含 newPassword
        assertTrue(js.contains("+ '/password/reset'"), "代重置路径缺失");
        assertTrue(js.contains("{ newPassword: pwd }"),
            "代重置请求体须为 { newPassword } ，与 AdminPasswordResetRequest 一致");

        // 密码绝不能进查询串（会被访问日志 / 浏览器历史记录留存）
        assertFalse(js.matches("(?s).*password/reset\\?.*"),
            "代重置不得把密码放进查询串");
        assertFalse(js.contains("newPassword="), "密码不得出现在查询串中");
    }

    @Test
    @DisplayName("前端预检口径与后端 PasswordPolicy 一致（8–72 位、含字母与数字）")
    void clientSidePolicyMatchesServerSide() throws IOException {
        String js = stripComments(read("static/admin/admin.js"));
        assertTrue(js.contains("pwd.length < 8"), "前端下限须为 8");
        assertTrue(js.contains("pwd.length > 72"), "前端上限须为 72");
        assertTrue(js.contains("/[A-Za-z]/.test(pwd)"), "前端须校验字母");
        assertTrue(js.contains("/[0-9]/.test(pwd)"), "前端须校验数字");
    }

    @Test
    @DisplayName("代重置弹层不得把密码回显或持久化")
    void resetModalMustNotEchoOrPersistPassword() throws IOException {
        String js = stripComments(read("static/admin/admin.js"));
        assertFalse(js.contains("localStorage.setItem(TOKEN_STORAGE, pwd"), "密码不得落本地存储");
        assertTrue(js.contains("closeResetModal"), "关闭弹层须清空输入");
        // 关闭时清空两个输入框
        assertTrue(js.contains("$('resetPwdNew').value = ''"), "关闭弹层须清空新密码输入");
        assertTrue(js.contains("$('resetPwdConfirm').value = ''"), "关闭弹层须清空确认输入");
    }

    @Test
    @DisplayName("新增标记用到的 CSS 类必须在 admin.css 中定义")
    void cssClassesUsedByNewMarkupAreDefined() throws IOException {
        String html = read("static/admin/index.html");
        String js = stripComments(read("static/admin/admin.js"));
        String css = read("static/admin/admin.css");

        Set<String> used = new LinkedHashSet<>();
        for (String attr : findAll(CLASS_ATTR, html)) {
            for (String cls : attr.split("\\s+")) {
                if (cls.startsWith("user-") || cls.startsWith("row-") || cls.equals("pager__info")) {
                    used.add(cls);
                }
            }
        }
        // JS 拼接出来的类同样要算（弹层与行内控件）
        for (String cls : List.of("user-modal", "user-modal__box", "user-modal__title",
            "row-select", "row-actions", "badge--muted", "lic-result", "pager__info")) {
            used.add(cls);
        }
        assertFalse(used.isEmpty());
        for (String cls : used) {
            assertTrue(css.contains("." + cls), "admin.css 未定义类：." + cls);
        }
        // 弹层与行内控件也可能只在 JS 里出现，故再核一遍关键类
        for (String cls : List.of("user-modal", "row-select", "row-actions")) {
            assertTrue(js.contains(cls) || html.contains(cls), "类 " + cls + " 没有任何使用方");
        }
    }
}
