package cn.unitask.security;

import cn.unitask.common.Problem;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class RateLimits {
    private record Counter(Instant expires, int used) {}
    private final Map<String, Counter> counters = new LinkedHashMap<>();
    private final Clock clock;

    public RateLimits(Clock clock) { this.clock = clock; }

    public synchronized void check(String bucket, int maximum, Duration interval) {
        Instant now = clock.instant();
        Counter existing = counters.get(bucket);
        if (existing != null && existing.expires().isAfter(now)) {
            if (existing.used() >= maximum) throw Problem.limited("操作过于频繁，请稍后重试");
            counters.put(bucket, new Counter(existing.expires(), existing.used() + 1));
            return;
        }
        if (counters.size() >= 10_000) {
            counters.values().removeIf(value -> !value.expires().isAfter(now));
            if (counters.size() >= 10_000) throw Problem.limited("操作过于频繁，请稍后重试");
        }
        counters.put(bucket, new Counter(now.plus(interval), 1));
    }
}
