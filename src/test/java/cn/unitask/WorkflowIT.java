package cn.unitask;

import cn.unitask.comment.WorkflowService;
import cn.unitask.notification.DeadlineReminders;
import cn.unitask.notification.NotificationService;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class WorkflowIT extends IntegrationSupport {
    @Autowired WorkflowService flow;
    @Autowired NotificationService notices;
    @Autowired DeadlineReminders deadlines;
    @Autowired TransactionTemplate transaction;
    @Autowired StringRedisTemplate redis;

    private static MockMultipartFile file(String name,byte[] data) {
        return new MockMultipartFile("files",name,"application/octet-stream",data);
    }

    @Test
    void t14_t15_t16_t17_t18_t26_t33_commentsAttachmentsAndNotificationOwnership() throws Exception {
        var a=actor(); var b=actor(); var c=actor(); var d=actor();
        long p=project(a.id());
        join(a.id(),p,b); join(a.id(),p,c);
        long t=task(a.id(),p,1);
        tasks.claim(b.id(),t,true,"");
        try (var batch=files.stage(null)) {
            assertThat(failureCode(() -> flow.comment(b.id(),t,"   ",batch,""))).isEqualTo(400);
            flow.comment(b.id(),t,"第一条负责人进展",batch,"");
            flow.comment(c.id(),t,"小组正常讨论",batch,"");
        }
        var uploads = new LinkedHashMap<String,byte[]>();
        uploads.put("bundle.zip",Fixtures.zip(Map.of("readme.txt","test")));
        uploads.put("截图.png",Fixtures.png());
        uploads.put("示例.docx",Fixtures.docx());
        uploads.put("材料.pdf",Fixtures.pdf());
        uploads.put("视频.mp4",Fixtures.video());
        uploads.put("../../备注.txt","附件内容".getBytes(StandardCharsets.UTF_8));
        var form = multipart("/api/tasks/" + t + "/comments")
                .file(new MockMultipartFile("data","","application/json","{}".getBytes(StandardCharsets.UTF_8)));
        uploads.forEach((filename,bytes) -> form.file(file(filename,bytes)));
        var response = mvc.perform(form.with(user(principal(b))).with(csrf().asHeader()))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.kind").value("PROGRESS"))
                .andExpect(jsonPath("$.attachments.length()").value(6)).andExpect(jsonPath("$.attachments[0].storageKey").doesNotExist())
                .andReturn();
        var entries = json.readTree(response.getResponse().getContentAsByteArray()).get("attachments");
        int i=0;
        for (byte[] expected : uploads.values()) {
            long fileId=entries.get(i++).get("id").asLong();
            var download=files.download(c.id(),fileId);
            assertThat(Files.readAllBytes(download.path())).isEqualTo(expected);
            assertThat(failureCode(() -> files.download(d.id(),fileId))).isEqualTo(404);
        }
        assertThat(entries.get(5).get("originalName").asText()).isEqualTo("备注.txt");
        assertThat(entries.get(1).get("previewable").asBoolean()).isTrue();
        assertThat(entries.get(2).get("previewable").asBoolean()).isFalse();
        assertThat(entries.get(4).get("previewable").asBoolean()).isTrue();
        assertThat(files.projectFiles(a.id(),p,t,b.id(),"document",0,20,"").total()).isEqualTo(2);
        assertThat(files.projectFiles(a.id(),p,t,b.id(),"video",0,20,"").total()).isEqualTo(1);
        assertThat(tasks.mine(b.id(),p,"IN_PROGRESS",null,0,20).total()).isEqualTo(1);
        assertThat(tasks.mine(c.id(),p,null,null,0,20).total()).isZero();
        assertThat(projects.stats(a.id(),p).get("members")).asList().hasSize(3);
        assertThat(failureCode(() -> files.stage(List.of(file("fake.pdf","not pdf".getBytes(StandardCharsets.UTF_8)))))).isEqualTo(415);
        assertThat(failureCode(() -> files.stage(List.of(file("bad.exe",new byte[]{1,2,3}))))).isEqualTo(415);
        assertThat(failureCode(() -> tasks.claim(b.id(),t,false,""))).isEqualTo(409);

        var comments = notices.inbox(a.id(),null,0,20,"").items().stream()
                .filter(n -> n.get("type").equals("PROGRESS_COMMENT")).toList();
        assertThat(comments).hasSize(2);
        assertThat(comments.get(0).get("commentId")).isNotEqualTo(comments.get(1).get("commentId"));
        long noticeId=id(comments.get(0));
        assertThat(failureCode(() -> notices.mark(d.id(),noticeId))).isEqualTo(404);
        mvc.perform(patch("/api/notifications/" + noticeId + "/read").with(user(principal(d))).with(csrf().asHeader()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        assertThat(notices.unread(a.id())).isPositive();
        notices.markAll(a.id());
        assertThat(notices.unread(a.id())).isZero();
    }

    @Test
    void t19_t20_t21_t22_t23_t25_t34_reviewLifecycleAndHistory() throws Exception {
        var a=actor(); var b=actor(); var c=actor();
        long p=project(a.id());
        join(a.id(),p,b); join(a.id(),p,c);
        long t=task(a.id(),p,2);
        tasks.claim(b.id(),t,true,"");
        assertThat(failureCode(() -> projects.archive(a.id(),p,""))).isEqualTo(409);
        try (var batch=files.stage(null)) {
            assertThat(failureCode(() -> flow.submit(c.id(),t,"讨论者",batch,""))).isEqualTo(403);
            assertThat(failureCode(() -> flow.submit(b.id(),t,"未满员",batch,""))).isEqualTo(409);
        }
        tasks.claim(c.id(),t,true,"");
        assertThat(failureCode(() -> tasks.edit(a.id(),t,node(Map.of("requiredCount",1)),""))).isEqualTo(409);
        tasks.edit(a.id(),t,node(Map.of("description","补充任务要求")),"");
        assertThat(notices.inbox(b.id(),null,0,20,"").items()).anyMatch(n -> n.get("type").equals("TASK_UPDATED"));
        long first;
        try (var batch=files.stage(null)) {
            assertThat(failureCode(() -> flow.submit(b.id(),t,null,batch,""))).isEqualTo(409);
            flow.comment(b.id(),t,"初稿",batch,"");
            first=((Number)flow.submit(b.id(),t,null,batch,"").get("submissionId")).longValue();
            flow.comment(b.id(),t,"审核期的补充",batch,"");
        }
        assertThat(failureCode(() -> tasks.edit(a.id(),t,node(Map.of("title","审核期间禁止编辑")),""))).isEqualTo(409);
        assertThat(failureCode(() -> flow.review(a.id(),t,1,first,"REJECTED","  ",""))).isEqualTo(400);
        flow.review(a.id(),t,1,first,"REJECTED","请补充测试结论","");
        try (var batch=files.stage(null)) {
            assertThat(failureCode(() -> flow.submit(b.id(),t,null,batch,""))).isEqualTo(409);
        }
        assertThat(failureCode(() -> flow.remind(c.id(),t,null))).isEqualTo(403);
        assertThat(flow.remind(a.id(),t,Set.of(b.id())).get("sent")).isEqualTo(List.of(b.id()));
        assertThat(failureCode(() -> flow.remind(a.id(),t,Set.of(b.id())))).isEqualTo(429);
        long second;
        long attachment;
        try (var batch=files.stage(List.of(file("修订.txt",new byte[]{65,66,67})))) {
            var update=flow.comment(c.id(),t,"已补测",batch,"");
            attachment=((Number)((Map<?,?>)((List<?>)update.get("attachments")).get(0)).get("id")).longValue();
        }
        try (var batch=files.stage(null)) {
            second=((Number)flow.submit(b.id(),t,null,batch,"").get("submissionId")).longValue();
        }
        assertThat(failureCode(() -> flow.review(a.id(),t,1,first,"APPROVED",null,""))).isEqualTo(409);
        assertThat(CoreIT.race(() -> failureCode(() -> flow.review(a.id(),t,2,second,"APPROVED",null,"")),
                        () -> failureCode(() -> flow.review(a.id(),t,2,second,"APPROVED",null,""))))
                .containsExactlyInAnyOrder(200,409);
        assertThat(flow.timeline(a.id(),t,0,20,"").items()).anyMatch(row -> row.get("body") != null && row.get("body").equals("初稿"));
        assertThat(flow.timeline(a.id(),t,0,20,"").items()).anyMatch(row -> row.get("review") instanceof Map<?,?> review
                && "请补充测试结论".equals(review.get("reason")));
        projects.remove(a.id(),p,c.id());
        assertThat(failureCode(() -> tasks.detail(c.id(),t,""))).isEqualTo(404);
        assertThat(failureCode(() -> files.download(c.id(),attachment))).isEqualTo(404);
        assertThat(flow.timeline(a.id(),t,0,20,"").items()).anyMatch(row -> "已补测".equals(row.get("body")));
        projects.archive(a.id(),p,"");
        try (var batch=files.stage(null)) {
            assertThat(failureCode(() -> flow.comment(b.id(),t,"归档后评论",batch,""))).isEqualTo(409);
        }
        assertThat(Files.readAllBytes(files.download(b.id(),attachment).path())).containsExactly(65,66,67);
    }

    @Test
    void t24_zeroAndCompletionRatio() {
        var a=actor();
        long p=project(a.id());
        assertThat(projects.detail(a.id(),p,"").get("progressPercent")).isEqualTo(0L);
        for (int index=0;index<6;index++) {
            long t=task(a.id(),p,1);
            if (index<2) complete(a.id(),t);
        }
        var summary=projects.detail(a.id(),p,"");
        assertThat(summary.get("taskCount")).isEqualTo(6L);
        assertThat(summary.get("progressPercent")).isEqualTo(33L);
    }

    @Test
    void t27_scheduledDeduplicationAndDeadlineChange() {
        var a=actor(); var b=actor();
        long p=project(a.id());
        join(a.id(),p,b);
        long t=task(a.id(),p,1);
        long done=task(a.id(),p,1);
        var now=Instant.parse("2031-02-03T01:00:00Z");
        projects.edit(a.id(),p,node(Map.of("deadline",now.plusSeconds(18_000).toString())),"");
        tasks.edit(a.id(),t,node(Map.of("deadline",now.plusSeconds(3600).toString())),"");
        tasks.edit(a.id(),done,node(Map.of("deadline",now.plusSeconds(7200).toString())),"");
        tasks.claim(b.id(),t,true,"");
        complete(a.id(),done);
        deadlines.runAt(now);
        deadlines.runAt(now);
        assertThat(due(p,"PROJECT_DUE")).isEqualTo(2);
        assertThat(due(p,"TASK_DUE")).isEqualTo(1);
        tasks.edit(a.id(),t,node(Map.of("deadline",now.plusSeconds(4000).toString())),"");
        deadlines.runAt(now);
        assertThat(due(p,"TASK_DUE")).isEqualTo(2);
        long empty=project(a.id());
        projects.edit(a.id(),empty,node(Map.of("deadline",now.plusSeconds(4000).toString())),"");
        projects.archive(a.id(),empty,"");
        deadlines.runAt(now);
        assertThat(due(empty,"PROJECT_DUE")).isZero();
    }

    @Test
    void t28_databaseFallback() throws Exception {
        if ("1".equals(System.getenv("UNITASK_EXPECT_REDIS_DOWN"))) {
            assertThatThrownBy(() -> { try (var socket=new Socket()) { socket.connect(new InetSocketAddress("127.0.0.1",6380),500); } })
                    .isInstanceOf(java.io.IOException.class);
        }
        var a=actor(); var b=actor();
        long p=project(a.id()); join(a.id(),p,b);
        long t=task(a.id(),p,1); tasks.claim(b.id(),t,true,"");
        notices.markAll(a.id());
        assertThat(notices.unread(a.id())).isZero();
        if (!"1".equals(System.getenv("UNITASK_EXPECT_REDIS_DOWN"))) {
            assertThat(redis.opsForValue().get("unitask:unread:" + a.id())).isEqualTo("0");
        }
        try (var batch=files.stage(null)) { flow.comment(b.id(),t,"Redis 时的真实写入",batch,""); }
        assertThat(notices.unread(a.id())).isEqualTo(1);
        assertThat(notices.inbox(a.id(),true,0,20,"").items()).hasSize(1);
        assertThat(flow.remind(a.id(),t,null).get("sent")).isEqualTo(List.of(b.id()));
        assertThat(failureCode(() -> flow.remind(a.id(),t,null))).isEqualTo(429);
    }

    @Test
    void t30_t35_filesAndMetadataRollbackAndLimits() throws Exception {
        var a=actor(); var b=actor(); long p=project(a.id()); join(a.id(),p,b);
        long t=task(a.id(),p,1); tasks.claim(b.id(),t,true,"");
        var objects=UPLOADS.resolve("objects");
        var parked=UPLOADS.resolve("objects-temporary-" + p);
        Files.move(objects,parked);
        try (var batch=files.stage(List.of(file("test.txt",new byte[]{65})))) {
            assertThat(failureCode(() -> flow.comment(b.id(),t,"移动失败",batch,""))).isEqualTo(500);
        } finally { Files.move(parked,objects); }

        long before=objectCount();
        transaction.executeWithoutResult(status -> {
            try (var batch=files.stage(List.of(file("test.txt",new byte[]{65})))) {
                flow.comment(b.id(),t,"回滚",batch,"");
                status.setRollbackOnly();
            }
        });
        assertThat(objectCount()).isEqualTo(before);
        assertThat(flow.timeline(a.id(),t,0,20,"").total()).isZero();
        assertThat(notices.inbox(a.id(),null,0,20,"").items()).noneMatch(x -> "PROGRESS_COMMENT".equals(x.get("type")));
        assertThatThrownBy(() -> files.stage(java.util.Collections.nCopies(11,file("small.txt",new byte[]{65}))))
                .matches(ex -> ex instanceof cn.unitask.common.Problem problem && problem.status()==413);
        byte[] medium="a".repeat(9000).getBytes(StandardCharsets.UTF_8);
        assertThat(failureCode(() -> files.stage(List.of(file("a.txt",medium),file("b.txt",medium))))).isEqualTo(413);
        assertThat(failureCode(() -> files.stage(List.of(file("big.txt","x".repeat(13000).getBytes(StandardCharsets.UTF_8)))))).isEqualTo(413);
        for (int i=0;i<3;i++) {
            try (var batch=files.stage(List.of(file("medium.txt",medium)))) { flow.comment(b.id(),t,null,batch,""); }
        }
        try (var batch=files.stage(List.of(file("medium.txt",medium)))) {
            assertThat(failureCode(() -> flow.comment(b.id(),t,null,batch,""))).isEqualTo(413);
        }
        assertThat(flow.timeline(a.id(),t,0,20,"").total()).isEqualTo(3);
        assertThat(objectCount()).isEqualTo(before+3);
    }

    private long objectCount() throws Exception {
        try (var files=Files.list(UPLOADS.resolve("objects"))) { return files.count(); }
    }

    private int due(long p,String type) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE project_id=? AND type=?",Integer.class,p,type);
    }

    private void complete(long actor,long task) {
        tasks.claim(actor,task,true,"");
        try (var batch=files.stage(null)) {
            long submission=((Number)flow.submit(actor,task,"已完成",batch,"").get("submissionId")).longValue();
            flow.review(actor,task,1,submission,"APPROVED",null,"");
        }
    }
}
