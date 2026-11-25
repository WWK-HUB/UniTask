package cn.unitask.config;

import java.util.Set;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
class DatabaseCheck {
    @Bean
    ApplicationRunner initializedSchema(JdbcTemplate jdbc) {
        return args -> {
            String version = jdbc.queryForObject("SELECT VERSION()",String.class);
            String timezone = jdbc.queryForObject("SELECT @@session.time_zone",String.class);
            Integer count = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN (
                      'users','projects','project_members','project_invitations','tasks','task_assignees',
                      'task_comments','task_reviews','attachments','notifications','task_logs','allocation_previews')
                    """,Integer.class);
            if (version == null || !version.startsWith("8.4."))
                throw new IllegalStateException("UniTask requires MySQL 8.4");
            if (timezone == null || !Set.of("+00:00","UTC","Etc/UTC").contains(timezone))
                throw new IllegalStateException("Set connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true on the JDBC URL");
            if (count == null || count != 12)
                throw new IllegalStateException("Initialize the database using database/migration/V1__initial_schema.sql");
        };
    }
}
