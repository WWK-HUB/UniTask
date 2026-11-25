package cn.unitask.security;

import cn.unitask.user.User;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

public final class AccountPrincipal extends org.springframework.security.core.userdetails.User {
    private final long id;

    public AccountPrincipal(User user) {
        super(user.getUsername(), user.getPasswordHash(), AuthorityUtils.createAuthorityList("ROLE_USER"));
        this.id = user.getId();
    }

    public long getId() { return id; }

    public static Long currentId() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof AccountPrincipal account
                ? account.id : null;
    }
}
