package dev.clashsub.source;

import dev.clashsub.auth.CurrentUser;
import dev.clashsub.common.ApiResult;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/sources")
public class SourceController {
    private final CurrentUser user;
    private final SourceService service;
    public SourceController(CurrentUser user, SourceService service) { this.user = user; this.service = service; }
    @GetMapping public ApiResult<List<SourceView>> list() { return ApiResult.success(service.list(user.requireId())); }
    @PostMapping public ApiResult<SourceView> create(@RequestBody SourceCommand command) {
        if (command.id() != null) { throw dev.clashsub.common.ApiException.invalid("新来源不能指定 ID"); }
        return ApiResult.success(service.save(user.requireId(), command));
    }
    @PatchMapping("/{id}") public ApiResult<SourceView> update(@PathVariable UUID id, @RequestBody SourceCommand command) {
        return ApiResult.success(service.save(user.requireId(), new SourceCommand(id, command.expectedVersion(), command.name(), command.enabled(),
                command.refreshIntervalSeconds(), command.inputFormat(), command.url(), command.headers(), command.userAgent(), command.outboundProxy())));
    }
    @GetMapping("/{id}/credentials") public ResponseEntity<ApiResult<SourceRequest>> credentials(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResult.success(service.credentials(user.requireId(), id)));
    }
    @GetMapping("/proxy-options") public ApiResult<List<String>> proxies() { user.requireId(); return ApiResult.success(service.proxyNames()); }
}
