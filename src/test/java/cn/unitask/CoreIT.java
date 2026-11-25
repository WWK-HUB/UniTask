package cn.unitask;

import cn.unitask.allocation.AllocationService;
import cn.unitask.task.TaskData;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CoreIT extends IntegrationSupport {
    @Autowired AllocationService allocations;
    @Autowired TaskData taskData;

    @Test
    void mysql84SchemaAndUtc() {
        assertThat(jdbc.queryForObject("SELECT VERSION()",String.class)).startsWith("8.4.");
        assertThat(jdbc.queryForObject("SELECT @@session.time_zone",String.class)).isIn("+00:00","UTC");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()",Integer.class))
                .isEqualTo(12);
    }

    @Test
    void t01_t02_signupLoginAndSettings() throws Exception {
        String username = name();
        mvc.perform(post("/register").with(csrf()).param("username",username.toUpperCase(Locale.ROOT))
                .param("nickname","课堂").param("password",PASSWORD)).andExpect(status().is3xxRedirection());
        assertThat(users.find(username).getPasswordHash()).startsWith("$2").isNotEqualTo(PASSWORD);
        mvc.perform(post("/register").with(csrf()).param("username",username).param("nickname","重复").param("password",PASSWORD))
                .andExpect(status().isConflict());
        mvc.perform(post("/register").with(csrf()).param("username",name()).param("nickname","字节限制")
                .param("password","课".repeat(25))).andExpect(status().isBadRequest());
        mvc.perform(post("/login").with(csrf()).param("username",username).param("password","Wrong-Pass0"))
                .andExpect(redirectedUrl("/login?error"));
        var login = mvc.perform(post("/login").with(csrf()).param("username",username.toUpperCase(Locale.ROOT))
                .param("password",PASSWORD)).andExpect(redirectedUrl("/dashboard")).andReturn();
        var session = (MockHttpSession) login.getRequest().getSession(false);
        mvc.perform(get("/api/me").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(username)).andExpect(jsonPath("$.passwordHash").doesNotExist());
        mvc.perform(patch("/api/me").session(session).contentType("application/json").content("{\"nickname\":\"新名称\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/me").session(session).with(csrf().asHeader()).contentType("application/json").content("{\"nickname\":\"新名称\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.nickname").value("新名称"));
        mvc.perform(post("/api/me/password").session(session).with(csrf().asHeader()).contentType("application/json")
                .content(json.writeValueAsString(Map.of("currentPassword",PASSWORD,"newPassword","Changed-Fixture2"))))
                .andExpect(status().isNoContent());
        assertThat(session.isInvalid()).isTrue();
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
        var after = mvc.perform(post("/login").with(csrf()).param("username",username).param("password","Changed-Fixture2"))
                .andExpect(redirectedUrl("/dashboard")).andReturn();
        var afterSession = (MockHttpSession) after.getRequest().getSession(false);
        mvc.perform(post("/logout").session(afterSession).with(csrf())).andExpect(redirectedUrl("/login?logout"));
        assertThat(afterSession.isInvalid()).isTrue();
    }

    @Test
    void t03_t04_t05_t06_projectRolesAndInvitationRaces() throws Exception {
        var a = actor(); var b = actor(); var c = actor(); var outside = actor();
        long p = project(a.id());
        long invite = id(projects.invite(a.id(),p,b.username()));
        mvc.perform(get("/api/projects/" + p).with(user(principal(b)))).andExpect(status().isNotFound());
        assertThat(failureCode(() -> projects.respond(outside.id(),invite,true))).isEqualTo(404);
        projects.respond(b.id(),invite,true);
        mvc.perform(get("/api/projects/" + p).with(user(principal(b)))).andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("MEMBER"));
        long other = project(b.id());
        join(b.id(),other,a);
        assertThat(projects.detail(b.id(),other,"").get("role")).isEqualTo("LEADER");
        assertThat(failureCode(() -> projects.edit(a.id(),other,node(Map.of("name","越权")),""))).isEqualTo(403);

        assertThat(race(() -> failureCode(() -> projects.invite(a.id(),p,c.username())),
                        () -> failureCode(() -> projects.invite(a.id(),p,c.username()))))
                .containsExactlyInAnyOrder(200,409);
        long pending = id(projects.invitations(a.id(),p,"PENDING",0,20).items().get(0));
        projects.respond(c.id(),pending,false);
        long second = id(projects.invite(a.id(),p,c.username()));
        assertThat(second).isNotEqualTo(pending);
        projects.revoke(a.id(),p,second);
        assertThat(failureCode(() -> projects.respond(c.id(),second,true))).isEqualTo(409);

        long task = task(a.id(),p,1);
        tasks.claim(b.id(),task,true,"");
        assertThat(failureCode(() -> projects.remove(a.id(),p,b.id()))).isEqualTo(409);
        projects.transfer(a.id(),p,b.id(),"");
        assertThat(failureCode(() -> projects.invite(a.id(),p,c.username()))).isEqualTo(403);
        assertThat(projects.detail(a.id(),p,"").get("role")).isEqualTo("MEMBER");
        assertThat(projects.detail(b.id(),p,"").get("role")).isEqualTo("LEADER");
    }

    @Test
    void t07_t08_t09_t10_mysqlLastSeatAndTaskValidation() throws Exception {
        var a = actor(); var b = actor(); var c = actor();
        long p = project(a.id());
        join(a.id(),p,b);
        join(a.id(),p,c);
        try (var batch = files.stage(null)) {
            assertThat(failureCode(() -> tasks.create(a.id(),p,node(Map.of("title","任务","description","","requiredCount",1)),batch,""))).isEqualTo(400);
            assertThat(failureCode(() -> tasks.create(a.id(),p,node(Map.of("title","任务","description","合法","requiredCount",0)),batch,""))).isEqualTo(400);
            assertThat(failureCode(() -> tasks.create(a.id(),p,node(Map.of("title","任务","description","合法","requiredCount",4)),batch,""))).isEqualTo(400);
        }
        long task = task(a.id(),p,1);
        assertThat(race(() -> failureCode(() -> tasks.claim(b.id(),task,true,"")),
                        () -> failureCode(() -> tasks.claim(c.id(),task,true,""))))
                .containsExactlyInAnyOrder(200,409);
        assertThat(taskData.assigneeIds(task)).hasSize(1);
        long winner = taskData.assigneeIds(task).get(0);
        assertThat(failureCode(() -> tasks.claim(winner,task,true,""))).isEqualTo(409);
        assertThat(failureCode(() -> tasks.setAssignees(a.id(),task,Set.of(b.id(),c.id()),""))).isEqualTo(409);
        tasks.claim(winner,task,false,"");
        assertThat(taskData.assigneeIds(task)).isEmpty();
        tasks.delete(a.id(),task);
        assertThat(failureCode(() -> tasks.detail(a.id(),task,""))).isEqualTo(404);
    }

    @Test
    void t11_t12_t13_allocationIsServerOwnedAndAtomic() {
        var a = actor(); var b = actor(); var c = actor();
        long p = project(a.id());
        join(a.id(),p,b); join(a.id(),p,c);
        long task = task(a.id(),p,3);
        tasks.claim(b.id(),task,true,"");
        var preview = allocations.preview(a.id(),p,false,Set.of(task));
        assertThat(taskData.assigneeIds(task)).containsExactly(b.id());
        var assignments = json.valueToTree(preview).get("assignments");
        assertThat(assignments.get(0).get("remainingSlots").asInt()).isEqualTo(1);
        assertThat(assignments.get(0).get("newUserIds").get(0).asLong()).isEqualTo(c.id());
        String token = (String)preview.get("previewToken");
        allocations.confirm(a.id(),p,token);
        assertThat(taskData.assigneeIds(task)).containsExactlyInAnyOrder(b.id(),c.id());
        assertThat(failureCode(() -> allocations.confirm(a.id(),p,token))).isEqualTo(409);

        long first = task(a.id(),p,1);
        long last = task(a.id(),p,1);
        String conflict = (String)allocations.preview(a.id(),p,false,Set.of(first,last)).get("previewToken");
        tasks.claim(c.id(),last,true,"");
        assertThat(failureCode(() -> allocations.confirm(a.id(),p,conflict))).isEqualTo(409);
        assertThat(taskData.assigneeIds(first)).isEmpty();
        String expired = (String)allocations.preview(a.id(),p,true,Set.of(first)).get("previewToken");
        jdbc.update("UPDATE allocation_previews SET expires_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE project_id=? AND consumed_at IS NULL",p);
        assertThat(failureCode(() -> allocations.confirm(a.id(),p,expired))).isEqualTo(409);
    }

    static java.util.List<Integer> race(Callable<Integer> left, Callable<Integer> right) throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        try {
            var a = pool.submit(() -> { ready.countDown(); ready.await(); return left.call(); });
            var b = pool.submit(() -> { ready.countDown(); ready.await(); return right.call(); });
            return java.util.List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); }
    }
}
