package cn.unitask.task;

import cn.unitask.allocation.AllocationService;
import cn.unitask.attachment.AttachmentService;
import cn.unitask.common.Inputs;
import cn.unitask.common.Problem;
import cn.unitask.security.AccountPrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class TaskController {
    private final TaskService tasks;
    private final AttachmentService files;
    private final AllocationService allocations;

    public TaskController(TaskService tasks, AttachmentService files, AllocationService allocations) {
        this.tasks = tasks;
        this.files = files;
        this.allocations = allocations;
    }

    @GetMapping("/projects/{p}/tasks")
    public Object list(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable long p,
                       @RequestParam(required=false) String status, @RequestParam(required=false) Long assigneeId,
                       @RequestParam(required=false) String priority, @RequestParam(required=false) String q,
                       @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size) {
        return tasks.listing(actor.getId(),p,status,assigneeId,priority,q,page,size);
    }

    @GetMapping("/my/tasks")
    public Object mine(@AuthenticationPrincipal AccountPrincipal actor, @RequestParam(required=false) Long projectId,
                       @RequestParam(required=false) String status, @RequestParam(required=false) Boolean overdue,
                       @RequestParam(defaultValue="0") int page,
                       @RequestParam(defaultValue="20") int size) {
        return tasks.mine(actor.getId(),projectId,status,overdue,page,size);
    }

    @PostMapping(value="/projects/{p}/tasks", consumes=MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Object create(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable long p,
                         @RequestBody JsonNode form, HttpServletRequest request) {
        return createWithFiles(actor,p,form,null,request);
    }

    @PostMapping(value="/projects/{p}/tasks", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Object createWithFiles(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable long p,
                                  @RequestPart("data") JsonNode form, @RequestPart(value="files",required=false) List<MultipartFile> uploads,
                                  HttpServletRequest request) {
        try (var batch = files.stage(uploads)) { return tasks.create(actor.getId(),p,form,batch,request.getContextPath()); }
    }

    @GetMapping("/tasks/{t}")
    public Object detail(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable long t, HttpServletRequest request) {
        return tasks.detail(actor.getId(),t,request.getContextPath());
    }

    @PatchMapping("/tasks/{t}")
    public Object edit(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable long t,
                       @RequestBody JsonNode form, HttpServletRequest request) {
        return tasks.edit(actor.getId(),t,form,request.getContextPath());
    }

    @DeleteMapping("/tasks/{t}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable long t) { tasks.delete(actor.getId(),t); }

    @PostMapping("/tasks/{t}/claim")
    public Object claim(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable long t, HttpServletRequest request) {
        return tasks.claim(actor.getId(),t,true,request.getContextPath());
    }

    @DeleteMapping("/tasks/{t}/claim")
    public Object cancel(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable long t, HttpServletRequest request) {
        return tasks.claim(actor.getId(),t,false,request.getContextPath());
    }

    @PutMapping("/tasks/{t}/assignees")
    public Object assignees(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable long t,
                            @RequestBody JsonNode form, HttpServletRequest request) {
        return tasks.setAssignees(actor.getId(),t,Inputs.ids(form,"userIds",true,100),request.getContextPath());
    }

    @PostMapping("/projects/{p}/allocations/preview")
    public Object preview(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable long p,@RequestBody JsonNode form) {
        JsonNode include = form.get("includeLeader");
        if (include != null && !include.isBoolean()) throw Problem.bad("includeLeader","请选择是否包含组长");
        return allocations.preview(actor.getId(),p,include!=null && include.booleanValue(),Inputs.ids(form,"taskIds",false,1000));
    }

    @PostMapping("/projects/{p}/allocations/confirm")
    public Object confirm(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable long p,@RequestBody JsonNode form) {
        return allocations.confirm(actor.getId(),p,Inputs.text(form,"previewToken",100,true));
    }
}
