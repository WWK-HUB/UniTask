package cn.unitask.task;

import cn.unitask.attachment.AttachmentService;
import cn.unitask.common.Inputs;
import cn.unitask.common.Problem;
import cn.unitask.common.Sql;
import cn.unitask.notification.NotificationService;
import cn.unitask.notification.NotificationService.Event;
import cn.unitask.project.Project;
import cn.unitask.project.ProjectAccess;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class TaskService {
    private static final Set<String> STATUSES = Set.of("UNASSIGNED","IN_PROGRESS","PENDING_REVIEW","REWORK","COMPLETED");
    private static final Set<String> PRIORITIES = Set.of("LOW","NORMAL","HIGH");
    private final Sql sql;
    private final ProjectAccess access;
    private final TaskData data;
    private final NotificationService notices;
    private final AttachmentService files;
    private final Clock clock;

    public TaskService(Sql sql, ProjectAccess access, TaskData data, NotificationService notices, AttachmentService files, Clock clock) {
        this.sql = sql;
        this.access = access;
        this.data = data;
        this.notices = notices;
        this.files = files;
        this.clock = clock;
    }

    @Transactional(readOnly=true)
    public Sql.Page<Map<String,Object>> listing(long actor, long projectId, String status, Long assignee, String priority,
                                               String q, int page, int size) {
        Project project = access.read(projectId,actor);
        if (status != null) Inputs.choice(status,"status",STATUSES);
        if (priority != null) Inputs.choice(priority,"priority",PRIORITIES);
        String pattern = Inputs.likePattern(q);
        return sql.page("t.*", """
                FROM tasks t WHERE t.project_id=? AND t.deleted_at IS NULL
                AND (? IS NULL OR t.status=?) AND (? IS NULL OR t.priority=?)
                AND (? IS NULL OR t.title LIKE ? ESCAPE '!')
                AND (? IS NULL OR EXISTS(SELECT 1 FROM task_assignees a WHERE a.task_id=t.id AND a.user_id=?))
                """, "t.updated_at DESC,t.id DESC",
                (row,i) -> view(Task.from(row,i),actor,project.getLeaderId(),project.active()),
                page,size,projectId,status,status,priority,priority,pattern,pattern,assignee,assignee);
    }

    @Transactional(readOnly=true)
    public Sql.Page<Map<String,Object>> mine(long actor, Long project, String status, Boolean overdue, int page, int size) {
        if (status != null) Inputs.choice(status,"status",STATUSES);
        return sql.page("t.*,p.name AS project_name,p.leader_id,p.status AS project_status",
                """
                FROM task_assignees a JOIN tasks t ON t.id=a.task_id JOIN projects p ON t.project_id=p.id
                JOIN project_members m ON m.project_id=p.id AND m.user_id=a.user_id
                WHERE a.user_id=? AND m.status='ACTIVE' AND t.deleted_at IS NULL
                AND (? IS NULL OR t.project_id=?) AND (? IS NULL OR t.status=?)
                AND (? IS NULL OR COALESCE(t.status<>'COMPLETED' AND t.deadline<?,0)=?)
                """, "t.status='COMPLETED',t.deadline IS NULL,t.deadline,t.id", (r,i) -> {
                    var result = view(Task.from(r,i),actor,r.getLong("leader_id"),r.getString("project_status").equals("ACTIVE"));
                    result.put("projectName",r.getString("project_name"));
                    return result;
                }, page,size,actor,project,project,status,status,overdue,Sql.timestamp(clock.instant()),overdue);
    }

    @Transactional(readOnly=true)
    public Map<String,Object> detail(long actor, long id, String context) {
        var scope = data.read(id,actor);
        var result = view(scope.task(),actor,scope.project().getLeaderId(),scope.project().active());
        result.put("projectName",scope.project().getName());
        result.put("attachments",files.direct(scope.project().getId(),id,context));
        if (Boolean.TRUE.equals(result.get("canRemind"))) result.put("reminderHints",notices.reminderHints(id,data.assigneeIds(id)));
        return result;
    }

    public Map<String,Object> create(long actor, long projectId, JsonNode form, AttachmentService.Batch batch, String context) {
        Project project = access.lockForWrite(projectId,actor,true);
        String title = Inputs.text(form,"title",100,true);
        String description = Inputs.text(form,"description",10000,true);
        int required = Inputs.requiredCount(form);
        List<Long> members = access.memberIds(projectId);
        validateCapacity(required,members.size());
        String priority = form.has("priority") ? Inputs.choice(Inputs.text(form,"priority",10,true),"priority",PRIORITIES) : "NORMAL";
        Instant deadline = validateDeadline(project,Inputs.deadline(form,null,clock));
        long task = sql.insert("""
                INSERT INTO tasks(project_id,title,description,required_count,priority,deadline,created_by) VALUES(?,?,?,?,?,?,?)
                """,projectId,title,description,required,priority,Sql.timestamp(deadline),actor);
        files.persist(actor,projectId,task,null,batch);
        access.revise(projectId);
        data.log(task,actor,"CREATED",Map.of("requiredCount",required));
        notices.sendOthers(members,actor,new Event(actor,projectId,task,null,"TASK_CREATED","发布了新任务",title));
        return detail(actor,task,context);
    }

    public Map<String,Object> edit(long actor, long taskId, JsonNode form, String context) {
        var scope = data.lock(taskId,actor,true);
        Task task = scope.task();
        requireAssignable(task);
        String title = form.has("title") ? Inputs.text(form,"title",100,true) : task.getTitle();
        String description = form.has("description") ? Inputs.text(form,"description",10000,true) : task.getDescription();
        String priority = form.has("priority") ? Inputs.choice(Inputs.text(form,"priority",10,true),"priority",PRIORITIES) : task.getPriority();
        Instant deadline = validateDeadline(scope.project(),Inputs.deadline(form,task.getDeadline(),clock));
        int required = form.has("requiredCount") ? Inputs.requiredCount(form) : task.getRequiredCount();
        List<Long> assignees = data.assigneeIds(taskId);
        if (required != task.getRequiredCount()) validateCapacity(required,access.memberIds(task.getProjectId()).size());
        if (required < assignees.size()) throw Problem.conflict("BELOW_ASSIGNED_COUNT","所需人数不能低于已分配人数");
        if (!title.equals(task.getTitle()) || !description.equals(task.getDescription()) || !priority.equals(task.getPriority())
                || !Objects.equals(deadline,task.getDeadline()) || required != task.getRequiredCount()) {
            sql.update("UPDATE tasks SET title=?,description=?,priority=?,deadline=?,required_count=? WHERE id=?",
                    title,description,priority,Sql.timestamp(deadline),required,taskId);
            access.revise(task.getProjectId());
            data.log(taskId,actor,"TASK_UPDATED",Sql.fields("requiredCount",required,
                    "deadlineChanged",!Objects.equals(deadline,task.getDeadline())));
            notices.sendOthers(assignees,actor,new Event(actor,task.getProjectId(),taskId,null,"TASK_UPDATED","任务内容已更新",title));
        }
        return detail(actor,taskId,context);
    }

    public void delete(long actor, long taskId) {
        var scope = data.lock(taskId,actor,true);
        var task = scope.task();
        if (task.getStatus() != Task.State.UNASSIGNED || !data.assigneeIds(taskId).isEmpty() || data.commentCount(taskId) > 0
                || sql.count("SELECT COUNT(*) FROM attachments WHERE task_id=?",taskId) > 0
                || sql.count("SELECT COUNT(*) FROM task_reviews WHERE task_id=?",taskId) > 0) {
            throw Problem.conflict("TASK_HAS_HISTORY","仅可删除尚无负责人、评论、附件或审核的待分配任务");
        }
        sql.update("UPDATE tasks SET deleted_at=UTC_TIMESTAMP(6) WHERE id=?",taskId);
        access.revise(task.getProjectId());
        data.log(taskId,actor,"DELETED",Map.of());
    }

    public Map<String,Object> claim(long actor, long taskId, boolean add, String context) {
        var scope = data.lock(taskId,actor,false);
        var task = scope.task();
        Set<Long> current = new LinkedHashSet<>(data.assigneeIds(taskId));
        Set<Long> requested = new LinkedHashSet<>(current);
        if (add) {
            if (!requested.add(actor)) throw Problem.conflict("ALREADY_ASSIGNED","你已是任务负责人");
        } else {
            if (!requested.remove(actor)) throw Problem.conflict("NOT_ASSIGNED","你没有认领此任务");
            if (data.hasOwnProgress(taskId,actor)) throw Problem.conflict("HAS_PROGRESS","已有成果记录，请联系组长调整");
        }
        assign(scope.project(),task,actor,current,requested,"SELF");
        return detail(actor,taskId,context);
    }

    public Map<String,Object> setAssignees(long actor, long taskId, Set<Long> requested, String context) {
        var scope = data.lock(taskId,actor,true);
        var task = scope.task();
        assign(scope.project(),task,actor,new LinkedHashSet<>(data.assigneeIds(taskId)),requested,"MANUAL");
        return detail(actor,taskId,context);
    }

    public void assign(Project project, Task task, long actor, Set<Long> current, Set<Long> requested, String source) {
        requireAssignable(task);
        long taskId = task.getId();
        if (requested.size() > task.getRequiredCount()) throw Problem.conflict("TASK_FULL","任务名额已满");
        if (requested.isEmpty() && (data.commentCount(taskId) > 0 || task.getReviewRound() > 0)) {
            throw Problem.conflict("CANNOT_REMOVE_LAST","任务已有协作或审核历史，至少保留一位负责人");
        }
        Set<Long> removed = new LinkedHashSet<>(current);
        removed.removeAll(requested);
        Set<Long> added = new LinkedHashSet<>(requested);
        added.removeAll(current);
        if (added.isEmpty() && removed.isEmpty()) return;

        long projectId = project.getId();
        for (long user : added) access.requireMember(projectId,user);
        for (long user : removed) {
            sql.update("DELETE FROM task_assignees WHERE task_id=? AND user_id=?",taskId,user);
            if (actor != user) notices.send(user,new Event(actor,projectId,taskId,null,"UNASSIGNED","任务分工已调整",task.getTitle()));
        }
        for (long user : added) {
            sql.update("INSERT INTO task_assignees(task_id,project_id,user_id,assigned_by,source) VALUES(?,?,?,?,?)",
                    taskId,projectId,user,actor,source);
            if (actor != user) notices.send(user,new Event(actor,projectId,taskId,null,"ASSIGNED","你被分配了任务",task.getTitle()));
        }
        Task.State next = requested.isEmpty() ? Task.State.UNASSIGNED
                : task.getStatus() == Task.State.REWORK ? Task.State.REWORK : Task.State.IN_PROGRESS;
        sql.update("UPDATE tasks SET status=?,updated_at=UTC_TIMESTAMP(6) WHERE id=?",next.name(),taskId);
        if (!source.equals("RANDOM")) access.revise(projectId);
        data.log(taskId,actor,"ASSIGNEES_CHANGED",Map.of("source",source,"added",added,"removed",removed,
                "previousStatus",task.getStatus().name(),"status",next.name()));
    }

    private Map<String,Object> view(Task task, long actor, long leader, boolean active) {
        List<TaskData.Assignee> assignees = data.assignees(task.getId());
        boolean mine = assignees.stream().anyMatch(a -> a.id() == actor);
        var status = task.getStatus();
        boolean editable = active && status.assignable();
        boolean hasPriorResult = mine && data.hasOwnProgress(task.getId(),actor);
        return Sql.fields("id",task.getId(),"projectId",task.getProjectId(),"title",task.getTitle(),
                "description",task.getDescription(),"requiredCount",task.getRequiredCount(),"assignedCount",assignees.size(),
                "assignees",assignees,"status",status,"priority",task.getPriority(),"deadline",task.getDeadline(),
                "overdue",status != Task.State.COMPLETED && task.getDeadline()!=null && task.getDeadline().isBefore(clock.instant()),
                "reviewRound",task.getReviewRound(),"currentSubmissionId",data.currentSubmission(task),
                "canClaim",editable && !mine && assignees.size()<task.getRequiredCount(),
                "canCancel",editable && mine && !hasPriorResult && (assignees.size()>1 || data.commentCount(task.getId())==0),
                "canEdit",editable && leader==actor,"canComment",active && status!=Task.State.COMPLETED,
                "canSubmit",editable && mine && assignees.size()==task.getRequiredCount(),
                "canReview",active && leader==actor && status==Task.State.PENDING_REVIEW,
                "canRemind",active && leader==actor && status!=Task.State.COMPLETED && !assignees.isEmpty(),
                "isAssignee",mine,"createdAt",task.getCreatedAt(),"updatedAt",task.getUpdatedAt());
    }

    private static void requireAssignable(Task task) {
        if (!task.getStatus().assignable()) throw Problem.conflict("TASK_READ_ONLY","待审核或已完成任务不可修改分工和内容");
    }

    private static void validateCapacity(int required, int members) {
        if (required > members) throw Problem.bad("requiredCount","人数不能超过已加入项目的成员数");
    }

    private static Instant validateDeadline(Project project, Instant deadline) {
        if (deadline != null && project.getDeadline() != null && deadline.isAfter(project.getDeadline())) {
            throw Problem.bad("deadline","任务截止时间不能晚于项目截止时间");
        }
        return deadline;
    }
}
