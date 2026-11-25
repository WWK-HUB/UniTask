package cn.unitask;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeDriverService;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import static org.assertj.core.api.Assertions.assertThat;

class WarBrowserE2E {
    private final Path evidence = Path.of("target","browser-evidence");
    private ChromeDriver browser;
    private WebDriverWait wait;
    private Process app;
    private Path uploads;
    private int port;
    private String base;
    private String project;
    private String task;
    private final String password = "Browser-Fixture-Only1";
    private final String prefix = "ui_" + UUID.randomUUID().toString().substring(0,8);
    private final String a = prefix + "a";
    private final String b = prefix + "b";
    private final String c = prefix + "c";
    private final String d = prefix + "d";
    private final String projectName = "校园协作实践 " + prefix.substring(3);
    private final String taskName = "设计并验证数据模型";
    private final String progress = "已提交首轮数据库结构与样例文件";
    private final String rejected = "请补充约束与并发验证结论";
    private final String revised = "已补全约束说明、并发测试与复现步骤";

    @Test
    void t16_t18_t26_t29_t31_t32_browserWorkflowAndRestart() throws Exception {
        Files.createDirectories(evidence);
        uploads = Files.createTempDirectory(Path.of("target"),"browser-upload-").toAbsolutePath();
        try (var socket = new ServerSocket(0,0,InetAddress.getByName("127.0.0.1"))) { port = socket.getLocalPort(); }
        base = "http://127.0.0.1:" + port + "/unitask";
        try {
            startWar("first");
            ChromeOptions options = new ChromeOptions();
            Path chrome = Path.of("C:/Program Files/Google/Chrome/Application/chrome.exe");
            if (Files.isRegularFile(chrome)) options.setBinary(chrome.toString());
            options.addArguments("--headless=new","--window-size=1280,960","--disable-background-networking");
            Path localDriver = Path.of("var/toolchain/chromedriver-win64/chromedriver.exe");
            if (Files.isRegularFile(localDriver)) {
                var service = new ChromeDriverService.Builder().usingDriverExecutable(localDriver.toFile()).build();
                browser = new ChromeDriver(service,options);
            } else {
                browser = new ChromeDriver(options);
            }
            wait = new WebDriverWait(browser,Duration.ofSeconds(18));
            browser.manage().timeouts().scriptTimeout(Duration.ofSeconds(15));

            register(a,"组长甲"); register(b,"队员乙"); register(c,"队员丙"); register(d,"外部丁");
            login(a);
            click("＋ 创建项目");
            fill("dialog [name=name]",projectName);
            fill("dialog [name=course]","企业 Java 程序设计");
            fill("dialog [name=description]","小组协作完成数据建模、附件整理与验收。");
            Path pdf = Files.write(evidence.resolve("input-reference.pdf"),Fixtures.pdf()).toAbsolutePath();
            visible("dialog input[type=file]").sendKeys(pdf.toString());
            click("创建项目");
            see(projectName);
            project = current("/projects/[1-9][0-9]*");
            assertThat(browser.getCurrentUrl()).startsWith(base + "/projects/");
            go(project + "/members");
            invite(b,"队员乙");
            invite(c,"队员丙");

            login(b);
            assertThat(fetch("GET","/api" + project,"",null).get("status")).isEqualTo(404L);
            go("/invitations");
            click("接受邀请");
            see("已加入项目");
            login(c);
            go("/invitations");
            click("接受邀请");
            see("已加入项目");

            login(a);
            go(project + "/tasks");
            click("＋ 发布任务");
            fill("dialog [name=title]",taskName);
            fill("dialog [name=description]","完成实体关系图、表约束和关键事务的验证。");
            fill("dialog [name=requiredCount]","2");
            click("发布任务");
            see("任务已发布");
            clickLink(taskName);
            see(taskName);
            task = current("/tasks/[1-9][0-9]*");

            login(b);
            go(task);
            click("认领任务");
            see("已认领任务");
            assertThat(visible(".task-meta").getText()).contains("1 / 2","队员乙");

            login(a);
            go(project + "/tasks");
            click("随机分配");
            click("生成预览");
            see("随机分配预览");
            assertThat(visible("dialog table").getText()).contains("队员乙","队员丙");
            click("确认分配");
            see("分配已确认");
            screenshot("02-task-board.png");

            login(b);
            go(project + "/tasks");
            choose("status","IN_PROGRESS");
            click("筛选");
            wait.until(driver -> driver.findElements(By.cssSelector(".lane")).size()==1);
            see(taskName);
            go("/my/tasks");
            choose("status","IN_PROGRESS");
            click("筛选");
            see(taskName);
            go(task);
            assertThat(visible(".task-meta").getText()).contains("2 / 2","队员乙","队员丙");
            Path text = Files.writeString(evidence.resolve("结构说明.txt"),"实体、外键及行锁",StandardCharsets.UTF_8).toAbsolutePath();
            Path png = Files.write(evidence.resolve("overview.png"),Fixtures.png()).toAbsolutePath();
            Path video = Files.write(evidence.resolve("evidence.mp4"),Fixtures.video()).toAbsolutePath();
            visible(".composer input[type=file]").sendKeys(text + "\n" + png + "\n" + video);
            fill(".composer [name=body]",progress);
            click("发表评论");
            see(progress);
            see("overview.png");
            String stored = fileUrl("结构说明.txt","download");
            assertThat(fetch("GET",stored,"",null).get("length")).isEqualTo(Files.size(text));
            String clip = fileUrl("evidence.mp4","preview");
            var partial = fetch("GET",clip,"bytes=0-15",null);
            assertThat(partial).containsEntry("status",206L).containsEntry("length",16L);
            assertThat(partial.get("range")).isEqualTo("bytes 0-15/" + Files.size(video));
            assertThat(fetch("GET",clip,"bytes=9999999-",null).get("status")).isEqualTo(416L);

            String scriptText = "<img src=x onerror=window.unitaskInjected=1>";
            fill(".composer [name=body]",scriptText);
            click("发表评论");
            see(scriptText);
            assertThat(browser.findElements(By.cssSelector("#timeline img[src='x']"))).isEmpty();
            assertThat(((JavascriptExecutor)browser).executeScript("return window.unitaskInjected ?? null")).isNull();
            click("提交验收");
            see("成果已提交验收");

            login(a);
            go("/notifications");
            see("任务进展更新");
            assertThat(browser.findElements(By.xpath("//a[normalize-space(.)='任务进展更新']"))).hasSize(2);
            clickLink("任务进展更新");
            assertThat(browser.getCurrentUrl()).contains(task + "#comment-");
            see(scriptText);
            click("打回");
            fill("dialog [name=reason]",rejected);
            click("确认打回");
            see(rejected);
            click("催办");
            click("发送提醒");
            see("已提醒 2 人");

            login(b);
            go("/notifications");
            see("组长发来催办提醒");
            clickLink("组长发来催办提醒");
            see(rejected);
            fill(".composer [name=body]",revised);
            click("提交验收");
            see(revised);
            screenshot("03-task-collaboration.png");

            login(a);
            go(task);
            click("通过验收");
            click("确认通过");
            see("任务已验收");
            go(project + "/files");
            see("结构说明.txt");
            see("input-reference.pdf");
            WebElement imageRow = wait.until(ExpectedConditions.visibilityOfElementLocated(
                    By.xpath("//li[span/strong[normalize-space(.)='overview.png']]")));
            imageRow.findElement(By.xpath(".//button[normalize-space(.)='预览']")).click();
            wait.until(driver -> Boolean.TRUE.equals(((JavascriptExecutor)driver).executeScript(
                    "return !!document.querySelector('dialog img')?.naturalWidth")));
            click("关闭");
            assertThat(fetch("GET",fileUrl("input-reference.pdf","preview"),"",null).get("status")).isEqualTo(200L);
            screenshot("04-files.png");
            choose("type","text");
            click("筛选");
            wait.until(driver -> !driver.findElement(By.id("content")).getText().contains("input-reference.pdf"));
            see("结构说明.txt");
            go(project);
            see("100%");
            click("归档项目");
            click("确认归档");
            see("项目已归档");
            assertThat(fetch("POST","/api" + task + "/comments","",Map.of("body","归档后写入")).get("status")).isEqualTo(409L);

            login(d);
            assertThat(fetch("GET",stored,"",null).get("status")).isEqualTo(404L);
            assertThat(fetch("GET",clip,"bytes=0-15",null).get("status")).isEqualTo(404L);
            assertThat(fetch("GET","/api" + task,"",null).get("status")).isEqualTo(404L);
            go(project);
            see("没有找到该资源");

            login(a);
            var injection = fetch("GET","/api/projects?q=%27%20OR%201%3D1--","",null);
            assertThat(injection.get("preview")).asString().contains("\"total\":0");
            measure();
            go("/dashboard");
            see("你好，组长甲");
            screenshot("01-dashboard.png");
            browser.manage().window().setSize(new Dimension(412,870));
            click("菜单");
            assertThat(visible(".nav-toggle").getAttribute("aria-expanded")).isEqualTo("true");
            visible(".nav-toggle").sendKeys(org.openqa.selenium.Keys.ESCAPE);
            wait.until(ExpectedConditions.attributeToBe(By.cssSelector(".nav-toggle"),"aria-expanded","false"));
            screenshot("05-narrow-dashboard.png");

            stopWar();
            startWar("restart");
            login(b);
            go(task);
            see(progress);
            see(revised);
            see("已完成");
            assertThat(fetch("GET",stored,"",null).get("length")).isEqualTo(Files.size(text));
            go("/notifications");
            see("组长发来催办提醒");
        } catch (Exception | AssertionError failure) {
            if (browser != null) screenshot("failure.png");
            throw failure;
        } finally {
            try { if (browser != null) browser.quit(); }
            finally { stopWar(); }
        }
    }

