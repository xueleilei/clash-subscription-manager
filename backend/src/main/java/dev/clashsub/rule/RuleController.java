package dev.clashsub.rule;

import dev.clashsub.auth.CurrentUser;
import dev.clashsub.common.ApiException;
import dev.clashsub.common.ApiResult;
import dev.clashsub.common.ValidationReport;
import dev.clashsub.node.NodeRepository;
import dev.clashsub.profile.ProfileDocument;
import dev.clashsub.profile.ProfileService;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/profiles/{id}")
public class RuleController {
    public record ImportCommand(Long expectedVersion, String text, MatchPolicy matchPolicy, ProfileDocument document) {
        @Override public String toString() { return "ImportCommand[redacted]"; }
    }
    private final CurrentUser user;
    private final ProfileService profiles;
    private final NodeRepository nodes;
    private final RuleImportService imports;
    public RuleController(CurrentUser user, ProfileService profiles, NodeRepository nodes, RuleImportService imports) {
        this.user = user; this.profiles = profiles; this.nodes = nodes; this.imports = imports;
    }
    @PostMapping("/rule-import/preview")
    @Transactional(readOnly = true)
    public ApiResult<RuleImportPreview> preview(@PathVariable UUID id, @RequestBody ImportCommand command) {
        UUID owner = user.requireId(); var draft = profiles.load(owner, id);
        if (command.expectedVersion() == null || command.expectedVersion() != draft.version()) { throw ApiException.conflict(); }
        if (command.document() != null) {
            profiles.validateDraft(owner, draft.document(), command.document());
            draft = new dev.clashsub.profile.DraftSnapshot(id, draft.version(), command.document());
        }
        return ApiResult.success(imports.preview(draft, command.text(), command.matchPolicy(), nodes.catalog(owner)));
    }
    @PostMapping("/draft/validate")
    @Transactional(readOnly = true)
    public ApiResult<ValidationReport> validate(@PathVariable UUID id, @RequestBody ProfileDocument document) {
        UUID owner = user.requireId(); var draft = profiles.load(owner, id); profiles.validateDraft(owner, draft.document(), document);
        return ApiResult.success(profiles.diagnostics(owner, document));
    }
}
