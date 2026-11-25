package cn.unitask.project;

import cn.unitask.common.Sql;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;

public class Project {
    private long id;
    private long leaderId;
    private long revision;
    private String name;
    private String course;
    private String description;
    private String status;
    private Instant deadline;
    private Instant createdAt;
    private Instant updatedAt;

    public Project() {}

    public static Project from(ResultSet row, int ignored) throws SQLException {
        Project project = new Project();
        project.setId(row.getLong("id"));
        project.setLeaderId(row.getLong("leader_id"));
        project.setRevision(row.getLong("revision"));
        project.setName(row.getString("name"));
        project.setCourse(row.getString("course"));
        project.setDescription(row.getString("description"));
        project.setStatus(row.getString("status"));
        project.setDeadline(Sql.instant(row, "deadline"));
        project.setCreatedAt(Sql.instant(row, "created_at"));
        project.setUpdatedAt(Sql.instant(row, "updated_at"));
        return project;
    }

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    public long getLeaderId() { return leaderId; }
    public void setLeaderId(long leaderId) { this.leaderId = leaderId; }
    public long getRevision() { return revision; }
    public void setRevision(long revision) { this.revision = revision; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCourse() { return course; }
    public void setCourse(String course) { this.course = course; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getDeadline() { return deadline; }
    public void setDeadline(Instant deadline) { this.deadline = deadline; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public boolean active() { return "ACTIVE".equals(status); }
}
