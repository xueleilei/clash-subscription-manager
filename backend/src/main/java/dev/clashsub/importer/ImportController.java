package dev.clashsub.importer;
import dev.clashsub.auth.CurrentUser;
import dev.clashsub.common.*;
import dev.clashsub.profile.DraftView;
import dev.clashsub.profile.ProfileService;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1")
public class ImportController {
    public record ImportCommand(String yaml) { @Override public String toString() { return "ImportCommand[redacted]"; } }
    public record ConfirmCommand(Map<UUID, BindingChoice> choices) { }
    public record BindingCommand(UUID remoteNodeId) { }
    private final CurrentUser user;
    private final ConfigImportService imports;
    private final LocalBindingService bindings;
    private final ProfileService profiles;
    public ImportController(CurrentUser user, ConfigImportService imports, LocalBindingService bindings, ProfileService profiles) { this.user = user; this.imports = imports; this.bindings = bindings; this.profiles = profiles; }
    @PostMapping("/imports/preview") public ResponseEntity<ApiResult<ImportPreview>> preview(@RequestBody ImportCommand command) {
        if (command.yaml() == null) { throw ApiException.invalid("请提供 YAML 配置"); } return sensitive(imports.preview(user.requireId(), command.yaml()));
    }
    @PostMapping("/imports/{id}/confirm") public ResponseEntity<ApiResult<DraftView>> confirm(@PathVariable UUID id, @RequestBody ConfirmCommand command) {
        UUID owner = user.requireId(); var draft = imports.confirm(owner, id, command.choices());
        return sensitive(new DraftView(draft.profileId(), Long.toString(draft.version()), draft.document(), profiles.diagnostics(owner, draft.document())));
    }
    @GetMapping("/profiles/{id}/import-batch") public ResponseEntity<ApiResult<OriginalImport>> original(@PathVariable UUID id) { return sensitive(imports.original(user.requireId(), id)); }
    @PostMapping("/nodes/{id}/binding/preview") public ApiResult<BindingPreview> binding(@PathVariable UUID id, @RequestBody BindingCommand command) {
        if (command.remoteNodeId() == null) { throw ApiException.invalid("请选择上游节点"); } return ApiResult.success(bindings.preview(user.requireId(), id, command.remoteNodeId()));
    }
    @PostMapping("/bindings/{id}/apply") public ApiResult<BindingResult> bind(@PathVariable UUID id) { return ApiResult.success(bindings.apply(user.requireId(), id)); }
    private <T> ResponseEntity<ApiResult<T>> sensitive(T value) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResult.success(value)); }
}
