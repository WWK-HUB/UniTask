package cn.unitask.attachment;

import cn.unitask.common.Inputs;
import cn.unitask.common.Problem;
import cn.unitask.common.Sql;
import cn.unitask.project.ProjectAccess;
import java.io.IOException;
import java.nio.file.*;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

@Service
public class AttachmentService {
    private record Part(Path staged, String key, String name, String media, long size, String sha) {}
    public record Limits(int files, long fileBytes, long batchBytes) {}
    public record Download(Path path, String name, String mime, long size, boolean preview, String category) {}

    public final class Batch implements AutoCloseable {
        private final List<Part> parts = new ArrayList<>();
        public boolean empty() { return parts.isEmpty(); }
        @Override public void close() { parts.forEach(part -> remove(part.staged())); }
    }

    private final Sql sql;
    private final ProjectAccess access;
    private final Path root;
    private final Path staging;
    private final Path storage;
    private final int maxFiles;
    private final long maxFile;
    private final long maxBatch;
    private final long maxProject;
    private final Logger log = LoggerFactory.getLogger(getClass());

    public AttachmentService(Sql sql, ProjectAccess access,
            @Value("${unitask.files.root}") Path root,
            @Value("${unitask.files.max-files}") int maxFiles,
            @Value("${unitask.files.max-file-bytes:104857600}") long maxFile,
            @Value("${unitask.files.max-batch-bytes}") long maxBatch,
            @Value("${unitask.files.max-project-bytes}") long maxProject) throws IOException {
        this.sql = sql;
        this.access = access;
        this.maxFiles = maxFiles;
        this.maxFile = maxFile;
        this.maxBatch = maxBatch;
        this.maxProject = maxProject;
        this.root = root.toAbsolutePath().normalize();
        Files.createDirectories(this.root);
        if (!this.root.equals(this.root.toRealPath())) throw new IOException("Upload root must not traverse symlinks");
        this.staging = directory("staging");
        this.storage = directory("objects");
    }

    private Path directory(String name) throws IOException {
        Path dir = root.resolve(name);
        Files.createDirectories(dir);
        if (!dir.equals(dir.toRealPath())) throw new IOException("Invalid upload directory");
        return dir;
    }

    public Limits limits() { return new Limits(maxFiles, maxFile, maxBatch); }

    public Batch stage(List<MultipartFile> uploads) {
        Batch batch = new Batch();
        if (uploads == null || uploads.isEmpty()) return batch;
        if (uploads.size() > maxFiles) throw tooLarge();
        long total = 0;
        try {
            for (MultipartFile upload : uploads) {
                if (upload.isEmpty()) throw Problem.bad("files", "不接受空文件");
                if (upload.getSize() > maxFile || upload.getSize() > maxBatch - total) throw tooLarge();
                Part part = stageOne(upload, maxBatch - total);
                batch.parts.add(part);
                total += part.size();
            }
            return batch;
        } catch (IOException failure) {
            batch.close();
            throw new Problem(500, "FILE_WRITE_ERROR", "不能暂存附件，请重试");
        } catch (RuntimeException failure) {
            batch.close();
            throw failure;
        }
    }

