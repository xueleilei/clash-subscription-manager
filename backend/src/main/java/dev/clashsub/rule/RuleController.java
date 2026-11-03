package dev.clashsub.rule;

import dev.clashsub.auth.CurrentUser;
import dev.clashsub.common.ApiException;
import dev.clashsub.common.ApiResult;
import dev.clashsub.common.ValidationReport;
import dev.clashsub.common.OwnerRevisionGuard;
import dev.clashsub.node.NodeRepository;
import dev.clashsub.profile.ProfileDocument;
import dev.clashsub.profile.ProfileService;
import dev.clashsub.profile.DraftSnapshot;
import dev.clashsub.source.SourceRepository;
import dev.clashsub.source.SourceState;
import java.util.LinkedHashMap;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/profiles/{id}")
public class RuleController {
    public record ImportCommand(Long expectedVersion, String text, MatchPolicy matchPolicy, ProfileDocument document) {
        @Override public String toString() { return "ImportCommand[redacted]"; }
    }
    public record TextCommand(Long expectedVersion, String text, ProfileDocument document, Long ownerRevision) {
        @Override public String toString() { return "TextCommand[redacted]"; }
    }
    private final CurrentUser user;
    private final ProfileService profiles;
    private final NodeRepository nodes;
    private final RuleImportService imports;
    private final RuleTextService text;
    private final SourceRepository sources;
    private final OwnerRevisionGuard revisions;
    public RuleController(CurrentUser user, ProfileService profiles, NodeRepository nodes, RuleImportService imports,
            RuleTextService text, SourceRepository sources, OwnerRevisionGuard revisions) {
        this.user = user; this.profiles = profiles; this.nodes = nodes; this.imports = imports;
        this.text = text; this.sources = sources; this.revisions = revisions;
    }
    @PostMapping("/rule-import/preview")
    @Transactional(readOnly = true)
    public ApiResult<RuleImportPreview> preview(@PathVariable UUID id, @RequestBody ImportCommand command) {
        UUID owner = user.requireId(); var draft = draft(owner, id, command.expectedVersion(), command.document());
        return ApiResult.success(imports.preview(draft, command.text(), command.matchPolicy(), nodes.catalog(owner)));
    }
    @PostMapping("/rule-text")
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ResponseEntity<ApiResult<RuleTextDocument>> text(@PathVariable UUID id, @RequestBody TextCommand command) {
        UUID owner = user.requireId(); var draft = draft(owner, id, command.expectedVersion(), command.document());
        return sensitive(text.text(draft, nodes.catalog(owner), sourceStates(owner), revisions.current(owner)));
    }
    @PostMapping("/rule-text/preview")
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ResponseEntity<ApiResult<RuleImportPreview>> textPreview(@PathVariable UUID id, @RequestBody TextCommand command) {
        UUID owner = user.requireId(); var draft = draft(owner, id, command.expectedVersion(), command.document());
        if (command.ownerRevision() == null || command.ownerRevision() != revisions.current(owner)) { throw ApiException.conflict(); }
        return sensitive(text.preview(draft, command.text(), nodes.catalog(owner), sourceStates(owner)));
    }
    @PostMapping("/draft/validate")
    @Transactional(readOnly = true)
    public ApiResult<ValidationReport> validate(@PathVariable UUID id, @RequestBody ProfileDocument document) {
        UUID owner = user.requireId(); var draft = profiles.load(owner, id); profiles.validateDraft(owner, draft.document(), document);
        return ApiResult.success(profiles.diagnostics(owner, document));
    }
    private DraftSnapshot draft(UUID owner, UUID id, Long expectedVersion, ProfileDocument document) {
        var draft = profiles.load(owner, id);
        if (expectedVersion == null || expectedVersion != draft.version()) { throw ApiException.conflict(); }
        if (document == null) { return draft; }
        profiles.validateDraft(owner, draft.document(), document);
        return new DraftSnapshot(id, draft.version(), document);
    }
    private java.util.Map<UUID, SourceState> sourceStates(UUID owner) {
        var states = new LinkedHashMap<UUID, SourceState>();
        sources.list(owner).forEach(row -> {
            var id = UUID.fromString(row.id()); states.put(id, new SourceState(id, row.configVersion(), row.snapshotVersion(), row.name()));
        });
        return states;
    }
    private <T> ResponseEntity<ApiResult<T>> sensitive(T value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResult.success(value));
    }
}
