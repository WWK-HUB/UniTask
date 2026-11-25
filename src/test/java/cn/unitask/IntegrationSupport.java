package cn.unitask;

import cn.unitask.attachment.AttachmentService;
import cn.unitask.common.Problem;
import cn.unitask.project.ProjectService;
import cn.unitask.security.AccountPrincipal;
import cn.unitask.task.TaskService;
import cn.unitask.user.UserService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
abstract class IntegrationSupport {
    @Autowired UserService users;
    @Autowired ProjectService projects;
    @Autowired TaskService tasks;
    @Autowired AttachmentService files;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    static final String PASSWORD = "Test-Fixture-Only1";
    static final Path UPLOADS = tempUploads();

    @DynamicPropertySource
    static void options(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> testUrl());
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("UNITASK_TEST_DB_USER","unitask_test"));
        registry.add("spring.datasource.password", () -> testPassword());
        registry.add("unitask.files.root", UPLOADS::toString);
        registry.add("unitask.files.max-project-bytes", () -> "32768");
        registry.add("unitask.files.max-batch-bytes", () -> "16384");
        registry.add("unitask.files.max-file-bytes", () -> "12288");
    }

    static String testPassword() {
        String password = System.getenv("UNITASK_TEST_DB_PASSWORD");
        if (password == null || password.isBlank()) throw new IllegalStateException("Set UNITASK_TEST_DB_PASSWORD for dedicated MySQL 8.4 test schema");
        return password;
    }

    static String testUrl() {
        String value = System.getenv().getOrDefault("UNITASK_TEST_DB_URL",
                "jdbc:mysql://127.0.0.1:3307/unitask_test?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true");
        if (!value.matches("jdbc:mysql://[^/]+/unitask_test(?:\\?.*)?")) throw new IllegalStateException("Use the unitask_test schema");
        return value;
    }

    private static Path tempUploads() {
        try {
            Files.createDirectories(Path.of("target"));
            return Files.createTempDirectory(Path.of("target"),"integration-uploads-").toAbsolutePath();
        } catch (IOException e) { throw new IllegalStateException(e); }
    }

    static long id(Map<String,Object> row) { return ((Number) row.get("id")).longValue(); }
    static String name() { return "test_" + UUID.randomUUID().toString().substring(0,8); }
    UserService.Profile actor() { return users.register(name(),"测试用户",PASSWORD); }
    AccountPrincipal principal(UserService.Profile user) { return new AccountPrincipal(users.find(user.username())); }

    long project(long owner) {
        try (var batch = files.stage(null)) {
            return id(projects.create(owner,json.valueToTree(Map.of("name","测试项目","course","企业 Java")),batch,""));
        }
    }

    void join(long owner, long project, UserService.Profile member) {
        long invite = id(projects.invite(owner,project,member.username()));
        projects.respond(member.id(),invite,true);
    }

    long task(long owner, long project, int seats) {
        try (var batch = files.stage(null)) {
            return id(tasks.create(owner,project,json.valueToTree(Map.of(
                    "title","测试任务","description","完成实际验收","requiredCount",seats)),batch,""));
        }
    }

    JsonNode node(Map<String,?> data) { return json.valueToTree(data); }
    static int failureCode(Runnable request) {
        try { request.run(); return 200; }
        catch (Problem problem) { return problem.status(); }
    }
}
