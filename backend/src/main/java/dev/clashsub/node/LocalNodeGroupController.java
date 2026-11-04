package dev.clashsub.node;

import dev.clashsub.auth.CurrentUser;
import dev.clashsub.common.ApiResult;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/local-node-groups")
public class LocalNodeGroupController {
    public record NameCommand(String name, Long expectedVersion) { }
    public record Members(List<UUID> ids) { }
    private final CurrentUser user;
    private final LocalNodeGroupService groups;
    public LocalNodeGroupController(CurrentUser user, LocalNodeGroupService groups) { this.user = user; this.groups = groups; }
    @GetMapping public ApiResult<LocalNodeGroupService.Directory> list() { return ApiResult.success(groups.list(user.requireId())); }
    @PostMapping public ApiResult<LocalNodeGroupService.Saved> create(@RequestBody NameCommand command) {
        return ApiResult.success(groups.create(user.requireId(), command.name()));
    }
    @PatchMapping("/{id}") public ApiResult<LocalNodeGroupService.Saved> rename(@PathVariable UUID id, @RequestBody NameCommand command) {
        return ApiResult.success(groups.rename(user.requireId(), id, command.expectedVersion(), command.name()));
    }
    @DeleteMapping("/{id}") public ApiResult<Void> delete(@PathVariable UUID id, @RequestParam Long expectedVersion) {
        groups.delete(user.requireId(), id, expectedVersion); return ApiResult.success(null);
    }
    @PutMapping("/{id}/members") public ApiResult<Void> move(@PathVariable UUID id, @RequestBody Members command) {
        groups.move(user.requireId(), id, command.ids()); return ApiResult.success(null);
    }
    @PutMapping("/ungrouped/members") public ApiResult<Void> ungroup(@RequestBody Members command) {
        groups.move(user.requireId(), null, command.ids()); return ApiResult.success(null);
    }
}
