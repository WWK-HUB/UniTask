package cn.unitask.web;

import cn.unitask.attachment.AttachmentService;
import cn.unitask.project.ProjectAccess;
import cn.unitask.security.AccountPrincipal;
import cn.unitask.task.TaskData;
import cn.unitask.user.UserService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
class PageController {
    private final UserService users;
    private final ProjectAccess access;
    private final TaskData tasks;
    private final AttachmentService files;

    PageController(UserService users, ProjectAccess access, TaskData tasks, AttachmentService files) {
        this.users = users;
        this.access = access;
        this.tasks = tasks;
        this.files = files;
    }

    @GetMapping("/")
    String home() { return "redirect:/dashboard"; }

    @GetMapping({"/dashboard","/projects","/my/tasks","/notifications","/invitations","/settings"})
    String personal(@AuthenticationPrincipal AccountPrincipal actor, jakarta.servlet.http.HttpServletRequest request, Model model) {
        return switch (request.getServletPath()) {
            case "/projects" -> page(actor,model,"projects","我的项目");
            case "/my/tasks" -> page(actor,model,"mine","我的任务");
            case "/notifications" -> page(actor,model,"notifications","消息中心");
            case "/invitations" -> page(actor,model,"invitations","项目邀请");
            case "/settings" -> page(actor,model,"settings","账号设置");
            default -> page(actor,model,"dashboard","工作台");
        };
    }

    @GetMapping({"/projects/{id}","/projects/{id}/{section:tasks|members|files}"})
    String project(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable long id,
                   @PathVariable(required=false) String section, Model model) {
        var project = access.read(id,actor.getId());
        model.addAttribute("project",project);
        model.addAttribute("entityId",id);
        return switch (section == null ? "" : section) {
            case "tasks" -> page(actor,model,"tasks","项目任务");
            case "members" -> page(actor,model,"members","项目成员");
            case "files" -> page(actor,model,"files","文件中心");
            default -> page(actor,model,"project","项目概览");
        };
    }

    @GetMapping("/tasks/{id}")
    String task(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable long id, Model model) {
        var scope = tasks.read(id,actor.getId());
        model.addAttribute("project",scope.project());
        model.addAttribute("entityId",id);
        return page(actor,model,"task","任务详情");
    }

    private String page(AccountPrincipal actor,Model model,String page,String heading) {
        model.addAttribute("actorName",users.me(actor.getId()).nickname());
        model.addAttribute("screen",page);
        model.addAttribute("heading",heading);
        var uploads = files.limits();
        model.addAttribute("uploadFiles",uploads.files());
        model.addAttribute("uploadFileBytes",uploads.fileBytes());
        model.addAttribute("uploadBatchBytes",uploads.batchBytes());
        return "app";
    }
}
