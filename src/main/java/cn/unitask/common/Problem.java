package cn.unitask.common;

import java.util.Map;

public final class Problem extends RuntimeException {
    private final int status;
    private final String code;
    private final Map<String, String> fields;

    public Problem(int status, String code, String message) {
        this(status, code, message, Map.of());
    }

    private Problem(int status, String code, String message, Map<String, String> fields) {
        super(message);
        this.status = status;
        this.code = code;
        this.fields = fields;
    }

    public int status() { return status; }
    public String code() { return code; }
    public Map<String, String> fields() { return fields; }

    public static Problem bad(String field, String message) {
        return new Problem(400, "INVALID_INPUT", message, Map.of(field, message));
    }

    public static Problem missing() {
        return new Problem(404, "NOT_FOUND", "没有找到该资源，或你已失去访问权限");
    }

    public static Problem forbidden() {
        return new Problem(403, "FORBIDDEN", "你没有执行此操作的权限");
    }

    public static Problem conflict(String code, String message) {
        return new Problem(409, code, message);
    }

    public static Problem limited(String message) {
        return new Problem(429, "RATE_LIMITED", message);
    }
}
