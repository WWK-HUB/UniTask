package cn.unitask.attachment;

import cn.unitask.common.Sql;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Profile("maintenance")
@Order(100)
class UploadMaintenance implements ApplicationRunner {
    private final Sql sql;
    private final Path root;
    private final Clock clock;

    UploadMaintenance(Sql sql,@Value("${unitask.files.root}") Path root,Clock clock) {
        this.sql = sql;
        this.root = root.toAbsolutePath().normalize();
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        if (!args.containsOption("unitask-check-uploads")) return;
        boolean apply = args.containsOption("unitask-apply-cleanup");
        Instant cutoff = clock.instant().minus(Duration.ofHours(24));
        for (String folder : List.of("objects","staging")) {
            Path directory = root.resolve(folder);
            if (!directory.equals(directory.toRealPath())) throw new IOException("Unexpected upload storage location");
            try (var entries = Files.list(directory)) {
                for (Path path : (Iterable<Path>) entries::iterator) {
                    String name = path.getFileName().toString();
                    if (!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)
                            || !Files.getLastModifiedTime(path,LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(cutoff)) continue;
                    if (folder.equals("objects")) {
                        if (!name.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")
                                || sql.count("SELECT COUNT(*) FROM attachments WHERE storage_key=?",name) != 0) continue;
                    } else if (!name.matches("upload-[0-9]+\\.tmp")) continue;
                    System.out.println((apply ? "清理 " : "待清理 ") + folder + "/" + name);
                    if (apply) Files.deleteIfExists(path);
                }
            }
        }
    }
}
