package cn.unitask.security;

import cn.unitask.common.Inputs;
import cn.unitask.common.Problem;
import cn.unitask.user.UserService;
import cn.unitask.web.WebErrors;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

@Configuration
class SecurityConfig {
    @Bean
    PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean
    UserDetailsService accountDetails(UserService users) {
        return username -> {
            var user = users.find(username);
            if (user == null) throw new UsernameNotFoundException("Invalid credentials");
            return new AccountPrincipal(user);
        };
    }

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    SecurityFilterChain applicationSecurity(HttpSecurity http, ObjectMapper json, RateLimits limits) throws Exception {
        http.authorizeHttpRequests(auth -> auth
                .dispatcherTypeMatchers(DispatcherType.FORWARD, DispatcherType.ERROR).permitAll()
                .requestMatchers(PathPatternRequestMatcher.withDefaults().matcher("/login"),
                        PathPatternRequestMatcher.withDefaults().matcher("/register"),
                        PathPatternRequestMatcher.withDefaults().matcher("/css/**"),
                        PathPatternRequestMatcher.withDefaults().matcher("/js/**"),
                        PathPatternRequestMatcher.withDefaults().matcher("/images/**"),
                        PathPatternRequestMatcher.withDefaults().matcher("/favicon.ico")).permitAll()
                .anyRequest().authenticated())
            .formLogin(login -> login.loginPage("/login").defaultSuccessUrl("/dashboard", true)
                .failureUrl("/login?error"))
            .logout(logout -> logout.logoutSuccessUrl("/login?logout").invalidateHttpSession(true)
                .deleteCookies("JSESSIONID"))
            .requestCache(cache -> cache.disable())
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, failure) -> {
                    if (WebErrors.api(request) || request.getServletPath().startsWith("/attachments")) {
                        WebErrors.write(response, json, new Problem(401, "AUTH_REQUIRED", "请先登录"));
                    } else {
                        response.sendRedirect(request.getContextPath() + "/login");
                    }
                })
                .accessDeniedHandler((request, response, failure) -> {
                    if (WebErrors.api(request)) {
                        Problem problem = AccountPrincipal.currentId() == null
                                ? new Problem(401, "AUTH_REQUIRED", "请先登录") : Problem.forbidden();
                        WebErrors.write(response, json, problem);
                    }
                    else response.sendError(403, "访问被拒绝");
                }))
            .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin())
                .contentSecurityPolicy(csp -> csp.policyDirectives(
                    "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
                    + "object-src 'none'; form-action 'self'; base-uri 'self'; frame-ancestors 'self'")))
            .addFilterBefore(new OncePerRequestFilter() {
                @Override
                protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                                FilterChain chain) throws ServletException, IOException {
                    if ("POST".equals(request.getMethod()) && "/login".equals(request.getServletPath())) {
                        try {
                            limits.check("login:" + request.getRemoteAddr(), 80, Duration.ofMinutes(5));
                            String username = Inputs.username(request.getParameter("username"));
                            limits.check("login:" + request.getRemoteAddr() + ":" + username, 12, Duration.ofMinutes(5));
                            Inputs.password(request.getParameter("password"));
                        } catch (Problem problem) {
                            String reason = problem.status() == 429 ? "limited" : "error";
                            response.sendRedirect(request.getContextPath() + "/login?" + reason);
                            return;
                        }
                    }
                    chain.doFilter(request, response);
                }
            }, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
