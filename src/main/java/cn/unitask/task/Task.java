package cn.unitask.task;

import cn.unitask.common.Sql;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;

public class Task {
    public enum State {
        UNASSIGNED, IN_PROGRESS, PENDING_REVIEW, REWORK, COMPLETED;
        public boolean assignable() { return this == UNASSIGNED || this == IN_PROGRESS || this == REWORK; }
    }

    private long id;
    private long projectId;
    private String title;
    private String description;
    private int requiredCount;
    private String priority;
    private State status;
    private Instant deadline;
    private int reviewRound;
    private Instant createdAt;
    private Instant updatedAt;

    public Task() {}

    public static Task from(ResultSet row, int ignored) throws SQLException {
        Task task = new Task();
        task.setId(row.getLong("id"));
        task.setProjectId(row.getLong("project_id"));
        task.setTitle(row.getString("title"));
        task.setDescription(row.getString("description"));
        task.setRequiredCount(row.getInt("required_count"));
        task.setPriority(row.getString("priority"));
        task.setStatus(State.valueOf(row.getString("status")));
        task.setDeadline(Sql.instant(row, "deadline"));
        task.setReviewRound(row.getInt("review_round"));
        task.setCreatedAt(Sql.instant(row, "created_at"));
        task.setUpdatedAt(Sql.instant(row, "updated_at"));
        return task;
    }

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    public long getProjectId() { return projectId; }
    public void setProjectId(long projectId) { this.projectId = projectId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public int getRequiredCount() { return requiredCount; }
    public void setRequiredCount(int requiredCount) { this.requiredCount = requiredCount; }
    public String getPriority() { return priority; }
    public void setPriority(String priority) { this.priority = priority; }
    public State getStatus() { return status; }
    public void setStatus(State status) { this.status = status; }
    public Instant getDeadline() { return deadline; }
    public void setDeadline(Instant deadline) { this.deadline = deadline; }
    public int getReviewRound() { return reviewRound; }
    public void setReviewRound(int reviewRound) { this.reviewRound = reviewRound; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
