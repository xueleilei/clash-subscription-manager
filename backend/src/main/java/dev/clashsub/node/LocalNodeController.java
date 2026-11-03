package dev.clashsub.node;

import dev.clashsub.auth.CurrentUser;
import dev.clashsub.common.ApiResult;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/local-nodes")
public class LocalNodeController {
    public record ParseCommand(String text) { @Override public String toString() { return "ParseCommand[redacted]"; } }
    public record BatchCommand(String text, Long ownerRevision) { @Override public String toString() { return "BatchCommand[redacted]"; } }
    public record DeleteCommand(Long expectedVersion, Long ownerRevision) { }
    private final CurrentUser user;
    private final LocalNodeService service;
    public LocalNodeController(CurrentUser user, LocalNodeService service) { this.user = user; this.service = service; }
    @PostMapping("/parse") public ResponseEntity<ApiResult<LocalNodeCommand>> parse(@RequestBody ParseCommand command) { return sensitive(service.parse(user.requireId(), command.text())); }
    @PostMapping("/batch/preview") public ResponseEntity<ApiResult<LocalNodeBatchPreview>> previewBatch(@RequestBody ParseCommand command) { return sensitive(service.previewBatch(user.requireId(), command.text())); }
    @PostMapping("/batch") public ApiResult<LocalNodeBatchResult> createBatch(@RequestBody BatchCommand command) { return ApiResult.success(service.saveBatch(user.requireId(), command.text(), command.ownerRevision())); }
    @PostMapping public ApiResult<LocalNodeResult> create(@RequestBody LocalNodeCommand command) { return ApiResult.success(service.save(user.requireId(), null, command)); }
    @GetMapping("/{id}") public ResponseEntity<ApiResult<LocalNodeView>> get(@PathVariable UUID id) { return sensitive(service.view(user.requireId(), id)); }
    @PutMapping("/{id}") public ApiResult<LocalNodeResult> update(@PathVariable UUID id, @RequestBody LocalNodeCommand command) { return ApiResult.success(service.save(user.requireId(), id, command)); }
    @GetMapping("/{id}/removal") public ApiResult<LocalNodeRemoval> removal(@PathVariable UUID id) { return ApiResult.success(service.removal(user.requireId(), id)); }
    @DeleteMapping("/{id}") public ApiResult<LocalNodeResult> delete(@PathVariable UUID id, @RequestBody DeleteCommand command) { return ApiResult.success(service.delete(user.requireId(), id, command.expectedVersion(), command.ownerRevision())); }
    private <T> ResponseEntity<ApiResult<T>> sensitive(T value) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResult.success(value)); }
}
