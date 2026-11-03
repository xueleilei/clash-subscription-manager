package dev.clashsub.auth;

import dev.clashsub.common.ApiResult;
import java.util.UUID;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SessionController {
    private final CurrentUser currentUser;
    public SessionController(CurrentUser currentUser) { this.currentUser = currentUser; }

    @GetMapping("/api/v1/csrf")
    public ApiResult<CsrfVO> csrf(CsrfToken token) {
        return ApiResult.success(new CsrfVO(token.getHeaderName(), token.getParameterName(), token.getToken()));
    }

    @GetMapping("/api/v1/session")
    public ApiResult<SessionVO> session() {
        var principal = currentUser.requirePrincipal();
        return ApiResult.success(new SessionVO(principal.id(), principal.getUsername()));
    }

    public record CsrfVO(String headerName, String parameterName, String token) { }
    public record SessionVO(UUID id, String username) { }
}
