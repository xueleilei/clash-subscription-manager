package dev.clashsub.source;
import dev.clashsub.auth.CurrentUser;
import dev.clashsub.common.ApiResult;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1")
public class SourceRemovalController {
    public record RemovalCommand(RemovalMode mode) { }
    private final CurrentUser user;
    private final SourceRemovalService service;
    public SourceRemovalController(CurrentUser user, SourceRemovalService service) { this.user = user; this.service = service; }
    @PostMapping("/sources/{id}/removal/preview") public ApiResult<RemovalPreview> preview(@PathVariable UUID id) { return ApiResult.success(service.preview(user.requireId(), id)); }
    @PostMapping("/source-removals/{id}/apply") public ApiResult<RemovalResult> remove(@PathVariable UUID id, @RequestBody RemovalCommand command) { return ApiResult.success(service.apply(user.requireId(), id, command.mode())); }
}
