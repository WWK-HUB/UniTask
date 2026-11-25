package cn.unitask.notification;

import cn.unitask.common.Sql;
import cn.unitask.notification.NotificationService.Event;
import cn.unitask.project.ProjectAccess;
import cn.unitask.task.Task;
import cn.unitask.task.TaskData;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class DeadlineReminders {
    private record Target(long project, Long task) {}
    private final Sql sql;
    private final ProjectAccess access;
    private final TaskData tasks;
    private final NotificationService notices;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final Logger log = LoggerFactory.getLogger(getClass());

    public DeadlineReminders(Sql sql, ProjectAccess access, TaskData tasks, NotificationService notices,
                             PlatformTransactionManager manager, Clock clock) {
        this.sql = sql;
        this.access = access;
        this.tasks = tasks;
        this.notices = notices;
        this.clock = clock;
        this.transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Scheduled(fixedDelay=300_000, initialDelay=60_000)
    public void tick() { runAt(clock.instant()); }

    public void runAt(Instant now) {
        var end = now.plus(Duration.ofHours(24));
        var targets = sql.list("""
                SELECT id AS project_id,NULL AS task_id FROM projects WHERE status='ACTIVE' AND deadline>? AND deadline<=?
                UNION ALL
                SELECT t.project_id,t.id FROM tasks t JOIN projects p ON p.id=t.project_id
                WHERE p.status='ACTIVE' AND t.deleted_at IS NULL AND t.status<>'COMPLETED' AND t.deadline>? AND t.deadline<=?
                """,(r,i) -> new Target(r.getLong("project_id"),r.getObject("task_id",Long.class)),
                Sql.timestamp(now),Sql.timestamp(end),Sql.timestamp(now),Sql.timestamp(end));
        for (var target : targets) {
            try {
                transaction.executeWithoutResult(status -> check(target,now,end));
            } catch (RuntimeException failure) {
                log.warn("Could not check deadline for project {} ({})",target.project(),failure.getClass().getSimpleName());
            }
        }
    }

    private void check(Target target, Instant now, Instant end) {
        var project = access.lock(target.project());
        if (!project.active()) return;
        if (target.task() == null) {
            if (!due(project.getDeadline(),now,end)) return;
            var event = new Event(null,target.project(),null,null,"PROJECT_DUE","项目即将截止",project.getName(),
                    "PROJECT_DUE:" + target.project() + ":" + project.getDeadline());
            access.memberIds(target.project()).forEach(user -> notices.send(user,event));
        } else {
            Task task = tasks.lockInProject(target.task(),target.project());
            if (task.getStatus() == Task.State.COMPLETED || !due(task.getDeadline(),now,end)) return;
            var event = new Event(null,target.project(),target.task(),null,"TASK_DUE","任务即将截止",task.getTitle(),
                    "TASK_DUE:" + target.task() + ":" + task.getDeadline());
            tasks.assigneeIds(target.task()).stream()
                    .filter(user -> access.member(target.project(),user)).forEach(user -> notices.send(user,event));
        }
    }

    private static boolean due(Instant value, Instant now, Instant end) {
        return value != null && value.isAfter(now) && !value.isAfter(end);
    }
}