    private void startWar(String attempt) throws Exception {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        var command = new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin",executable).toString(),
                "-jar","target/unitask.war","--server.port=" + port,"--server.servlet.context-path=/unitask");
        command.environment().put("UNITASK_DB_URL",IntegrationSupport.testUrl());
        command.environment().put("UNITASK_DB_USER",System.getenv().getOrDefault("UNITASK_TEST_DB_USER","unitask_test"));
        command.environment().put("UNITASK_DB_PASSWORD",IntegrationSupport.testPassword());
        command.environment().put("UNITASK_UPLOAD_DIR",uploads.toString());
        Path log = evidence.resolve("war-" + attempt + ".log");
        app = command.redirectErrorStream(true).redirectOutput(log.toFile()).start();
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        var request = HttpRequest.newBuilder(URI.create(base + "/login")).timeout(Duration.ofSeconds(4)).build();
        long end = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        while (app.isAlive() && System.nanoTime() < end) {
            try {
                if (client.send(request,HttpResponse.BodyHandlers.discarding()).statusCode() == 200) return;
            } catch (java.io.IOException unavailable) { /* startup in progress */ }
            Thread.sleep(450);
        }
        throw new AssertionError("WAR failed to start, see " + log.toAbsolutePath());
    }

    private void stopWar() throws InterruptedException {
        if (app == null || !app.isAlive()) return;
        app.destroy();
        if (!app.waitFor(8,TimeUnit.SECONDS)) {
            app.destroyForcibly();
            app.waitFor(8,TimeUnit.SECONDS);
        }
    }

    private void register(String user,String nickname) {
        go("/register");
        fill("[name=username]",user);
        fill("[name=nickname]",nickname);
        fill("[name=password]",password);
        click("创建账号");
        see("账号已注册");
    }

    private void login(String user) {
        go("/login");
        browser.manage().deleteAllCookies();
        go("/login");
        fill("[name=username]",user);
        fill("[name=password]",password);
        click("登录");
        see("查看课程项目进展");
    }

    private void invite(String account,String nickname) {
        click("＋ 邀请成员");
        fill("dialog [name=username]",account);
        click("查找账号");
        see(nickname + " · @" + account);
        click("发出邀请");
        see("邀请已发出");
    }

    private void go(String route) { browser.get(base + route); }
    private WebElement visible(String selector) {
        return wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector(selector)));
    }
    private void fill(String selector,String value) {
        var element=visible(selector);
        element.clear();
        element.sendKeys(value);
    }
    private void choose(String field,String value) {
        new org.openqa.selenium.support.ui.Select(visible("select[name=" + field + "]")).selectByValue(value);
    }
    private void click(String text) {
        wait.until(ExpectedConditions.elementToBeClickable(By.xpath("//button[normalize-space(.)=" + literal(text) + "]"))).click();
    }
    private void clickLink(String text) {
        wait.until(ExpectedConditions.elementToBeClickable(By.xpath("//a[normalize-space(.)=" + literal(text) + "]"))).click();
    }
    private String literal(String text) { return "'" + text + "'"; }
    private void see(String text) {
        wait.withMessage("Expected visible text: " + text).until(driver -> driver.findElement(By.tagName("body")).getText().contains(text));
    }
    private String current(String pattern) {
        String route = browser.getCurrentUrl().substring(base.length());
        assertThat(route).matches(pattern);
        return route;
    }
    private String fileUrl(String name,String action) {
        String selector = "//li[span/strong[normalize-space(.)=" + literal(name) + "]]//a[contains(@href,'/download')]";
        String url = wait.until(ExpectedConditions.visibilityOfElementLocated(By.xpath(selector))).getAttribute("href");
        return url.substring(base.length(),url.length()-"/download".length()) + "/" + action;
    }
    private void screenshot(String filename) throws Exception {
        Files.write(evidence.resolve(filename),((TakesScreenshot)browser).getScreenshotAs(OutputType.BYTES));
    }

    @SuppressWarnings("unchecked")
    private Map<String,Object> fetch(String method,String route,String range,Map<String,Object> body) {
        return (Map<String,Object>) ((JavascriptExecutor)browser).executeAsyncScript("""
                const [method,url,range,body,done] = arguments;
                const options = {method,headers:{}};
                if (range) options.headers.Range = range;
                if (body) {
                  options.headers['Content-Type']='application/json';
                  const header=document.querySelector('meta[name=unitask-csrf-header]').content;
                  options.headers[header]=document.querySelector('meta[name=unitask-csrf]').content;
                  options.body=JSON.stringify(body);
                }
                fetch(url,options).then(async response => {
                  const bytes = await response.arrayBuffer();
                  done({status:response.status,length:bytes.byteLength,range:response.headers.get('content-range'),
                    preview:new TextDecoder().decode(bytes.slice(0,1000))});
                }).catch(error => done({error:String(error)}));
                """,method,base + route,range,body);
    }

    @SuppressWarnings("unchecked")
    private void measure() throws Exception {
        List<Number> samples = (List<Number>)((JavascriptExecutor)browser).executeAsyncScript("""
                const [url,done]=arguments;
                (async () => {
                  const result=[];
                  for (let i=0;i<23;i++) {
                    const start=performance.now();
                    const response=await fetch(url,{cache:'no-store'});
                    if (!response.ok) return done(null);
                    await response.json();
                    if (i>=3) result.push(performance.now()-start);
                  }
                  done(result);
                })().catch(() => done(null));
                """,base + "/api" + project);
        assertThat(samples).hasSize(20);
        var sorted = samples.stream().mapToDouble(Number::doubleValue).sorted().toArray();
        Files.writeString(evidence.resolve("performance.txt"),String.format(java.util.Locale.ROOT,
                "20 sequential warm local HTTP requests, GET %s; p50 %.1f ms, p95 %.1f ms, max %.1f ms%n",
                "/api" + project,(sorted[9]+sorted[10])/2,sorted[18],sorted[19]));
    }
}
