package cn.unitask.project;

import cn.unitask.common.Problem;
import cn.unitask.common.Sql;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ProjectAccess {
    private final Sql sql;

    public ProjectAccess(Sql sql) { this.sql = sql; }

    public Project read(long projectId, long userId) {
        Project project = find(projectId, false);
        requireMember(projectId, userId);
        return project;
    }

    public Project lockForWrite(long projectId, long userId, boolean leaderOnly) {
        Project project = find(projectId, true);
        requireMember(projectId, userId);
        if (leaderOnly && project.getLeaderId() != userId) throw Problem.forbidden();
        requireActive(project);
        return project;
    }

    public Project lock(long projectId) { return find(projectId, true); }

    private Project find(long projectId, boolean locked) {
        return sql.first("SELECT * FROM projects WHERE id=?" + (locked ? " FOR UPDATE" : ""),
                Project::from, projectId).orElseThrow(Problem::missing);
    }

    public void requireActive(Project project) {
        if (!project.active()) throw Problem.conflict("PROJECT_ARCHIVED", "项目已归档，仅可查看");
    }

    public boolean member(long projectId, long userId) {
        return sql.count("SELECT COUNT(*) FROM project_members WHERE project_id=? AND user_id=? AND status='ACTIVE'",
                projectId, userId) == 1;
    }

    public void requireMember(long projectId, long userId) {
        if (!member(projectId, userId)) throw Problem.missing();
    }

    public List<Long> memberIds(long projectId) {
        return sql.list("SELECT user_id FROM project_members WHERE project_id=? AND status='ACTIVE' ORDER BY user_id",
                (row, i) -> row.getLong(1), projectId);
    }

    public void revise(long projectId) {
        sql.update("UPDATE projects SET revision=revision+1 WHERE id=?", projectId);
    }
}
