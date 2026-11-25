package cn.unitask.user;

import cn.unitask.common.Inputs;
import cn.unitask.common.Problem;
import cn.unitask.common.Sql;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {
    public record Profile(long id, String username, String nickname) {}
    private final Sql sql;
    private final PasswordEncoder passwords;

    public UserService(Sql sql, PasswordEncoder passwords) {
        this.sql = sql;
        this.passwords = passwords;
    }

    private static User row(ResultSet row, int ignored) throws SQLException {
        User user = new User();
        user.setId(row.getLong("id"));
        user.setUsername(row.getString("username"));
        user.setNickname(row.getString("nickname"));
        user.setPasswordHash(row.getString("password_hash"));
        return user;
    }

    private static Profile profile(User user) {
        return new Profile(user.getId(), user.getUsername(), user.getNickname());
    }

    public User find(String username) {
        return sql.first("SELECT * FROM users WHERE username=?", UserService::row,
                Inputs.normalizeUsername(username)).orElse(null);
    }

    public Profile lookup(String username) {
        User user = find(Inputs.username(username));
        if (user == null) throw Problem.missing();
        return profile(user);
    }

    public Profile me(long id) { return profile(byId(id, false)); }

    private User byId(long id, boolean lock) {
        return sql.first("SELECT * FROM users WHERE id=?" + (lock ? " FOR UPDATE" : ""),
                UserService::row, id).orElseThrow(Problem::missing);
    }

    @Transactional
    public Profile register(String username, String nickname, String password) {
        String account = Inputs.username(username);
        String displayName = Inputs.text(nickname, "nickname", 40, true);
        try {
            long id = sql.insert("INSERT INTO users(username,nickname,password_hash) VALUES(?,?,?)",
                    account, displayName, passwords.encode(Inputs.password(password)));
            return new Profile(id, account, displayName);
        } catch (DuplicateKeyException ex) {
            throw Problem.conflict("USERNAME_EXISTS", "该账号已被使用");
        }
    }

    @Transactional
    public Profile rename(long id, String nickname) {
        sql.update("UPDATE users SET nickname=? WHERE id=?", Inputs.text(nickname, "nickname", 40, true), id);
        return me(id);
    }

    @Transactional
    public void changePassword(long id, String current, String replacement) {
        User user = byId(id, true);
        if (!passwords.matches(Inputs.password(current), user.getPasswordHash())) {
            throw Problem.bad("currentPassword", "当前密码不正确");
        }
        sql.update("UPDATE users SET password_hash=? WHERE id=?", passwords.encode(Inputs.password(replacement)), id);
    }
}