    private Part stageOne(MultipartFile upload, long remainingBytes) throws IOException {
        if (!staging.equals(staging.toRealPath())) throw new IOException("Invalid staging directory");
        String key = UUID.randomUUID().toString();
        String name = normalizeName(upload.getOriginalFilename());
        Path staged = Files.createTempFile(staging, "upload-", ".tmp");
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            long length = 0;
            try (var in = new DigestInputStream(upload.getInputStream(), sha);
                 var out = Files.newOutputStream(staged)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    length += read;
                    if (length > maxFile || length > remainingBytes) throw tooLarge();
                    out.write(buffer, 0, read);
                }
            }
            if (length != upload.getSize()) throw Problem.bad("files", "文件长度不符，请重新上传");
            var type = FileTypes.inspect(name, staged);
            return new Part(staged, key, name, type.media(), length, HexFormat.of().formatHex(sha.digest()));
        } catch (IOException | RuntimeException ex) {
            remove(staged);
            throw ex;
        } catch (java.security.NoSuchAlgorithmException ex) {
            remove(staged);
            throw new IllegalStateException(ex);
        }
    }

    static String normalizeName(String filename) {
        String path = filename == null ? "" : filename.replace('\\', '/');
        String name = path.substring(path.lastIndexOf('/') + 1).replaceAll("[\\p{Cc}\\p{Cf}<>:\"|?*]", "_").strip();
        if (name.isBlank()) throw Problem.bad("files", "文件名无效");
        int length = name.codePointCount(0, name.length());
        if (length <= 255) return name;
        int dot = name.lastIndexOf('.');
        String suffix = dot > 0 && name.length() - dot <= 14 ? name.substring(dot) : "";
        return name.substring(0, name.offsetByCodePoints(0, 255 - suffix.length())) + suffix;
    }

    public void persist(long user, long project, Long task, Long comment, Batch batch) {
        if (batch.empty()) return;
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Transaction required");
        long used = sql.count("SELECT COALESCE(SUM(size_bytes),0) FROM attachments WHERE project_id=?", project);
        long requested = batch.parts.stream().mapToLong(Part::size).sum();
        if (requested > maxProject - used) throw tooLarge();

        List<Path> moved = new ArrayList<>();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) moved.forEach(AttachmentService.this::remove);
                batch.close();
            }
        });
        for (Part part : batch.parts) {
            sql.update("""
                    INSERT INTO attachments(project_id,task_id,comment_id,uploaded_by,original_name,storage_key,
                    media_type,size_bytes,sha256) VALUES(?,?,?,?,?,?,?,?,?)
                    """, project, task, comment, user, part.name(), part.key(), part.media(), part.size(), part.sha());
            try {
                Path target = object(part.key());
                Files.move(part.staged(), target);
                moved.add(target);
            } catch (IOException failure) {
                log.warn("Attachment move failed ({})", failure.getClass().getSimpleName());
                throw new Problem(500, "FILE_WRITE_ERROR", "附件保存失败，操作已取消");
            }
        }
    }

    public List<Map<String,Object>> uploaders(long actor, long project) {
        access.read(project,actor);
        return sql.list("""
                SELECT DISTINCT u.id,u.username,u.nickname
                FROM attachments a JOIN users u ON u.id=a.uploaded_by
                WHERE a.project_id=? ORDER BY u.nickname,u.id
                """, (row,i)->Sql.fields("id",row.getLong("id"),"username",row.getString("username"),
                        "nickname",row.getString("nickname")),project);
    }

    public Sql.Page<Map<String, Object>> projectFiles(long actor, long project, Long task, Long uploader, String category,
                                                      int page, int size, String context) {
        access.read(project, actor);
        if (category != null) Inputs.choice(category, "type", Set.of("image","document","video","audio","archive","text"));
        String typeFilter = switch (category == null ? "" : category) {
            case "image", "video", "audio", "text" -> " AND a.media_type LIKE '" + category + "/%'";
            case "document" -> " AND (" + DOCUMENT + ")";
            case "archive" -> " AND a.media_type LIKE 'application/%' AND NOT (" + DOCUMENT + ")";
            default -> "";
        };
        return sql.page(SELECT, JOIN + """
                WHERE a.project_id=? AND (? IS NULL OR a.task_id=?) AND (? IS NULL OR a.uploaded_by=?)
                """ + typeFilter, "a.created_at DESC,a.id DESC", (row,i) -> dto(row, context), page, size,
                project, task, task, uploader, uploader);
    }

    public List<Map<String,Object>> direct(long project, Long task, String context) {
        return sql.list("SELECT " + SELECT + " " + JOIN + " WHERE a.project_id=? AND a.task_id <=> ? AND a.comment_id IS NULL ORDER BY a.id",
                (row,i) -> dto(row, context), project, task);
    }

    public List<Map<String,Object>> commentFiles(long comment, String context) {
        return sql.list("SELECT " + SELECT + " " + JOIN + " WHERE a.comment_id=? ORDER BY a.id",
                (row,i) -> dto(row, context), comment);
    }

    private static final String SELECT = "a.*,u.nickname AS uploader_name,t.title AS task_title";
    private static final String JOIN = "FROM attachments a JOIN users u ON u.id=a.uploaded_by LEFT JOIN tasks t ON t.id=a.task_id ";
    private static final String DOCUMENT = "a.media_type REGEXP 'pdf|document|word|excel|powerpoint|spreadsheet|presentation'";

    private Map<String,Object> dto(java.sql.ResultSet row, String context) throws java.sql.SQLException {
        long id = row.getLong("id");
        var type = FileTypes.stored(row.getString("media_type"));
        String url = context + "/attachments/" + id;
        return Sql.fields("id", id, "originalName", row.getString("original_name"), "mediaType", type.media(),
                "sizeBytes", row.getLong("size_bytes"), "previewable", type.preview(), "downloadUrl", url + "/download",
                "previewUrl", type.preview() ? url + "/preview" : null, "category", type.category(),
                "taskId", row.getObject("task_id", Long.class), "taskTitle", row.getString("task_title"),
                "uploaderId", row.getLong("uploaded_by"), "uploaderName", row.getString("uploader_name"),
                "createdAt", Sql.instant(row, "created_at"));
    }

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public Download download(long actor, long id) {
        return sql.first("""
                SELECT a.* FROM attachments a JOIN project_members m ON m.project_id=a.project_id AND m.user_id=?
                WHERE a.id=? AND m.status='ACTIVE'
                """, (row, i) -> {
            try {
                Path path = object(row.getString("storage_key"));
                long size = row.getLong("size_bytes");
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) != size)
                    throw new IOException("File missing or inconsistent");
                var type = FileTypes.stored(row.getString("media_type"));
                return new Download(path, row.getString("original_name"), type.media(), size, type.preview(), type.category());
            } catch (IOException failure) {
                log.warn("Attachment {} unavailable", id);
                throw Problem.missing();
            }
        }, actor, id).orElseThrow(Problem::missing);
    }

    Path object(String key) throws IOException {
        if (!key.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}") || !storage.equals(storage.toRealPath()))
            throw new IOException("Invalid storage location");
        Path file = storage.resolve(key);
        if (Files.isSymbolicLink(file)) throw new IOException("Invalid storage object");
        return file;
    }

    private void remove(Path file) {
        try { Files.deleteIfExists(file); }
        catch (IOException failure) { log.warn("Temporary attachment cleanup failed ({})", failure.getClass().getSimpleName()); }
    }

    private static Problem tooLarge() {
        return new Problem(413, "FILE_LIMIT", "附件数量或文件、批次、项目容量超过限制");
    }
}
