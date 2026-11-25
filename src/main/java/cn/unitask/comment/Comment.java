package cn.unitask.comment;

import cn.unitask.common.Sql;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;

public class Comment {
    private long id;
    private long authorId;
    private String authorName;
    private String kind;
    private String body;
    private Integer reviewRound;
    private Instant createdAt;

    public Comment() {}

    public static Comment from(ResultSet row) throws SQLException {
        Comment comment = new Comment();
        comment.setId(row.getLong("id"));
        comment.setAuthorId(row.getLong("author_id"));
        comment.setAuthorName(row.getString("author_name"));
        comment.setKind(row.getString("kind"));
        comment.setBody(row.getString("body"));
        comment.setReviewRound(row.getObject("review_round",Integer.class));
        comment.setCreatedAt(Sql.instant(row,"created_at"));
        return comment;
    }

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    public long getAuthorId() { return authorId; }
    public void setAuthorId(long authorId) { this.authorId = authorId; }
    public String getAuthorName() { return authorName; }
    public void setAuthorName(String authorName) { this.authorName = authorName; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
    public Integer getReviewRound() { return reviewRound; }
    public void setReviewRound(Integer reviewRound) { this.reviewRound = reviewRound; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
