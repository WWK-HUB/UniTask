package cn.unitask.notification;

import cn.unitask.common.Problem;
import cn.unitask.common.Sql;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class NotificationService {
    public record Event(Long actor, long project, Long task, Long comment, String type,
                        String title, String summary, String dedupe) {
        public Event(Long actor, long project, Long task, Long comment, String type, String title, String summary) {
            this(actor, project, task, comment, type, title, summary, null);
        }
    }

    private final Sql sql;
    private final StringRedisTemplate redis;
    private final Clock clock;
    private final Duration cacheTtl;
    private final Duration reminderInterval;
    private final Logger log = LoggerFactory.getLogger(getClass());
    private volatile long retryAfter;

    public NotificationService(Sql sql, StringRedisTemplate redis, Clock clock,
            @Value("${unitask.notification.unread-cache-ttl-seconds:60}") long cacheSeconds,
            @Value("${unitask.notification.reminder-interval-seconds:300}") long reminderSeconds) {
        this.sql = sql;
        this.redis = redis;
        this.clock = clock;
        this.cacheTtl = Duration.ofSeconds(cacheSeconds);
        this.reminderInterval = Duration.ofSeconds(reminderSeconds);
    }

    public boolean send(long recipient, Event event) {
        try {
            sql.update("""
                    INSERT INTO notifications(recipient_id,actor_id,project_id,task_id,comment_id,type,title,summary,dedupe_key)
                    VALUES(?,?,?,?,?,?,?,?,?)
                    """, recipient, event.actor(), event.project(), event.task(), event.comment(), event.type(),
                    event.title(), clip(event.summary(), 1000), event.dedupe());
        } catch (DuplicateKeyException ex) {
            if (event.dedupe() != null) return false;
            throw ex;
        }
        committed(() -> invalidate(recipient));
        return true;
    }

    public void sendOthers(Collection<Long> recipients, long actor, Event event) {
        for (long recipient : recipients) {
            if (recipient != actor) send(recipient, event);
        }
    }

    public static String clip(String input, int max) {
        int length = input.codePointCount(0, input.length());
        return length <= max ? input : input.substring(0, input.offsetByCodePoints(0, max - 1)) + "…";
    }

    public Sql.Page<Map<String, Object>> inbox(long recipient, Boolean unread, int page, int size, String context) {
        String readFilter = unread == null ? "" : unread ? " AND n.read_at IS NULL" : " AND n.read_at IS NOT NULL";
        return sql.page("n.*,u.nickname AS actor_name",
                "FROM notifications n LEFT JOIN users u ON n.actor_id=u.id WHERE n.recipient_id=?" + readFilter,
                "n.created_at DESC,n.id DESC", (r, i) -> {
                    String type = r.getString("type");
                    Long project = r.getObject("project_id", Long.class);
                    Long task = r.getObject("task_id", Long.class);
                    Long comment = r.getObject("comment_id", Long.class);
                    String link = switch (type) {
                        case "INVITE" -> "/invitations";
                        default -> task != null ? "/tasks/" + task + (comment == null ? "" : "#comment-" + comment)
                                : project == null ? "/notifications" : "/projects/" + project;
                    };
                    return Sql.fields("id", r.getLong("id"), "type", type, "actorName", r.getString("actor_name"),
                            "projectId", project, "taskId", task, "commentId", comment, "title", r.getString("title"),
                            "summary", r.getString("summary"), "createdAt", Sql.instant(r, "created_at"),
                            "readAt", Sql.instant(r, "read_at"), "link", context + link);
                }, page, size, recipient);
    }

    public long unread(long user) {
        if (redisReady()) {
            try {
                String value = redis.opsForValue().get(unreadKey(user));
                if (value != null) return Long.parseLong(value);
            } catch (RuntimeException ex) {
                unavailable(ex);
            }
        }
        long count = sql.count("SELECT COUNT(*) FROM notifications WHERE recipient_id=? AND read_at IS NULL", user);
        if (redisReady()) {
            try {
                redis.opsForValue().set(unreadKey(user), Long.toString(count), cacheTtl);
            } catch (RuntimeException ex) {
                unavailable(ex);
            }
        }
        return count;
    }

    @Transactional
    public void mark(long user, long id) {
        if (sql.count("SELECT COUNT(*) FROM notifications WHERE id=? AND recipient_id=?", id, user) == 0)
            throw Problem.missing();
        sql.update("UPDATE notifications SET read_at=UTC_TIMESTAMP(6) WHERE id=? AND recipient_id=? AND read_at IS NULL",
                id, user);
        committed(() -> invalidate(user));
    }

    @Transactional
    public void markAll(long user) {
        sql.update("UPDATE notifications SET read_at=UTC_TIMESTAMP(6) WHERE recipient_id=? AND read_at IS NULL", user);
        committed(() -> invalidate(user));
    }

    public boolean recentlyReminded(long task, long user) {
        Instant cutoff = clock.instant().minus(reminderInterval);
        return sql.count("""
                SELECT COUNT(*) FROM notifications WHERE task_id=? AND recipient_id=?
                AND type='REMINDER' AND created_at>?
                """, task, user, Sql.timestamp(cutoff)) > 0;
    }

    public List<Long> reminderHints(long task, List<Long> assignees) {
        if (assignees.isEmpty() || !redisReady()) return List.of();
        try {
            var markers = redis.opsForValue().multiGet(assignees.stream().map(user -> reminderKey(task,user)).toList());
            if (markers == null) return List.of();
            List<Long> result = new ArrayList<>();
            for (int i=0;i<markers.size();i++) if ("1".equals(markers.get(i))) result.add(assignees.get(i));
            return result;
        } catch (RuntimeException ex) {
            unavailable(ex);
            return List.of();
        }
    }

    public void rememberReminder(long task, long user) {
        committed(() -> {
            if (!redisReady()) return;
            try {
                redis.opsForValue().set(reminderKey(task,user), "1", reminderInterval);
            } catch (RuntimeException ex) {
                unavailable(ex);
            }
        });
    }

    private void invalidate(long user) {
        if (!redisReady()) return;
        try { redis.delete(unreadKey(user)); }
        catch (RuntimeException ex) { unavailable(ex); }
    }

    private boolean redisReady() { return System.currentTimeMillis() >= retryAfter; }
    private String reminderKey(long task,long user) { return "unitask:remind:" + task + ":" + user; }
    private String unreadKey(long user) { return "unitask:unread:" + user; }

    private void unavailable(RuntimeException ex) {
        if (redisReady()) log.warn("Redis unavailable, using MySQL ({})", ex.getClass().getSimpleName());
        retryAfter = System.currentTimeMillis() + 5_000;
    }

    private void committed(Runnable effect) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            effect.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { effect.run(); }
        });
    }
}
