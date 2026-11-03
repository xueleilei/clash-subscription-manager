package dev.clashsub.profile;

import dev.clashsub.auth.CurrentUser;
import dev.clashsub.common.ApiException;
import dev.clashsub.common.ApiResult;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/profiles")
public class ProfileController {
    public record CreateCommand(String name) { }
    public record RenameCommand(Long expectedVersion, String name) { }
    public record SaveCommand(Long expectedVersion, ProfileDocument document) {
        @Override public String toString() { return "SaveCommand[expectedVersion=" + expectedVersion + "]"; }
    }
    private final ProfileService profiles;
    private final CurrentUser user;
    public ProfileController(ProfileService profiles, CurrentUser user) { this.profiles = profiles; this.user = user; }
    @GetMapping public ApiResult<List<ProfileView>> list() { return ApiResult.success(profiles.list(user.requireId())); }
    @PostMapping public ApiResult<DraftView> create(@RequestBody CreateCommand command) { return response(profiles.create(user.requireId(), command.name())); }
    @GetMapping("/{id}/draft") public ApiResult<DraftView> load(@PathVariable UUID id) { return response(profiles.load(user.requireId(), id)); }
    @PutMapping("/{id}/draft") public ApiResult<DraftView> save(@PathVariable UUID id, @RequestBody SaveCommand command) {
        return response(profiles.save(user.requireId(), id, version(command.expectedVersion()), command.document()));
    }
    @PatchMapping("/{id}") public ApiResult<ProfileView> rename(@PathVariable UUID id, @RequestBody RenameCommand command) {
        return ApiResult.success(profiles.rename(user.requireId(), id, version(command.expectedVersion()), command.name()));
    }
    @GetMapping("/{id}/deletion-impact") public ApiResult<ProfileDeletionImpact> impact(@PathVariable UUID id) { return ApiResult.success(profiles.deletionImpact(user.requireId(), id)); }
    @DeleteMapping("/{id}") public ApiResult<Void> delete(@PathVariable UUID id, @RequestParam long expectedVersion) {
        profiles.delete(user.requireId(), id, expectedVersion); return ApiResult.success(null);
    }
    private ApiResult<DraftView> response(DraftSnapshot draft) { return ApiResult.success(DraftView.of(draft, profiles.diagnostics(user.requireId(), draft.document()))); }
    private long version(Long value) { if (value == null || value < 1) { throw ApiException.invalid("请提交读取草稿时的版本"); } return value; }
}
