package cn.unitask.notification;

import cn.unitask.security.AccountPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {
    private final NotificationService notices;

    public NotificationController(NotificationService notices) { this.notices = notices; }

    @GetMapping
    public Object list(@AuthenticationPrincipal AccountPrincipal actor, HttpServletRequest request,
                       @RequestParam(required=false) Boolean unread, @RequestParam(defaultValue="0") int page,
                       @RequestParam(defaultValue="20") int size) {
        return notices.inbox(actor.getId(),unread,page,size,request.getContextPath());
    }

    @GetMapping("/unread-count")
    public Map<String,Long> unread(@AuthenticationPrincipal AccountPrincipal actor) {
        return Map.of("count",notices.unread(actor.getId()));
    }

    @PatchMapping("/{n}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void mark(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable long n) { notices.mark(actor.getId(),n); }

    @PostMapping("/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markAll(@AuthenticationPrincipal AccountPrincipal actor) { notices.markAll(actor.getId()); }
}
