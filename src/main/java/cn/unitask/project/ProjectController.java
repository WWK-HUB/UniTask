package cn.unitask.project;

import cn.unitask.attachment.AttachmentService;
import cn.unitask.common.Inputs;
import cn.unitask.security.AccountPrincipal;
import cn.unitask.security.RateLimits;
import java.time.Duration;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class ProjectController {
    private final ProjectService projects;
    private final AttachmentService files;
    private final RateLimits limits;

    public ProjectController(ProjectService projects, AttachmentService files, RateLimits limits) {
        this.projects = projects;
        this.files = files;
        this.limits = limits;
    }

    @GetMapping("/projects")
    public Object list(@AuthenticationPrincipal AccountPrincipal actor, @RequestParam(required=false) String status,
                       @RequestParam(required=false) String q, @RequestParam(defaultValue="0") int page,
                       @RequestParam(defaultValue="20") int size) {
        return projects.list(actor.getId(),status,q,page,size);
    }

    @PostMapping(value="/projects", consumes=MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Object create(@AuthenticationPrincipal AccountPrincipal actor, @RequestBody JsonNode data, HttpServletRequest request) {
        return createWithFiles(actor, data, null, request);
    }

    @PostMapping(value="/projects", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Object createWithFiles(@AuthenticationPrincipal AccountPrincipal actor, @RequestPart("data") JsonNode data,
                                  @RequestPart(value="files", required=false) List<MultipartFile> uploads,
                                  HttpServletRequest request) {
        try (var batch = files.stage(uploads)) {
            return projects.create(actor.getId(),data,batch,request.getContextPath());
        }
    }

    @GetMapping("/projects/{p}")
    public Object detail(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable long p, HttpServletRequest request) {
        return projects.detail(actor.getId(),p,request.getContextPath());
    }

    @PatchMapping("/projects/{p}")
    public Object edit(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable long p,
                       @RequestBody JsonNode data, HttpServletRequest request) {
        return projects.edit(actor.getId(),p,data,request.getContextPath());
    }

    @PostMapping("/projects/{p}/archive")
    public Object archive(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable long p, HttpServletRequest request) {
        return projects.archive(actor.getId(),p,request.getContextPath());
    }

    @GetMapping("/projects/{p}/members")
    public Object members(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable long p,
                          @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size) {
        return projects.members(actor.getId(),p,page,size);
    }

    @DeleteMapping("/projects/{p}/members/{u}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable long p, @PathVariable long u) {
        projects.remove(actor.getId(),p,u);
    }

    @PostMapping("/projects/{p}/transfer")
    public Object transfer(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable long p,
                           @RequestBody JsonNode data, HttpServletRequest request) {
        return projects.transfer(actor.getId(),p,Inputs.id(data.get("userId"),"userId"),request.getContextPath());
    }

    @PostMapping("/projects/{p}/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    public Object invite(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable long p, @RequestBody JsonNode data) {
        limits.check("invite:" + actor.getId(),30,Duration.ofMinutes(1));
        return projects.invite(actor.getId(),p,Inputs.text(data,"username",32,true));
    }

    @GetMapping("/invitations")
    public Object received(@AuthenticationPrincipal AccountPrincipal actor, @RequestParam(required=false) String status,
                           @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size) {
        return projects.invitations(actor.getId(),null,status,page,size);
    }

    @GetMapping("/projects/{p}/invitations")
    public Object sent(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable long p,
                       @RequestParam(required=false) String status, @RequestParam(defaultValue="0") int page,
                       @RequestParam(defaultValue="20") int size) {
        return projects.invitations(actor.getId(),p,status,page,size);
    }

    @PostMapping("/invitations/{i}/accept")
    public Object accept(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable long i) {
        return projects.respond(actor.getId(),i,true);
    }

    @PostMapping("/invitations/{i}/decline")
    public Object decline(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable long i) {
        return projects.respond(actor.getId(),i,false);
    }

    @PostMapping("/projects/{p}/invitations/{i}/revoke")
    public Object revoke(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable long p, @PathVariable long i) {
        return projects.revoke(actor.getId(),p,i);
    }

    @GetMapping("/projects/{p}/attachments")
    public Object attachments(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable long p, HttpServletRequest request,
                              @RequestParam(required=false) Long taskId, @RequestParam(required=false) Long uploaderId,
                              @RequestParam(required=false) String type, @RequestParam(defaultValue="0") int page,
                              @RequestParam(defaultValue="20") int size) {
        return files.projectFiles(actor.getId(),p,taskId,uploaderId,type,page,size,request.getContextPath());
    }

    @GetMapping("/projects/{p}/attachments/uploaders")
    public Object uploaders(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable long p) {
        return files.uploaders(actor.getId(),p);
    }

    @GetMapping("/projects/{p}/stats")
    public Map<String,Object> stats(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable long p) {
        return projects.stats(actor.getId(),p);
    }
}
