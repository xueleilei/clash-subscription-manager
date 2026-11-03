package dev.clashsub.sync;
import dev.clashsub.auth.CurrentUser;
import dev.clashsub.common.ApiResult;
import dev.clashsub.source.SourceRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1")
public class RefreshController {
    public record DecisionCommand(EmptyChoice choice) { }
    private final CurrentUser user;
    private final RefreshTaskRunner runner;
    private final SourceRepository sources;
    private final EmptyDecisionService decisions;
    public RefreshController(CurrentUser user, RefreshTaskRunner runner, SourceRepository sources, EmptyDecisionService decisions) { this.user = user; this.runner = runner; this.sources = sources; this.decisions = decisions; }
    @PostMapping("/sources/{id}/refresh") public ResponseEntity<ApiResult<TaskView>> refresh(@PathVariable UUID id) { return ResponseEntity.accepted().body(ApiResult.success(runner.enqueue(user.requireId(), id, Trigger.MANUAL))); }
    @PostMapping("/sources/refresh-all") public ResponseEntity<ApiResult<List<TaskView>>> all() {
        UUID owner = user.requireId(); var tasks = sources.list(owner).stream().map(source -> runner.enqueue(owner, UUID.fromString(source.id()), Trigger.MANUAL)).toList();
        return ResponseEntity.accepted().body(ApiResult.success(tasks));
    }
    @GetMapping("/tasks/{id}") public ApiResult<TaskView> task(@PathVariable UUID id) { return ApiResult.success(runner.get(user.requireId(), id)); }
    @GetMapping("/sources/{id}/history") public ApiResult<List<TaskView>> history(@PathVariable UUID id) { return ApiResult.success(runner.history(user.requireId(), id)); }
    @GetMapping("/empty-decisions") public ApiResult<List<EmptyDecisionView>> empty() { return ApiResult.success(decisions.pending(user.requireId())); }
    @PostMapping("/empty-decisions/{id}/decision") public ApiResult<DecisionResult> decide(@PathVariable UUID id, @RequestBody DecisionCommand command) { return ApiResult.success(decisions.decide(user.requireId(), id, command.choice())); }
}
