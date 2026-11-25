package cn.unitask.comment;

import cn.unitask.attachment.AttachmentService;
import cn.unitask.common.Inputs;
import cn.unitask.common.Problem;
import cn.unitask.common.Sql;
import cn.unitask.notification.NotificationService;
import cn.unitask.notification.NotificationService.Event;
import cn.unitask.project.ProjectAccess;
import cn.unitask.task.Task;
import cn.unitask.task.TaskData;
import cn.unitask.task.TaskService;
import cn.unitask.user.UserService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class WorkflowService {
    private final Sql sql;
    private final TaskData data;
    private final TaskService tasks;
    private final ProjectAccess access;
    private final UserService users;
    private final AttachmentService files;
    private final NotificationService notices;
    private final ObjectMapper json;

    public WorkflowService(Sql sql, TaskData data, TaskService tasks, ProjectAccess access, UserService users,
                           AttachmentService files, NotificationService notices, ObjectMapper json) {
        this.sql = sql;
        this.data = data;
        this.tasks = tasks;
        this.access = access;
        this.users = users;
        this.files = files;
        this.notices = notices;
        this.json = json;
    }

    @Transactional(readOnly=true)
    public Sql.Page<Map<String,Object>> timeline(long actor, long taskId, int page, int size, String context) {
        data.read(taskId,actor);
        return sql.page(COMMENT_COLUMNS,COMMENT_JOIN + " WHERE c.task_id=?",
                "c.created_at,c.id",(row,i) -> commentDto(row,context),page,size,taskId);
    }

    @Transactional(readOnly=true)
    public Map<String,Object> commentPage(long actor, long taskId, long commentId, int size) {
        data.read(taskId,actor);
        if (size < 1 || size > 100) throw Problem.bad("size","每页 1–100 条");
        long previous = sql.first("""
                SELECT (SELECT COUNT(*) FROM task_comments older WHERE older.task_id=c.task_id
                    AND (older.created_at<c.created_at OR (older.created_at=c.created_at AND older.id<c.id)))
                FROM task_comments c WHERE c.task_id=? AND c.id=?
                """,(row,i)->row.getLong(1),taskId,commentId).orElseThrow(Problem::missing);
        return Map.of("page", previous / size);
    }

    public Map<String,Object> comment(long actor, long taskId, String suppliedBody, AttachmentService.Batch batch, String context) {
        var scope = data.lock(taskId,actor,false);
        Task task = scope.task();
        if (task.getStatus() == Task.State.COMPLETED) throw readOnly();
        String body = Inputs.text(suppliedBody,"body",5000,false);
        if (body == null && batch.empty()) throw Problem.bad("body","请填写评论或添加附件");
        boolean assignee = data.assigneeIds(taskId).contains(actor);
        long commentId = createComment(actor,taskId,assignee ? "PROGRESS" : "DISCUSSION",body,null);
        files.persist(actor,task.getProjectId(),taskId,commentId,batch);
        long leader = scope.project().getLeaderId();
        if (assignee && leader != actor) {
            String summary = users.me(actor).nickname() + "更新了「" + task.getTitle() + "」：" + (body == null ? "上传了附件" : body);
            notices.send(leader,new Event(actor,task.getProjectId(),taskId,commentId,"PROGRESS_COMMENT","任务进展更新",summary));
        }
        return commentById(commentId,context);
    }

    public Map<String,Object> submit(long actor, long taskId, String suppliedBody, AttachmentService.Batch batch, String context) {
        var scope = data.lock(taskId,actor,false);
        Task task = scope.task();
        var assignees = data.assigneeIds(taskId);
        if (!assignees.contains(actor)) throw Problem.forbidden();
        if (!task.getStatus().assignable() || assignees.size() != task.getRequiredCount())
            throw Problem.conflict("CANNOT_SUBMIT","仅在任务名额已满且未进入审核时可提交");
        String body = Inputs.text(suppliedBody,"body",5000,false);
        boolean newResult = body != null || !batch.empty();
        if (!newResult && !data.hasOwnProgress(taskId,actor))
            throw Problem.conflict("RESULT_REQUIRED","请先发表成果评论，或随本次提交附上成果");
        if (!newResult && task.getStatus() == Task.State.REWORK && !hasProgressSinceRejection(taskId))
            throw Problem.conflict("NEW_RESULT_REQUIRED","打回后请补充新的成果再提交");

        int round = task.getReviewRound() + 1;
        long submission = createComment(actor,taskId,"SUBMISSION",body,round);
        files.persist(actor,task.getProjectId(),taskId,submission,batch);
        sql.update("UPDATE tasks SET status='PENDING_REVIEW',review_round=? WHERE id=?",round,taskId);
        data.log(taskId,actor,"REVIEW_REQUESTED",Map.of("reviewRound",round,"submissionId",submission));
        access.revise(task.getProjectId());
        notices.send(scope.project().getLeaderId(),new Event(actor,task.getProjectId(),taskId,submission,
                "REVIEW_REQUESTED","有任务待验收",task.getTitle()));
        return Map.of("task",tasks.detail(actor,taskId,context),"submissionId",submission);
    }

    public Map<String,Object> review(long actor, long taskId, int round, long submission, String decision, String suppliedReason,
                                     String context) {
        var scope = data.lock(taskId,actor,true);
        Task task = scope.task();
        Inputs.choice(decision,"decision",Set.of("APPROVED","REJECTED"));
        boolean approved = decision.equals("APPROVED");
        String reason = Inputs.text(suppliedReason,"reason",5000,!approved);
        Long current = data.currentSubmission(task);
        if (round != task.getReviewRound() || current == null || submission != current) {
            throw Problem.conflict("STALE_REVIEW","该审核轮次已变更，请刷新任务");
        }
        sql.update("""
                INSERT INTO task_reviews(task_id,review_round,submission_id,reviewer_id,decision,reason) VALUES(?,?,?,?,?,?)
                """,taskId,round,submission,actor,decision,reason);
        String state = approved ? "COMPLETED" : "REWORK";
        sql.update("UPDATE tasks SET status=?,completed_at=IF(?='COMPLETED',UTC_TIMESTAMP(6),NULL) WHERE id=?",state,state,taskId);
        String event = approved ? "REVIEW_APPROVED" : "REVIEW_REJECTED";
        long lastCommentId = sql.count("SELECT COALESCE(MAX(id),0) FROM task_comments WHERE task_id=?",taskId);
        data.log(taskId,actor,event,Map.of("reviewRound",round,"submissionId",submission,"lastCommentId",lastCommentId));
        access.revise(task.getProjectId());
        for (long recipient : data.assigneeIds(taskId)) {
            notices.send(recipient,new Event(actor,task.getProjectId(),taskId,submission,event,
                    approved ? "任务验收通过" : "任务被打回",approved ? task.getTitle() : task.getTitle() + "：" + reason));
        }
        return tasks.detail(actor,taskId,context);
    }

    public Map<String,Object> remind(long actor, long taskId, Set<Long> targets) {
        var scope = data.lock(taskId,actor,true);
        Task task = scope.task();
        if (task.getStatus() == Task.State.COMPLETED) throw readOnly();
        List<Long> assignees = data.assigneeIds(taskId);
        if (targets != null && (!assignees.containsAll(targets) || targets.isEmpty()))
            throw Problem.bad("userIds","请选择当前任务负责人");
        if (assignees.isEmpty()) throw Problem.conflict("NO_ASSIGNEES","任务尚未分配负责人");
        List<Long> sent = new ArrayList<>();
        List<Long> skipped = new ArrayList<>();
        for (long recipient : assignees) {
            if (targets != null && !targets.contains(recipient)) continue;
            if (notices.recentlyReminded(taskId,recipient)) {
                skipped.add(recipient);
            } else {
                notices.send(recipient,new Event(actor,task.getProjectId(),taskId,null,"REMINDER","组长发来催办提醒",task.getTitle()));
                notices.rememberReminder(taskId,recipient);
                sent.add(recipient);
            }
        }
        if (sent.isEmpty()) throw Problem.limited("近期已催办，请在上次提醒 5 分钟后重试");
        data.log(taskId,actor,"REMINDER",Map.of("sent",sent,"skipped",skipped));
        return Map.of("sent",sent,"skipped",skipped);
    }

    @Transactional(readOnly=true)
    public Sql.Page<Map<String,Object>> logs(long actor, long taskId, int page, int size) {
        data.read(taskId,actor);
        return sql.page("l.*,u.nickname","FROM task_logs l JOIN users u ON u.id=l.actor_id WHERE l.task_id=?",
                "l.created_at DESC,l.id DESC",(r,i) -> {
                    try {
                        return Sql.fields("id",r.getLong("id"),"actorId",r.getLong("actor_id"),"actorName",r.getString("nickname"),
                                "action",r.getString("action"),"detail",json.readTree(r.getString("detail")),
                                "createdAt",Sql.instant(r,"created_at"));
                    } catch (JsonProcessingException ex) { throw new SQLException("Invalid task log metadata",ex); }
                },page,size,taskId);
    }

    private boolean hasProgressSinceRejection(long task) {
        return sql.count("""
                SELECT COUNT(*) FROM task_comments c
                WHERE c.task_id=? AND c.kind='PROGRESS' AND c.id>COALESCE((
                    SELECT MAX(CAST(JSON_UNQUOTE(JSON_EXTRACT(l.detail,'$.lastCommentId')) AS UNSIGNED))
                    FROM task_logs l WHERE l.task_id=? AND l.action='REVIEW_REJECTED'
                ),0)
                """,task,task)>0;
    }

    private long createComment(long actor,long task,String kind,String body,Integer round) {
        return sql.insert("INSERT INTO task_comments(task_id,author_id,kind,body,review_round) VALUES(?,?,?,?,?)",
                task,actor,kind,body,round);
    }

    private Map<String,Object> commentById(long id,String context) {
        return sql.first("SELECT " + COMMENT_COLUMNS + " " + COMMENT_JOIN + " WHERE c.id=?",
                (r,i) -> commentDto(r,context),id).orElseThrow(Problem::missing);
    }

    private Map<String,Object> commentDto(ResultSet row,String context) throws SQLException {
        Comment comment = Comment.from(row);
        String decision = row.getString("decision");
        return Sql.fields("id",comment.getId(),"authorId",comment.getAuthorId(),"authorName",comment.getAuthorName(),
                "kind",comment.getKind(),"body",comment.getBody(),"reviewRound",comment.getReviewRound(),
                "createdAt",comment.getCreatedAt(),"attachments",files.commentFiles(comment.getId(),context),
                "review",decision == null ? null : Sql.fields("decision",decision,"reason",row.getString("reason"),
                        "reviewerName",row.getString("reviewer_name"),"reviewedAt",Sql.instant(row,"reviewed_at")));
    }

    private static final String COMMENT_COLUMNS = "c.*,u.nickname AS author_name,r.decision,r.reason,r.created_at AS reviewed_at,v.nickname AS reviewer_name";
    private static final String COMMENT_JOIN = """
            FROM task_comments c JOIN users u ON u.id=c.author_id LEFT JOIN task_reviews r ON r.submission_id=c.id
            LEFT JOIN users v ON v.id=r.reviewer_id
            """;

    private static Problem readOnly() { return Problem.conflict("TASK_COMPLETED","任务已完成，仅可查看"); }
}
