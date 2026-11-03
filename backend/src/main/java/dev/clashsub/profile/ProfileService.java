package dev.clashsub.profile;

import dev.clashsub.common.ApiException;
import dev.clashsub.common.DefinitionHasher;
import dev.clashsub.common.OwnerRevisionGuard;
import dev.clashsub.common.ValidationReport;
import dev.clashsub.node.NodeRepository;
import dev.clashsub.release.ReleaseRepository;
import dev.clashsub.release.SubscriptionTokenService;
import dev.clashsub.rule.BaseSettingsValidator;
import dev.clashsub.rule.ProviderValidator;
import dev.clashsub.rule.RuleValidator;
import dev.clashsub.rule.RuleDefinition;
import dev.clashsub.rule.RuleExpression;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProfileService {
    private final ProfileRepository repository;
    private final NodeRepository nodes;
    private final OwnerRevisionGuard revisions;
    private final ReferenceValidator references;
    private final BaseSettingsValidator baseSettings;
    private final ProviderValidator providers;
    private final RuleValidator rules;
    private final DraftGuard guard;
    private final DefinitionHasher hashes;
    private final SubscriptionTokenService tokens;
    private final ReleaseRepository releases;
    public ProfileService(ProfileRepository repository, NodeRepository nodes, OwnerRevisionGuard revisions, ReferenceValidator references,
            BaseSettingsValidator baseSettings, ProviderValidator providers, RuleValidator rules, DraftGuard guard, DefinitionHasher hashes,
            SubscriptionTokenService tokens, ReleaseRepository releases) {
        this.repository = repository; this.nodes = nodes; this.revisions = revisions; this.references = references;
        this.baseSettings = baseSettings; this.providers = providers; this.rules = rules;
        this.guard = guard; this.hashes = hashes; this.tokens = tokens; this.releases = releases;
    }

    @Transactional public DraftSnapshot create(UUID owner, String name) {
        validateName(name);
        var document = new ProfileDocument(1, "mode: rule\n", List.of(), List.of(new RuleDefinition(UUID.randomUUID(),
                new RuleExpression("MATCH", "", null), TargetRef.reject(), List.of(), "", List.of())), List.of(), List.of());
        revisions.compareAndIncrement(owner, revisions.current(owner));
        UUID id = UUID.randomUUID(); var draft = repository.insert(owner, id, name, document); tokens.initialize(owner, id); return draft;
    }

    @Transactional(readOnly = true) public DraftSnapshot load(UUID owner, UUID id) { return repository.draft(owner, id); }
    @Transactional(readOnly = true) public List<ProfileView> list(UUID owner) {
        var catalog = nodes.catalog(owner);
        return repository.list(owner).stream().map(row -> view(row, repository.draft(owner, UUID.fromString(row.id())), catalog)).toList();
    }
    @Transactional public DraftSnapshot save(UUID owner, UUID id, long expectedVersion, ProfileDocument document) {
        var profile = repository.require(owner, id); var previous = repository.draft(owner, id);
        if (previous.version() != expectedVersion) { throw ApiException.conflict(); }
        validateDraft(owner, previous.document(), document);
        revisions.compareAndIncrement(owner, revisions.current(owner));
        repository.touch(owner, id, profile.version());
        return repository.updateDraft(owner, id, expectedVersion, document);
    }

    /** An unfinished document is allowed, but ownership and stable reference structure are not optional. */
    public void validateDraft(UUID owner, ProfileDocument previous, ProfileDocument document) {
        guard.validate(previous, document, nodes.catalog(owner));
    }

    @Transactional(readOnly = true) public ValidationReport diagnostics(UUID owner, UUID id) { return diagnostics(owner, repository.draft(owner, id).document()); }
    public ValidationReport diagnostics(UUID owner, ProfileDocument document) {
        var catalog = nodes.catalog(owner);
        return references.validate(document, catalog).merge(baseSettings.validate(document.baseYaml(), ReferenceIndex.of(document, catalog)))
                .merge(providers.validate(document.providers())).merge(rules.validate(document.rules())).merge(ValidationReport.of(document.importIssues()));
    }

    @Transactional public ProfileView rename(UUID owner, UUID id, long expected, String name) {
        validateName(name); var profile = repository.require(owner, id);
        if (profile.version() != expected) { throw ApiException.conflict(); }
        revisions.compareAndIncrement(owner, revisions.current(owner)); repository.rename(owner, id, expected, name);
        return view(repository.require(owner, id), repository.draft(owner, id));
    }
    @Transactional(readOnly = true) public ProfileDeletionImpact deletionImpact(UUID owner, UUID id) {
        var profile = repository.require(owner, id); var draft = repository.draft(owner, id);
        return new ProfileDeletionImpact(id, Long.toString(profile.version()), Long.toString(draft.version()), draft.document().groups().size(),
                draft.document().rules().size(), profile.currentReleaseId() != null, "删除此方案将立即撤销其订阅访问；共享来源和其他方案保留");
    }
    @Transactional public void delete(UUID owner, UUID id, long expected) {
        var profile = repository.require(owner, id);
        if (profile.version() != expected) { throw ApiException.conflict(); }
        revisions.compareAndIncrement(owner, revisions.current(owner)); repository.delete(owner, id, expected);
    }
    private void validateName(String name) { if (name == null || name.isBlank() || name.length() > 128) { throw ApiException.invalid("方案名称必须为 1–128 个字符"); } }
    private ProfileView view(ProfileDO row, DraftSnapshot draft) {
        return view(row, draft, nodes.catalog(UUID.fromString(row.ownerId())));
    }
    private ProfileView view(ProfileDO row, DraftSnapshot draft, dev.clashsub.node.NodeCatalog catalog) {
        var stored = row.currentReleaseId() == null ? null : releases.require(UUID.fromString(row.ownerId()), UUID.fromString(row.id()), UUID.fromString(row.currentReleaseId()));
        var published = stored == null ? null : stored.snapshot();
        boolean changedNodes = stored != null && stored.nodes().nodes().values().stream()
                .anyMatch(node -> node.sourceId() == null && !node.equals(catalog.get(node.id())));
        return new ProfileView(UUID.fromString(row.id()), row.name(), Long.toString(row.version()), Long.toString(draft.version()),
                row.currentReleaseId() == null ? null : UUID.fromString(row.currentReleaseId()), published == null ? null : Long.toString(published.version()),
                hashes.hash(draft.document()), published == null ? null : hashes.hash(published.definition()), changedNodes, row.createTime(), row.updateTime());
    }
}
