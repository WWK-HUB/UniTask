package cn.unitask.common;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

public final class Inputs {
    private Inputs() {}

    public static String text(JsonNode data, String key, int max, boolean required) {
        JsonNode value = data == null ? null : data.get(key);
        if (value != null && !value.isNull() && !value.isTextual()) {
            throw Problem.bad(key, "请输入文本");
        }
        return text(value == null || value.isNull() ? null : value.textValue(), key, max, required);
    }

    public static String text(String value, String key, int max, boolean required) {
        String trimmed = value == null ? "" : value.strip();
        if ((required && trimmed.isEmpty()) || trimmed.codePointCount(0, trimmed.length()) > max) {
            throw Problem.bad(key, required ? "请输入 1–" + max + " 个字符" : "最多 " + max + " 个字符");
        }
        return trimmed.isEmpty() ? null : trimmed;
    }

    public static String username(String input) {
        if (input == null || !input.strip().matches("[A-Za-z0-9_]{4,32}")) {
            throw Problem.bad("username", "账号应为 4–32 位字母、数字或下划线");
        }
        return normalizeUsername(input);
    }

    public static String normalizeUsername(String input) {
        return input == null ? "" : input.strip().toLowerCase(Locale.ROOT);
    }

    public static String password(String input) {
        if (input == null || input.codePointCount(0, input.length()) < 8
                || input.codePointCount(0, input.length()) > 64
                || input.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72
                || input.indexOf(0) >= 0) {
            throw Problem.bad("password", "密码须为 8–64 个字符，且 UTF-8 不超过 72 字节");
        }
        return input;
    }

    public static long id(JsonNode node, String key) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong() || node.longValue() <= 0) {
            throw Problem.bad(key, "请输入有效 ID");
        }
        return node.longValue();
    }

    public static Set<Long> ids(JsonNode data, String key, boolean required, int limit) {
        JsonNode node = data == null ? null : data.get(key);
        if (node == null && !required) return null;
        if (node == null || !node.isArray() || node.size() > limit) throw Problem.bad(key, "请选择有效记录");
        Set<Long> ids = new LinkedHashSet<>();
        for (JsonNode item : node) {
            if (!ids.add(id(item, key))) throw Problem.bad(key, "选择项重复");
        }
        return ids;
    }

    public static int requiredCount(JsonNode data) {
        JsonNode value = data == null ? null : data.get("requiredCount");
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()
                || value.intValue() < 1 || value.intValue() > 100) {
            throw Problem.bad("requiredCount", "所需人数必须在 1–100 之间");
        }
        return value.intValue();
    }

    public static String choice(String value, String key, Set<String> values) {
        if (value == null || !values.contains(value)) throw Problem.bad(key, "选项无效");
        return value;
    }

    public static Instant deadline(JsonNode data, Instant existing, Clock clock) {
        if (data == null || !data.has("deadline")) return existing;
        JsonNode value = data.get("deadline");
        if (value.isNull()) return null;
        Instant parsed;
        try {
            if (!value.isTextual()) throw new IllegalArgumentException();
            parsed = Instant.parse(value.textValue()).truncatedTo(ChronoUnit.MICROS);
            if (parsed.isAfter(Instant.parse("9999-12-31T23:59:59Z"))) throw new IllegalArgumentException();
        } catch (RuntimeException ex) {
            throw Problem.bad("deadline", "请提供有效的截止时间");
        }
        if (!parsed.equals(existing) && !parsed.isAfter(clock.instant())) {
            throw Problem.bad("deadline", "截止时间须晚于当前时间");
        }
        return parsed;
    }

    public static String likePattern(String value) {
        String query = text(value, "q", 100, false);
        return query == null ? null : "%" + query.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }
}
