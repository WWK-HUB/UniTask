package cn.unitask.user;

import cn.unitask.common.Inputs;
import cn.unitask.common.Problem;
import cn.unitask.security.AccountPrincipal;
import cn.unitask.security.RateLimits;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;

@Controller
public class UserController {
    private final UserService users;
    private final RateLimits limits;
    private final RegistrationPolicy registration;

    public UserController(UserService users, RateLimits limits, RegistrationPolicy registration) {
        this.users = users;
        this.limits = limits;
        this.registration = registration;
    }

    @GetMapping("/login")
    public String login() { return "auth/login"; }

    @GetMapping("/register")
    public String registerPage(Model model) {
        model.addAttribute("registrationCodeRequired", registration.requiresCode());
        return "auth/register";
    }

    @PostMapping("/register")
    public ModelAndView register(@RequestParam String username, @RequestParam String nickname,
                                 @RequestParam String password, @RequestParam(required = false) String registrationCode,
                                 HttpServletRequest request, HttpServletResponse response) {
        try {
            limits.check("register:" + request.getRemoteAddr(), 12, Duration.ofMinutes(10));
            registration.requireValidCode(registrationCode);
            users.register(username, nickname, password);
            return new ModelAndView("redirect:/login?registered");
        } catch (Problem problem) {
            response.setStatus(problem.status());
            return new ModelAndView("auth/register")
                    .addObject("registrationCodeRequired", registration.requiresCode())
                    .addObject("error", problem.getMessage())
                    .addObject("username", username)
                    .addObject("nickname", nickname);
        }
    }

    @ResponseBody
    @GetMapping("/api/me")
    public UserService.Profile profile(@AuthenticationPrincipal AccountPrincipal actor) { return users.me(actor.getId()); }

    @ResponseBody
    @PatchMapping("/api/me")
    public UserService.Profile rename(@AuthenticationPrincipal AccountPrincipal actor, @RequestBody JsonNode data) {
        return users.rename(actor.getId(), Inputs.text(data, "nickname", 40, true));
    }

    @PostMapping("/api/me/password")
    public ResponseEntity<Void> password(@AuthenticationPrincipal AccountPrincipal actor, @RequestBody JsonNode data,
                                         HttpServletRequest request, HttpServletResponse response) {
        users.changePassword(actor.getId(), passwordField(data, "currentPassword"), passwordField(data, "newPassword"));
        new SecurityContextLogoutHandler().logout(request, response, SecurityContextHolder.getContext().getAuthentication());
        return ResponseEntity.noContent().build();
    }

    private String passwordField(JsonNode data, String field) {
        JsonNode node = data.get(field);
        if (node == null || !node.isTextual()) throw Problem.bad(field, "请输入密码");
        return node.textValue();
    }

    @ResponseBody
    @GetMapping("/api/users/lookup")
    public UserService.Profile lookup(@AuthenticationPrincipal AccountPrincipal actor, @RequestParam String username) {
        limits.check("lookup:" + actor.getId(), 30, Duration.ofMinutes(1));
        return users.lookup(username);
    }
}
