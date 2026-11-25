package cn.unitask.task;

import cn.unitask.common.Problem;
import cn.unitask.common.Sql;
import cn.unitask.project.Project;
import cn.unitask.project.ProjectAccess;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Repository;

@Repository
public class TaskData {
    public record Scope(Project project, Task task) {}
    public record Assignee(long id, String username, String nickname) {}

    private final Sql sql;
    private final ProjectAccess access;
    private final ObjectMapper json;

    public TaskData(Sql sql, ProjectAccess access, ObjectMapper json) {
        this.sql = sql;
        this.access = access;
        this.json = json;
    }

    public Scope read(long id, long actor) {
        Task task = sql.first("SELECT * FROM tasks WHERE id=? AND deleted_at IS NULL", Task::from,id).orElseThrow(Problem::missing);
        return new Scope(access.read(task.getProjectId(),actor),task);
    }

    public Scope lock(long id, long actor, boolean leaderOnly) {
        long projectId = sql.first("SELECT project_id FROM tasks WHERE id=? AND deleted_at IS NULL",
                (r,i) -> r.getLong(1), id).orElseThrow(Problem::missing);
        Project project = access.lockForWrite(projectId,actor,leaderOnly);
        return new Scope(project, lockInProject(id,projectId));
    }

    public Task lockInProject(long id, long project) {
        return sql.first("SELECT * FROM tasks WHERE id=? AND project_id=? AND deleted_at IS NULL FOR UPDATE",
                Task::from,id,project).orElseThrow(Problem::missing);
    }

    public List<Assignee> assignees(long task) {
        return sql.list("""
                SELECT u.id,u.username,u.nickname FROM task_assignees a JOIN users u ON u.id=a.user_id
                WHERE a.task_id=? ORDER BY a.assigned_at,u.id
                """, (r,i) -> new Assignee(r.getLong("id"),r.getString("username"),r.getString("nickname")), task);
    }

    public List<Long> assigneeIds(long task) { return assignees(task).stream().map(Assignee::id).toList(); }

    public Long currentSubmission(Task task) {
        if (task.getStatus() != Task.State.PENDING_REVIEW) return null;
        return sql.first("SELECT id FROM task_comments WHERE task_id=? AND review_round=? AND kind='SUBMISSION'",
                (r,i) -> r.getLong(1),task.getId(),task.getReviewRound()).orElse(null);
    }

    public long commentCount(long task) {
        return sql.count("SELECT COUNT(*) FROM task_comments WHERE task_id=?",task);
    }

    public boolean hasOwnProgress(long task, long author) {
        return sql.count("SELECT COUNT(*) FROM task_comments WHERE task_id=? AND author_id=? AND kind IN('PROGRESS','SUBMISSION')",
                task,author) > 0;
    }

    public void log(long task, long actor, String action, Map<String,?> info) {
        try {
            sql.update("INSERT INTO task_logs(task_id,actor_id,action,detail) VALUES(?,?,?,?)",
                    task,actor,action,json.writeValueAsString(info));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException(error);
        }
    }
}
