package cn.unitask.allocation;

import cn.unitask.common.Problem;
import cn.unitask.common.Sql;
import cn.unitask.project.Project;
import cn.unitask.project.ProjectAccess;
import cn.unitask.task.Task;
import cn.unitask.task.TaskData;
import cn.unitask.task.TaskService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class AllocationService {
    private record Saved(long id, long author, long revision, String plan, Instant expires, Instant consumed) {}
    private final Sql sql;
    private final ProjectAccess access;
    private final TaskData data;
    private final TaskService tasks;
    private final ObjectMapper json;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public AllocationService(Sql sql, ProjectAccess access, TaskData data, TaskService tasks,
                             ObjectMapper json, Clock clock) {
        this.sql = sql;
        this.access = access;
        this.data = data;
        this.tasks = tasks;
        this.json = json;
        this.clock = clock;
    }

    public Map<String,Object> preview(long actor, long projectId, boolean includeLeader, Set<Long> selected) {
        Project project = access.lockForWrite(projectId,actor,true);
        Map<Long,Long> loads = new TreeMap<>();
        sql.list("""
                SELECT m.user_id,(SELECT COUNT(*) FROM task_assignees a JOIN tasks t ON t.id=a.task_id
                    WHERE a.project_id=m.project_id AND a.user_id=m.user_id AND t.deleted_at IS NULL AND t.status<>'COMPLETED')
                FROM project_members m WHERE m.project_id=? AND m.status='ACTIVE'
                """, (r,i) -> {
                    loads.put(r.getLong(1),r.getLong(2));
                    return r.getLong(1);
                },projectId);
        if (!includeLeader) loads.remove(project.getLeaderId());
        var eligible = sql.list("""
                SELECT * FROM tasks WHERE project_id=? AND deleted_at IS NULL
                AND status IN('UNASSIGNED','IN_PROGRESS','REWORK') ORDER BY id
                """,Task::from,projectId);
        if (selected != null) {
            eligible = eligible.stream().filter(t -> selected.contains(t.getId())).toList();
            if (eligible.size() != selected.size()) throw Problem.bad("taskIds","仅可选择本项目中可分配的任务");
        }
        var slots = eligible.stream().map(t -> new BalancedAllocation.Slot(t.getId(),t.getRequiredCount(),
                data.assigneeIds(t.getId()))).toList();
        var plan = BalancedAllocation.build(slots,loads,random);
        byte[] nonce = new byte[32];
        random.nextBytes(nonce);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(nonce);
        Instant expires = clock.instant().plus(Duration.ofMinutes(10));
        sql.update("""
                INSERT INTO allocation_previews(project_id,created_by,token_hash,project_revision,plan,expires_at)
                VALUES(?,?,?,?,?,?)
                """,projectId,actor,digest(token),project.getRevision(),encode(plan),Sql.timestamp(expires));
        return Sql.fields("previewToken",token,"expiresAt",expires,"assignments",plan.assignments(),"memberLoads",plan.memberLoads());
    }

    public Map<String,Object> confirm(long actor, long projectId, String token) {
        Project project = access.lockForWrite(projectId,actor,true);
        if (!token.matches("[A-Za-z0-9_-]{43}")) throw stale();
        Saved saved = sql.first("""
                SELECT * FROM allocation_previews WHERE project_id=? AND token_hash=? FOR UPDATE
                """,(r,i) -> new Saved(r.getLong("id"),r.getLong("created_by"),r.getLong("project_revision"),
                        r.getString("plan"),Sql.instant(r,"expires_at"),Sql.instant(r,"consumed_at")),projectId,digest(token))
                .orElseThrow(AllocationService::stale);
        if (saved.author()!=actor || saved.revision()!=project.getRevision() || saved.consumed()!=null
                || !saved.expires().isAfter(clock.instant())) throw stale();

        var plan = decode(saved.plan());
        boolean changes = false;
        for (var assignment : plan.assignments()) {
            Task task = data.lockInProject(assignment.taskId(),projectId);
            if (!task.getStatus().assignable()) throw stale();
            if (assignment.newUserIds().isEmpty()) continue;
            Set<Long> current = new LinkedHashSet<>(data.assigneeIds(task.getId()));
            Set<Long> requested = new LinkedHashSet<>(current);
            for (long id : assignment.newUserIds()) {
                if (!requested.add(id) || !access.member(projectId,id)) throw stale();
            }
            if (requested.size() > task.getRequiredCount()) throw stale();
            tasks.assign(project,task,actor,current,requested,"RANDOM");
            changes = true;
        }
        sql.update("UPDATE allocation_previews SET consumed_at=UTC_TIMESTAMP(6) WHERE id=?",saved.id());
        if (changes) access.revise(projectId);
        return Sql.fields("assignments",plan.assignments(),"memberLoads",plan.memberLoads());
    }

    @Scheduled(fixedDelay=3_600_000, initialDelay=3_600_000)
    public void cleanPreviews() {
        sql.update("DELETE FROM allocation_previews WHERE expires_at<?",Sql.timestamp(clock.instant().minus(Duration.ofHours(1))));
    }

    private String encode(BalancedAllocation.Plan plan) {
        try { return json.writeValueAsString(plan); }
        catch (JsonProcessingException ex) { throw new IllegalStateException(ex); }
    }

    private BalancedAllocation.Plan decode(String plan) {
        try { return json.readValue(plan,BalancedAllocation.Plan.class); }
        catch (JsonProcessingException ex) { throw new IllegalStateException(ex); }
    }

    private static String digest(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    private static Problem stale() {
        return Problem.conflict("STALE_PREVIEW","预览已失效或已确认，请重新生成");
    }
}
