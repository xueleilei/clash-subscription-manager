package dev.clashsub.auth;

import dev.clashsub.common.ApiException;
import java.util.UUID;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class CurrentUser {
    public UUID requireId() { return requirePrincipal().id(); }

    public AdminPrincipal requirePrincipal() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AdminPrincipal principal)) {
            throw new ApiException(401, "A0002", "请先登录");
        }
        return principal;
    }
}
