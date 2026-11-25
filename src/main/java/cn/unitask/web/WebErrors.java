package cn.unitask.web;

import cn.unitask.common.Problem;
import cn.unitask.common.Sql;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@ControllerAdvice
public class WebErrors {
    private final Logger log = LoggerFactory.getLogger(getClass());

    public static Map<String, Object> body(Problem problem) {
        return Sql.fields("code", problem.code(), "message", problem.getMessage(),
                "fieldErrors", problem.fields(), "requestId", UUID.randomUUID().toString());
    }

    public static void write(HttpServletResponse response, ObjectMapper mapper, Problem problem) throws IOException {
        response.setStatus(problem.status());
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        mapper.writeValue(response.getOutputStream(), body(problem));
    }

    public static boolean api(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length()).startsWith("/api/");
    }

    @ExceptionHandler(Problem.class)
    public Object problem(Problem problem, HttpServletRequest request, HttpServletResponse response) {
        if (api(request)) return ResponseEntity.status(problem.status()).body(body(problem));
        response.setStatus(problem.status());
        return new ModelAndView("error", Map.of("message", problem.getMessage(), "status", problem.status()));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, ServletRequestBindingException.class,
            MethodArgumentTypeMismatchException.class})
    public Object badInput(Exception exception, HttpServletRequest request, HttpServletResponse response) {
        return problem(Problem.bad("request", "请求参数或格式无效"), request, response);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public Object unsupported(HttpServletRequest request, HttpServletResponse response) {
        return problem(new Problem(415, "UNSUPPORTED_TYPE", "请求媒体类型不受支持"), request, response);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public Object uploadTooLarge(HttpServletRequest request, HttpServletResponse response) {
        return problem(new Problem(413, "UPLOAD_TOO_LARGE", "上传请求超过大小限制"), request, response);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public Object method(HttpServletRequest request, HttpServletResponse response) {
        return problem(new Problem(405,"METHOD_NOT_ALLOWED","该地址不支持此请求方法"),request,response);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public Object missing(HttpServletRequest request, HttpServletResponse response) {
        return problem(Problem.missing(), request, response);
    }

    @ExceptionHandler(Exception.class)
    public Object fallback(Exception exception, HttpServletRequest request, HttpServletResponse response) {
        log.error("Unhandled request failure [{}]", exception.getClass().getSimpleName());
        return problem(new Problem(500, "INTERNAL_ERROR", "暂时无法完成操作，请稍后重试"), request, response);
    }
}
