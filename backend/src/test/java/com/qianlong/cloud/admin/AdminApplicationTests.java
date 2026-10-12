package com.qianlong.cloud.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 冒烟测试：验证「应用能起来 + 鉴权拦截器真的生效 + 前端契约形状正确」。
 *
 * <p><b>为什么必须有这个测试：</b>本项目开发中真实踩到过一次严重缺陷 ——
 * 没有注册 {@code SaInterceptor} 时，Sa-Token 的 {@code @SaCheckLogin} /
 * {@code @SaCheckPermission} 会被**静默忽略**（不报错、不打日志），
 * 结果是任何能登录的人都调得动「新建管理账号」「新建角色」。
 * 只有跑起来打真实 HTTP 请求才能发现，单元测试测不到。
 *
 * <p>因此下面专门有一条用例守着这件事：未登录调 {@code /system/users} 必须拿到 40100，
 * 而不是 200。这条断言一旦失效，说明鉴权又变成摆设了。
 *
 * <p>需要数据库可连（{@code 192.168.1.136/qianlong_cloud}）。
 * 若在无网络环境跑，可加 {@code -DskipTests}。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminApplicationTests {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    private final ObjectMapper mapper = new ObjectMapper();

    private String url(String path) {
        return "http://127.0.0.1:" + port + "/api" + path;
    }

    private JsonNode get(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        ResponseEntity<String> resp = rest.exchange(
                url(path), HttpMethod.GET, new HttpEntity<>(headers), String.class);
        try {
            return mapper.readTree(resp.getBody());
        } catch (Exception e) {
            throw new IllegalStateException("响应不是合法 JSON: " + resp.getBody(), e);
        }
    }

    private JsonNode post(String path, String json, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        ResponseEntity<String> resp = rest.exchange(
                url(path), HttpMethod.POST, new HttpEntity<>(json, headers), String.class);
        try {
            return mapper.readTree(resp.getBody());
        } catch (Exception e) {
            throw new IllegalStateException("响应不是合法 JSON: " + resp.getBody(), e);
        }
    }

    private String login(String username, String password) {
        JsonNode body = post("/system/auth/login",
                "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}", null);
        assertThat(body.get("code").asInt())
                .as("登录应当成功：" + body)
                .isZero();
        return body.get("data").get("token").asText();
    }

    /**
     * 守门用例：**鉴权拦截器必须真的在工作**。
     *
     * <p>如果哪天有人把 {@code WebConfig.addInterceptors} 里的 {@code SaInterceptor}
     * 删掉或改错，这条会失败 —— 这正是它存在的意义。
     */
    @Test
    void 未登录访问受保护接口必须被拒绝() {
        JsonNode body = get("/system/users", null);
        assertThat(body.get("code").asInt())
                .as("未登录却拿到了数据，说明 Sa-Token 拦截器没生效（鉴权形同虚设）：" + body)
                .isEqualTo(40100);
    }

    /** 无效 token 同样要被拒绝，不能因为「解析不出 loginId」就放行 */
    @Test
    void 无效token必须被拒绝() {
        JsonNode body = get("/system/users", "this-is-not-a-valid-token");
        assertThat(body.get("code").asInt()).isEqualTo(40100);
    }

    /** 登录后可以拿到列表，且响应形状与前端 PageResult 一致 */
    @Test
    void 登录后可以读取管理账号列表且形状正确() {
        String token = login("admin", "Admin@2026");
        JsonNode data = get("/system/users?page=1&pageSize=10", token).get("data");

        assertThat(data.has("list")).isTrue();
        assertThat(data.has("total")).isTrue();
        assertThat(data.has("page")).isTrue();
        assertThat(data.has("pageSize")).isTrue();

        JsonNode first = data.get("list").get(0);
        // 密码字段绝不能出现在响应里
        assertThat(first.has("password")).isFalse();
        assertThat(first.has("passwordHash")).isFalse();
        // 前端 AdminUser 契约要求的字段
        for (String field : new String[]{"id", "username", "displayName", "roleIds", "enabled",
                "locked", "failedAttempts", "mustChangePassword", "lastLoginTime",
                "lastLoginIP", "loginCount", "createdAt", "updatedAt", "remark"}) {
            assertThat(first.has(field)).as("缺少字段 " + field).isTrue();
        }
    }

    /**
     * 权限校验必须在后端生效。
     *
     * <p>只读观察者（viewer）没有 {@code action:system-user:manage}，
     * 直接打接口必须被拒 —— 前端把按钮置灰**不是**安全边界。
     */
    @Test
    void 无权限账号调用写接口必须被拒绝() {
        String token = login("zhangguochang", "View@2026");
        JsonNode body = post("/system/users",
                "{\"username\":\"smoke_should_fail\",\"password\":\"Smoke@2026\","
                        + "\"roleIds\":[3],\"enabled\":true}", token);
        assertThat(body.get("code").asInt())
                .as("只读账号竟然建成了账号，权限校验失效：" + body)
                .isEqualTo(40300);
    }

    /** 账号不存在与密码错误必须同码同文案（防账号枚举） */
    @Test
    void 账号不存在与密码错误必须同码同文案() {
        JsonNode wrongPwd = post("/system/auth/login",
                "{\"username\":\"admin\",\"password\":\"definitely-wrong\"}", null);
        JsonNode noUser = post("/system/auth/login",
                "{\"username\":\"no_such_user_at_all\",\"password\":\"whatever\"}", null);

        assertThat(wrongPwd.get("code").asInt()).isEqualTo(40101);
        assertThat(noUser.get("code").asInt()).isEqualTo(40101);
        assertThat(wrongPwd.get("message").asText())
                .as("两条分支的文案必须逐字一致，否则可以据此枚举账号")
                .isEqualTo(noUser.get("message").asText());
    }

    /** 审计聚合接口必须返回补零后的 14 天（否则图表 X 轴会跳过空白日） */
    @Test
    void 审计按天聚合返回十四天且日期合法() {
        String token = login("admin", "Admin@2026");
        JsonNode data = get("/system/audit-logs/daily", token).get("data");
        assertThat(data.isArray()).isTrue();
        assertThat(data.size()).isEqualTo(14);
        assertThat(data.get(0).get("date").asText()).matches("\\d{4}-\\d{2}-\\d{2}");
        assertThat(data.get(0).has("high")).isTrue();
        assertThat(data.get(0).has("other")).isTrue();
    }

    /** 下载导出文件时的路径穿越必须被拦下 */
    @Test
    void 导出文件下载路径穿越必须被拒绝() {
        String token = login("admin", "Admin@2026");
        JsonNode body = get("/system/audit-logs/export/download?file=..%2F..%2Fapplication.yml", token);
        assertThat(body.get("code").asInt())
                .as("路径穿越没有被拦下：" + body)
                .isEqualTo(40000);
    }

    /** 前端在无筛选时会传空参数，接口必须给出默认值而不是报错 */
    @Test
    void 空参数查询使用默认分页() {
        String token = login("admin", "Admin@2026");
        JsonNode data = get("/system/audit-logs", token).get("data");
        assertThat(data.get("page").asInt()).isEqualTo(1);
        assertThat(data.get("pageSize").asInt()).isEqualTo(20);
        assertThat(Optional.ofNullable(data.get("list")).isPresent()).isTrue();
    }
}
