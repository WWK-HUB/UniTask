package cn.unitask.project;

import cn.unitask.attachment.AttachmentService;
import cn.unitask.common.Inputs;
import cn.unitask.common.Problem;
import cn.unitask.common.Sql;
import cn.unitask.notification.NotificationService;
import cn.unitask.notification.NotificationService.Event;
import cn.unitask.user.UserService;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class ProjectService {
    private static final Set<String> STATUSES = Set.of("ACTIVE", "ARCHIVED");
    private static final Set<String> INVITATIONS = Set.of("PENDING", "ACCEPTED", "DECLINED", "REVOKED");

    private final Sql sql;
    private final ProjectAccess access;
    private final UserService users;
    private final NotificationService notices;
    private final AttachmentService files;
    private final Clock clock;

    public ProjectService(Sql sql, ProjectAccess access, UserService users, NotificationService notices,
                          AttachmentService files, Clock clock) {
        this.sql = sql;
        this.access = access;
        this.users = users;
        this.notices = notices;
        this.files = files;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Sql.Page<Map<String, Object>> list(long actor, String status, String query, int page, int size) {
        if (status != null) Inputs.choice(status, "status", STATUSES);
        String pattern = Inputs.likePattern(query);
        return sql.page("p.*", """
                FROM project_members m JOIN projects p ON p.id=m.project_id
                WHERE m.user_id=? AND m.status='ACTIVE' AND (? IS NULL OR p.status=?)
                AND (? IS NULL OR p.name LIKE ? ESCAPE '!' OR p.course LIKE ? ESCAPE '!')
                """, "p.status,p.updated_at DESC,p.id DESC", (row, i) -> view(Project.from(row,i), actor),
                page, size, actor, status, status, pattern, pattern, pattern);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(long actor, long project, String context) {
        Map<String, Object> result = view(access.read(project, actor), actor);
        result.put("attachments", files.direct(project, null, context));
        return result;
    }

    public Map<String, Object> create(long actor, JsonNode form, AttachmentService.Batch batch, String context) {
        String name = Inputs.text(form, "name", 100, true);
        String course = Inputs.text(form, "course", 100, true);
        String description = Inputs.text(form, "description", 2000, false);
        Instant deadline = Inputs.deadline(form, null, clock);
        long id = sql.insert("INSERT INTO projects(name,course,description,leader_id,deadline) VALUES(?,?,?,?,?)",
                name, course, description, actor, Sql.timestamp(deadline));
        sql.update("INSERT INTO project_members(project_id,user_id) VALUES(?,?)", id, actor);
        files.persist(actor, id, null, null, batch);
        return detail(actor, id, context);
    }

    public Map<String, Object> edit(long actor, long id, JsonNode form, String context) {
        Project project = access.lockForWrite(id, actor, true);
        String name = form.has("name") ? Inputs.text(form, "name", 100, true) : project.getName();
        String course = form.has("course") ? Inputs.text(form, "course", 100, true) : project.getCourse();
        String description = form.has("description") ? Inputs.text(form, "description", 2000, false) : project.getDescription();
        Instant deadline = Inputs.deadline(form, project.getDeadline(), clock);
        if (deadline != null && sql.count("SELECT COUNT(*) FROM tasks WHERE project_id=? AND deleted_at IS NULL AND deadline>?",
                id, Sql.timestamp(deadline)) > 0) {
            throw Problem.bad("deadline", "项目截止时间不能早于已有任务的截止时间");
        }
        sql.update("UPDATE projects SET name=?,course=?,description=?,deadline=? WHERE id=?",
                name, course, description, Sql.timestamp(deadline), id);
        return detail(actor, id, context);
    }

    public Map<String, Object> archive(long actor, long id, String context) {
        access.lockForWrite(id, actor, true);
        if (sql.count("SELECT COUNT(*) FROM tasks WHERE project_id=? AND deleted_at IS NULL AND status<>'COMPLETED'", id) > 0)
            throw Problem.conflict("PROJECT_UNFINISHED", "仍有任务未验收，暂时不能归档");
        sql.update("UPDATE projects SET status='ARCHIVED',archived_at=UTC_TIMESTAMP(6),revision=revision+1 WHERE id=?", id);
        return detail(actor, id, context);
    }

    @Transactional(readOnly = true)
    public Sql.Page<Map<String, Object>> members(long actor, long project, int page, int size) {
        Project model = access.read(project, actor);
        return sql.page("u.id,u.username,u.nickname,m.joined_at,p.leader_id",
                """
                FROM project_members m JOIN users u ON u.id=m.user_id JOIN projects p ON p.id=m.project_id
                WHERE m.project_id=? AND m.status='ACTIVE'
                """, "(u.id=p.leader_id) DESC,m.joined_at,u.id", (row,i) -> Sql.fields(
                        "id", row.getLong("id"), "username", row.getString("username"),
                        "nickname", row.getString("nickname"),
                        "role", row.getLong("id") == model.getLeaderId() ? "LEADER" : "MEMBER",
                        "joinedAt", Sql.instant(row,"joined_at")), page,size,project);
    }

    public void remove(long actor, long project, long member) {
        Project model = access.lockForWrite(project, actor, true);
        if (member == model.getLeaderId()) throw Problem.conflict("REMOVE_LEADER", "请先将组长转让给其他成员");
        access.requireMember(project, member);
        if (sql.count("""
                SELECT COUNT(*) FROM task_assignees a JOIN tasks t ON t.id=a.task_id
                WHERE a.project_id=? AND a.user_id=? AND t.deleted_at IS NULL AND t.status<>'COMPLETED'
                """, project, member) > 0) {
            throw Problem.conflict("MEMBER_HAS_TASKS", "请先调整该成员负责的未完成任务");
        }
        sql.update("UPDATE project_members SET status='REMOVED',removed_at=UTC_TIMESTAMP(6) WHERE project_id=? AND user_id=?",
                project, member);
        access.revise(project);
        notices.send(member, new Event(actor, project, null, null, "MEMBER_REMOVED", "项目成员变更",
                "你已被移出「" + model.getName() + "」"));
    }

    public Map<String,Object> transfer(long actor, long project, long target, String context) {
        Project model = access.lockForWrite(project, actor, true);
        if (target == actor) throw Problem.bad("userId", "请选择另一名成员");
        access.requireMember(project, target);
        sql.update("UPDATE projects SET leader_id=?,revision=revision+1 WHERE id=?", target, project);
        var event = new Event(actor, project, null, null, "LEADER_CHANGED", "项目组长已变更", model.getName());
        notices.send(target, event);
        notices.send(actor, event);
        return detail(actor, project, context);
    }

    public Map<String,Object> invite(long actor, long project, String username) {
        Project model = access.lockForWrite(project, actor, true);
        var invitee = users.lookup(username);
        if (access.member(project, invitee.id())) throw Problem.conflict("ALREADY_JOINED", "该账号已加入项目");
        if (sql.count("SELECT COUNT(*) FROM project_invitations WHERE project_id=? AND pending_invitee=?", project, invitee.id()) > 0)
            throw Problem.conflict("INVITE_PENDING", "该账号已有待处理邀请");
        long invite = sql.insert("INSERT INTO project_invitations(project_id,invitee_id,inviter_id) VALUES(?,?,?)",
                project, invitee.id(), actor);
        notices.send(invitee.id(), new Event(actor, project, null, null, "INVITE", "收到项目邀请",
                "邀请你加入「" + model.getName() + "」"));
        return invitation(invite);
    }

    @Transactional(readOnly = true)
    public Sql.Page<Map<String,Object>> invitations(long actor, Long project, String status, int page, int size) {
        if (status != null) Inputs.choice(status, "status", INVITATIONS);
        if (project != null && access.read(project, actor).getLeaderId() != actor) throw Problem.forbidden();
        String owner = project == null ? "i.invitee_id=?" : "i.project_id=?";
        return sql.page(INVITE_COLUMNS, INVITE_JOIN + " WHERE " + owner + " AND (? IS NULL OR i.status=?)",
                "i.created_at DESC,i.id DESC", ProjectService::inviteRow, page, size,
                project == null ? actor : project, status, status);
    }

    public Map<String,Object> respond(long actor, long id, boolean accepted) {
        long projectId = sql.first("SELECT project_id FROM project_invitations WHERE id=? AND invitee_id=?",
                (row,i) -> row.getLong(1), id, actor).orElseThrow(Problem::missing);
        Project project = access.lock(projectId);
        access.requireActive(project);
        String status = accepted ? "ACCEPTED" : "DECLINED";
        int changed = sql.update("""
                UPDATE project_invitations SET status=?,resolved_at=UTC_TIMESTAMP(6)
                WHERE id=? AND invitee_id=? AND status='PENDING'
                """, status, id, actor);
        if (changed == 0) throw Problem.conflict("INVITE_RESOLVED", "邀请已处理，请刷新");
        if (accepted) {
            sql.update("""
                    INSERT INTO project_members(project_id,user_id) VALUES(?,?)
                    ON DUPLICATE KEY UPDATE status='ACTIVE',joined_at=UTC_TIMESTAMP(6),removed_at=NULL
                    """, projectId, actor);
            access.revise(projectId);
            notices.send(project.getLeaderId(), new Event(actor, projectId, null, null, "INVITE_ACCEPTED", "邀请已接受",
                    users.me(actor).nickname() + "已加入「" + project.getName() + "」"));
        }
        return invitation(id);
    }

    public Map<String,Object> revoke(long actor, long project, long id) {
        access.lockForWrite(project, actor, true);
        if (sql.count("SELECT COUNT(*) FROM project_invitations WHERE id=? AND project_id=?", id, project) == 0)
            throw Problem.missing();
        if (sql.update("""
                UPDATE project_invitations SET status='REVOKED',resolved_at=UTC_TIMESTAMP(6)
                WHERE id=? AND project_id=? AND status='PENDING'
                """, id, project) == 0) throw Problem.conflict("INVITE_RESOLVED", "邀请已处理，请刷新");
        return invitation(id);
    }

    @Transactional(readOnly = true)
    public Map<String,Object> stats(long actor, long project) {
        access.read(project, actor);
        var output = progress(project);
        output.put("members", sql.list("""
                SELECT u.id,u.nickname,
                    (SELECT COUNT(*) FROM task_assignees a JOIN tasks t ON t.id=a.task_id
                        WHERE a.project_id=m.project_id AND a.user_id=m.user_id AND t.deleted_at IS NULL) AS assigned,
                    (SELECT COUNT(*) FROM task_assignees a JOIN tasks t ON t.id=a.task_id
                        WHERE a.project_id=m.project_id AND a.user_id=m.user_id AND t.deleted_at IS NULL AND t.status='COMPLETED') AS completed,
                    (SELECT COUNT(*) FROM task_comments c JOIN tasks t ON t.id=c.task_id
                        WHERE t.project_id=m.project_id AND c.author_id=m.user_id AND c.kind<>'SUBMISSION') AS comments,
                    (SELECT COUNT(*) FROM attachments f WHERE f.project_id=m.project_id AND f.uploaded_by=m.user_id) AS files
                FROM project_members m JOIN users u ON u.id=m.user_id WHERE m.project_id=? AND m.status='ACTIVE'
                ORDER BY u.nickname,u.id
                """, (r,i) -> Sql.fields("id",r.getLong("id"), "nickname",r.getString("nickname"),
                        "assignedCount",r.getLong("assigned"), "completedCount",r.getLong("completed"),
                        "commentCount",r.getLong("comments"), "attachmentCount",r.getLong("files")), project));
        return output;
    }

    private Map<String,Object> progress(long projectId) {
        return sql.first("""
                SELECT COUNT(*) AS total,COALESCE(SUM(status='COMPLETED'),0) AS completed
                FROM tasks WHERE project_id=? AND deleted_at IS NULL
                """, (r,i) -> {
            long total = r.getLong("total");
            long completed = r.getLong("completed");
            return Sql.fields("taskCount",total, "completedCount",completed,
                    "progressPercent",total == 0 ? 0 : Math.round(100.0 * completed / total));
        }, projectId).orElseThrow();
    }

    private Map<String,Object> view(Project project, long actor) {
        var result = Sql.fields("id", project.getId(), "name", project.getName(), "course", project.getCourse(),
                "description", project.getDescription(), "deadline", project.getDeadline(), "status", project.getStatus(),
                "role", project.getLeaderId() == actor ? "LEADER" : "MEMBER", "leaderId", project.getLeaderId(),
                "createdAt", project.getCreatedAt(), "updatedAt", project.getUpdatedAt());
        result.putAll(progress(project.getId()));
        return result;
    }

    private static final String INVITE_COLUMNS = "i.*,p.name,p.course,p.status AS project_status,host.nickname AS inviter_name,guest.username AS invitee_username,guest.nickname AS invitee_name";
    private static final String INVITE_JOIN = """
            FROM project_invitations i JOIN projects p ON p.id=i.project_id
            JOIN users host ON host.id=i.inviter_id JOIN users guest ON guest.id=i.invitee_id
            """;

    private Map<String,Object> invitation(long id) {
        return sql.first("SELECT " + INVITE_COLUMNS + " " + INVITE_JOIN + " WHERE i.id=?", ProjectService::inviteRow, id)
                .orElseThrow(Problem::missing);
    }

    private static Map<String,Object> inviteRow(ResultSet r, int i) throws SQLException {
        return Sql.fields("id",r.getLong("id"),"projectId",r.getLong("project_id"),"projectName",r.getString("name"),
                "course",r.getString("course"),"projectStatus",r.getString("project_status"),"status",r.getString("status"),
                "inviterName",r.getString("inviter_name"),"inviteeName",r.getString("invitee_name"),
                "inviteeUsername",r.getString("invitee_username"),"createdAt",Sql.instant(r,"created_at"),
                "resolvedAt",Sql.instant(r,"resolved_at"));
    }
}
