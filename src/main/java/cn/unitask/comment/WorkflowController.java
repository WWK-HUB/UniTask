package cn.unitask.comment;

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
@RequestMapping("/api/tasks/{t}")
public class WorkflowController {
    private final WorkflowService flow;
    private final AttachmentService files;

    public WorkflowController(WorkflowService flow, AttachmentService files) {
        this.flow = flow;
        this.files = files;
    }

    @GetMapping("/comments")
    public Object timeline(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable long t, HttpServletRequest request,
                           @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size) {
        return flow.timeline(actor.getId(),t,page,size,request.getContextPath());
    }

    @GetMapping("/comments/{c}/page")
    public Object jump(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable long t, @PathVariable long c,
                       @RequestParam(defaultValue="20") int size) {
        return flow.commentPage(actor.getId(),t,c,size);
    }

    @PostMapping(value="/comments", consumes=MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Object comment(@AuthenticationPrincipal AccountPrincipal actor,@PathVariable long t,HttpServletRequest request,
                          @RequestBody JsonNode form) {
        return commentWithFiles(actor,t,request,form,null);
    }

    @PostMapping(value="/comments", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Object commentWithFiles(@AuthenticationPrincipal AccountPrincipal actor,@PathVariable long t,HttpServletRequest request,
                          @RequestPart("data") JsonNode form,@RequestPart(value="files",required=false) List<MultipartFile> uploads) {
        try (var batch = files.stage(uploads)) {
            return flow.comment(actor.getId(),t,Inputs.text(form,"body",5000,false),batch,request.getContextPath());
        }
    }

    @PostMapping(value="/submit", consumes=MediaType.APPLICATION_JSON_VALUE)
    public Object submit(@AuthenticationPrincipal AccountPrincipal actor,@PathVariable long t,HttpServletRequest request,
                         @RequestBody(required=false) JsonNode form) {
        return submitWithFiles(actor,t,request,form,null);
    }

    @PostMapping(value="/submit", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public Object submitWithFiles(@AuthenticationPrincipal AccountPrincipal actor,@PathVariable long t,HttpServletRequest request,
                         @RequestPart("data") JsonNode form,@RequestPart(value="files",required=false) List<MultipartFile> uploads) {
        try (var batch = files.stage(uploads)) {
            return flow.submit(actor.getId(),t,Inputs.text(form,"body",5000,false),batch,request.getContextPath());
        }
    }

    @PostMapping("/review")
    public Object review(@AuthenticationPrincipal AccountPrincipal actor,@PathVariable long t,HttpServletRequest request,
                         @RequestBody JsonNode form) {
        long round = Inputs.id(form.get("reviewRound"),"reviewRound");
        if (round > Integer.MAX_VALUE) throw Problem.bad("reviewRound","审核轮次无效");
        return flow.review(actor.getId(),t,(int)round,Inputs.id(form.get("submissionId"),"submissionId"),
                Inputs.text(form,"decision",10,true),Inputs.text(form,"reason",5000,false),request.getContextPath());
    }

    @PostMapping("/remind")
    public Object remind(@AuthenticationPrincipal AccountPrincipal actor,@PathVariable long t,@RequestBody(required=false) JsonNode form) {
        return flow.remind(actor.getId(),t,Inputs.ids(form,"userIds",false,100));
    }

    @GetMapping("/logs")
    public Object logs(@AuthenticationPrincipal AccountPrincipal actor,@PathVariable long t,
                       @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        return flow.logs(actor.getId(),t,page,size);
    }
}
