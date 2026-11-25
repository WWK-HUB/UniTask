package cn.unitask.config;

import cn.unitask.attachment.AttachmentService;
import cn.unitask.comment.WorkflowService;
import cn.unitask.project.ProjectService;
import cn.unitask.task.TaskService;
import cn.unitask.user.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Component
@Profile("dev")
@Order(100)
class DemoSeed implements ApplicationRunner {
    private final UserService users;
    private final ProjectService projects;
    private final TaskService tasks;
    private final WorkflowService flow;
    private final AttachmentService files;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final Clock clock;

    DemoSeed(UserService users,ProjectService projects,TaskService tasks,WorkflowService flow,
             AttachmentService files,TransactionTemplate transactions,ObjectMapper json,Clock clock) {
        this.users = users;
        this.projects = projects;
        this.tasks = tasks;
        this.flow = flow;
        this.files = files;
        this.transactions = transactions;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption("unitask-seed")) return;
        String prefix = "demo_" + UUID.randomUUID().toString().substring(0,8);
        String password = "UniTask-" + UUID.randomUUID().toString().substring(0,18);
        transactions.executeWithoutResult(status -> create(prefix,password));
        System.out.println("演示数据已写入。临时演示账户（课堂专用）");
        System.out.println("组长 " + prefix + "_a · 成员 " + prefix + "_b / " + prefix + "_c · 外部用户 " + prefix + "_d");
        System.out.println("本次演示密码 " + password);
    }

    private void create(String prefix,String password) {
        var a = users.register(prefix + "_a","林同学",password);
        var b = users.register(prefix + "_b","陈同学",password);
        var c = users.register(prefix + "_c","许同学",password);
        users.register(prefix + "_d","访客同学",password);
        String projectDue = clock.instant().plus(6,ChronoUnit.DAYS).toString();
        long p;
        try (var batch = files.stage(List.of(new DemoFile("项目要求.md",
                "# 课程小组项目\n\n- 完成业务分析、数据模型、实现与测试。\n- 每个任务由组长验收。\n".getBytes(StandardCharsets.UTF_8))))) {
            p = id(projects.create(a.id(),json.valueToTree(Map.of("name","校园创意节协作系统","course","企业 Java 程序设计",
                    "description","课程小组负责需求梳理、数据建模、协作页面和验收演示。按任务更新进度并整理提交材料。",
                    "deadline",projectDue)),batch,""));
        }
        join(a.id(),p,b); join(a.id(),p,c);
        long model = task(a.id(),p,"数据模型与约束","绘制 E-R 图、设计外键与并发认领校验。",2,2);
        tasks.setAssignees(a.id(),model,Set.of(b.id(),c.id()),"");
        long design = task(a.id(),p,"工作台交互设计","设计工作台、任务看板和任务详细页。",1,3);
        tasks.claim(c.id(),design,true,"");
        long done = task(a.id(),p,"需求访谈纪要","总结课堂项目的使用流程和范围。",1,2);
        tasks.claim(a.id(),done,true,"");
        long report = task(a.id(),p,"测试报告草稿","整理真实测试数据、失败回归及演示说明。",1,5);
        tasks.claim(b.id(),report,true,"");
        task(a.id(),p,"答辩演练","按完整业务主线演练，准备截图和课堂演示账号。",1,5);
        try (var batch = files.stage(List.of(new DemoFile("表结构说明.txt",
                "已核对项目、成员、任务和负责人之间的外键约束。".getBytes(StandardCharsets.UTF_8))))) {
            flow.comment(b.id(),model,"已完成主表设计，下一步补充并发场景。",batch,"");
        }
        try (var batch = files.stage(null)) {
            flow.comment(c.id(),model,"已补充 ER 关系与会话权限检查。",batch,"");
            flow.submit(c.id(),design,"已完成三张交互页面设计，申请验收。",batch,"");
            long polished = idOfSubmission(flow.submit(b.id(),report,"已提交测试报告初稿。",batch,""));
            flow.review(a.id(),report,1,polished,"REJECTED","请附上并发抢占最后一个名额的实际结果。","");
            long completed = idOfSubmission(flow.submit(a.id(),done,"需求纪要已整理。",batch,""));
            flow.review(a.id(),done,1,completed,"APPROVED",null,"");
        }
        try (var batch = files.stage(null)) {
            long other = id(projects.create(b.id(),json.valueToTree(Map.of("name","软件测试课程练习",
                    "course","软件质量保证","description","成员在另一个项目中担任组长。")),batch,""));
            join(b.id(),other,a);
            long mini = task(b.id(),other,"复核边界值用例","按输入区间核对边界值与等价类。",1,2);
            tasks.claim(a.id(),mini,true,"");
            long archive = id(projects.create(a.id(),json.valueToTree(Map.of("name","已归档的课程周记",
                    "course","软件工程导论","description","用于展示历史项目可读。")),batch,""));
            projects.archive(a.id(),archive,"");
        }
    }

    private long task(long actor,long project,String title,String description,int slots,int days) {
        try (var batch = files.stage(null)) {
            return id(tasks.create(actor,project,json.valueToTree(Map.of("title",title,"description",description,
                    "requiredCount",slots,"deadline",clock.instant().plus(days,ChronoUnit.DAYS).toString())),batch,""));
        }
    }

    private void join(long actor,long project,UserService.Profile member) {
        projects.respond(member.id(),id(projects.invite(actor,project,member.username())),true);
    }

    private static long id(Map<String,Object> entity) { return ((Number)entity.get("id")).longValue(); }
    private static long idOfSubmission(Map<String,Object> result) { return ((Number)result.get("submissionId")).longValue(); }

    private record DemoFile(String originalFilename,byte[] bytes) implements MultipartFile {
        @Override public String getName() { return "files"; }
        @Override public String getOriginalFilename() { return originalFilename; }
        @Override public String getContentType() { return "text/plain"; }
        @Override public boolean isEmpty() { return bytes.length == 0; }
        @Override public long getSize() { return bytes.length; }
        @Override public byte[] getBytes() { return bytes.clone(); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }
        @Override public void transferTo(File dest) throws IOException { Files.write(dest.toPath(),bytes); }
    }
}
