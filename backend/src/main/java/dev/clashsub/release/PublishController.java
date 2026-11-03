package dev.clashsub.release;

import dev.clashsub.auth.CurrentUser;
import dev.clashsub.common.ApiException;
import dev.clashsub.common.ApiResult;
import dev.clashsub.profile.ProfileDocument;
import dev.clashsub.profile.ProfileRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/profiles/{id}")
public class PublishController {
    public record PublishCommand(Long expectedVersion, ProfileDocument document) {
        @Override public String toString() { return "PublishCommand[expectedVersion=" + expectedVersion + "]"; }
    }
    private final CurrentUser user;
    private final ReleaseService service;
    private final ReleaseRepository releases;
    private final ProfileRepository profiles;
    private final SubscriptionTokenService tokens;
    public PublishController(CurrentUser user, ReleaseService service, ReleaseRepository releases, ProfileRepository profiles, SubscriptionTokenService tokens) {
        this.user = user; this.service = service; this.releases = releases; this.profiles = profiles; this.tokens = tokens;
    }
    @PostMapping("/validate") public ResponseEntity<ApiResult<PublishPreview>> validate(@PathVariable UUID id, @RequestBody PublishCommand command) {
        return sensitive(service.preview(user.requireId(), id, version(command.expectedVersion()), command.document()));
    }
    @PostMapping("/publish") public ApiResult<ReleaseView> publish(@PathVariable UUID id, @RequestBody PublishCommand command) {
        return ApiResult.success(service.publish(user.requireId(), id, version(command.expectedVersion()), command.document()));
    }
    @GetMapping("/releases") public ApiResult<List<ReleaseView>> history(@PathVariable UUID id) {
        UUID owner = user.requireId(); profiles.require(owner, id); return ApiResult.success(releases.list(owner, id));
    }
    @GetMapping("/subscription-link") public ResponseEntity<ApiResult<SubscriptionLink>> link(@PathVariable UUID id) { return sensitive(tokens.link(user.requireId(), id)); }
    @PostMapping("/token/rotate") public ResponseEntity<ApiResult<SubscriptionLink>> rotate(@PathVariable UUID id) { return sensitive(tokens.rotate(user.requireId(), id)); }
    private <T> ResponseEntity<ApiResult<T>> sensitive(T value) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResult.success(value)); }
    private long version(Long version) { if (version == null || version < 1) { throw ApiException.invalid("请提供当前草稿版本"); } return version; }
}
