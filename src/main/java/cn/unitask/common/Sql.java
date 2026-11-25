package cn.unitask.common;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class Sql {
    private final JdbcTemplate jdbc;

    public Sql(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(String statement, Object... args) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var prepared = connection.prepareStatement(statement, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) prepared.setObject(i + 1, args[i]);
            return prepared;
        }, keys);
        return keys.getKey().longValue();
    }

    public int update(String statement, Object... args) { return jdbc.update(statement, args); }
    public long count(String statement, Object... args) { return jdbc.queryForObject(statement, Long.class, args); }

    public <T> List<T> list(String statement, RowMapper<T> mapper, Object... args) {
        return jdbc.query(statement, mapper, args);
    }

    public <T> Optional<T> first(String statement, RowMapper<T> mapper, Object... args) {
        return list(statement, mapper, args).stream().findFirst();
    }

    public static Instant instant(ResultSet row, String column) throws SQLException {
        Timestamp timestamp = row.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    public static Timestamp timestamp(Instant time) { return time == null ? null : Timestamp.from(time); }

    public static Map<String, Object> fields(Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) row.put((String) pairs[i], pairs[i + 1]);
        return row;
    }

    public record Page<T>(List<T> items, int page, int size, long total) {}

    public <T> Page<T> page(String projection, String from, String order, RowMapper<T> mapper,
                            int page, int size, Object... args) {
        if (page < 0 || size < 1 || size > 100) throw Problem.bad("page", "页码须大于等于 0，每页 1–100 条");
        long total = count("SELECT COUNT(*) " + from, args);
        List<Object> parameters = new ArrayList<>(Arrays.asList(args));
        parameters.add(size);
        parameters.add((long) page * size);
        var items = list("SELECT " + projection + " " + from + " ORDER BY " + order + " LIMIT ? OFFSET ?",
                mapper, parameters.toArray());
        return new Page<>(items, page, size, total);
    }
}
