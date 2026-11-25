-- MySQL 8.4 / InnoDB / UTC. Empty database initialization only.
-- Execute in selected database. Do not run against unrelated existing schemas.
SET NAMES utf8mb4;
SET time_zone = '+00:00';
CREATE TABLE users (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 username VARCHAR(32) CHARACTER SET ascii COLLATE ascii_general_ci NOT NULL UNIQUE,
 nickname VARCHAR(40) NOT NULL, password_hash VARCHAR(100) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE projects (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, name VARCHAR(100) NOT NULL, course VARCHAR(100) NOT NULL,
 description VARCHAR(2000), leader_id BIGINT NOT NULL, deadline DATETIME(6),
 status ENUM('ACTIVE','ARCHIVED') NOT NULL DEFAULT 'ACTIVE', revision BIGINT NOT NULL DEFAULT 0,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6), archived_at DATETIME(6),
 FOREIGN KEY (leader_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE project_members (
 project_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
 status ENUM('ACTIVE','REMOVED') NOT NULL DEFAULT 'ACTIVE', joined_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), removed_at DATETIME(6),
 PRIMARY KEY(project_id,user_id), INDEX idx_member_user(user_id,status,project_id),
 FOREIGN KEY(project_id) REFERENCES projects(id), FOREIGN KEY(user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE project_invitations (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, project_id BIGINT NOT NULL, invitee_id BIGINT NOT NULL, inviter_id BIGINT NOT NULL,
 status ENUM('PENDING','ACCEPTED','DECLINED','REVOKED') NOT NULL DEFAULT 'PENDING',
 pending_invitee BIGINT GENERATED ALWAYS AS (IF(status='PENDING',invitee_id,NULL)) STORED,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), resolved_at DATETIME(6),
 UNIQUE KEY uq_pending(project_id,pending_invitee), INDEX idx_invitee(invitee_id,status),
 FOREIGN KEY(project_id) REFERENCES projects(id), FOREIGN KEY(invitee_id) REFERENCES users(id), FOREIGN KEY(inviter_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE tasks (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, project_id BIGINT NOT NULL, title VARCHAR(100) NOT NULL, description TEXT NOT NULL,
 required_count INT NOT NULL, priority ENUM('LOW','NORMAL','HIGH') NOT NULL DEFAULT 'NORMAL', deadline DATETIME(6),
 status ENUM('UNASSIGNED','IN_PROGRESS','PENDING_REVIEW','REWORK','COMPLETED') NOT NULL DEFAULT 'UNASSIGNED',
 review_round INT NOT NULL DEFAULT 0, deleted_at DATETIME(6), completed_at DATETIME(6),
 created_by BIGINT NOT NULL, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
 CHECK(required_count BETWEEN 1 AND 100), CHECK(review_round >= 0),
 UNIQUE KEY uq_task_project(id,project_id), INDEX idx_task_project(project_id,deleted_at,status), INDEX idx_task_due(status,deadline),
 FOREIGN KEY(project_id) REFERENCES projects(id), FOREIGN KEY(created_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE task_assignees (
 task_id BIGINT NOT NULL, project_id BIGINT NOT NULL, user_id BIGINT NOT NULL, assigned_by BIGINT NOT NULL,
 source ENUM('SELF','MANUAL','RANDOM') NOT NULL, assigned_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(task_id,user_id), INDEX idx_assignee_user(user_id,task_id),
 FOREIGN KEY(task_id,project_id) REFERENCES tasks(id,project_id), FOREIGN KEY(project_id,user_id) REFERENCES project_members(project_id,user_id), FOREIGN KEY(assigned_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE task_comments (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, task_id BIGINT NOT NULL, author_id BIGINT NOT NULL,
 kind ENUM('PROGRESS','DISCUSSION','SUBMISSION') NOT NULL, body TEXT, review_round INT,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uq_submission_round(task_id,review_round), INDEX idx_comment_timeline(task_id,created_at,id),
 FOREIGN KEY(task_id) REFERENCES tasks(id), FOREIGN KEY(author_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE task_reviews (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, task_id BIGINT NOT NULL, review_round INT NOT NULL, submission_id BIGINT NOT NULL,
 reviewer_id BIGINT NOT NULL, decision ENUM('APPROVED','REJECTED') NOT NULL, reason VARCHAR(5000), created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uq_review(task_id,review_round), UNIQUE KEY uq_review_submission(submission_id),
 FOREIGN KEY(task_id) REFERENCES tasks(id), FOREIGN KEY(submission_id) REFERENCES task_comments(id), FOREIGN KEY(reviewer_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE attachments (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, project_id BIGINT NOT NULL, task_id BIGINT, comment_id BIGINT,
 uploaded_by BIGINT NOT NULL, original_name VARCHAR(255) NOT NULL, storage_key VARCHAR(100) CHARACTER SET ascii NOT NULL UNIQUE,
 media_type VARCHAR(120) NOT NULL, size_bytes BIGINT NOT NULL, sha256 CHAR(64) CHARACTER SET ascii NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 CHECK(size_bytes > 0), CHECK(comment_id IS NULL OR task_id IS NOT NULL),
 INDEX idx_attachment_project(project_id,created_at,id), INDEX idx_attachment_task(task_id), INDEX idx_attachment_comment(comment_id),
 FOREIGN KEY(project_id) REFERENCES projects(id), FOREIGN KEY(task_id,project_id) REFERENCES tasks(id,project_id), FOREIGN KEY(comment_id) REFERENCES task_comments(id), FOREIGN KEY(uploaded_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE notifications (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, recipient_id BIGINT NOT NULL, actor_id BIGINT, project_id BIGINT, task_id BIGINT, comment_id BIGINT,
 type VARCHAR(40) NOT NULL, title VARCHAR(150) NOT NULL, summary VARCHAR(1000) NOT NULL, dedupe_key VARCHAR(191) CHARACTER SET ascii,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), read_at DATETIME(6),
 UNIQUE KEY uq_notification_dedupe(recipient_id,dedupe_key), INDEX idx_notification_inbox(recipient_id,read_at,created_at,id), INDEX idx_reminder(task_id,recipient_id,type,created_at),
 FOREIGN KEY(recipient_id) REFERENCES users(id), FOREIGN KEY(actor_id) REFERENCES users(id), FOREIGN KEY(project_id) REFERENCES projects(id), FOREIGN KEY(task_id) REFERENCES tasks(id), FOREIGN KEY(comment_id) REFERENCES task_comments(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE task_logs (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, task_id BIGINT NOT NULL, actor_id BIGINT NOT NULL, action VARCHAR(40) NOT NULL, detail JSON NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), INDEX idx_log_task(task_id,created_at,id),
 FOREIGN KEY(task_id) REFERENCES tasks(id), FOREIGN KEY(actor_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE allocation_previews (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, project_id BIGINT NOT NULL, created_by BIGINT NOT NULL, token_hash CHAR(64) CHARACTER SET ascii NOT NULL UNIQUE,
 project_revision BIGINT NOT NULL, plan JSON NOT NULL, expires_at DATETIME(6) NOT NULL, consumed_at DATETIME(6),
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), INDEX idx_preview_expiry(expires_at),
 FOREIGN KEY(project_id) REFERENCES projects(id), FOREIGN KEY(created_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
