package dev.clashsub.profile;

import dev.clashsub.common.ApiException;
import dev.clashsub.common.OwnerRevisionGuard;
import dev.clashsub.common.ValidationReport;
import dev.clashsub.node.NodeRepository;
import dev.clashsub.parse.SafeYaml;
import dev.clashsub.parse.YamlInputException;
import dev.clashsub.rule.RuleDefinition;
import dev.clashsub.rule.RuleExpression;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProfileService {
    private final ProfileRepository repository;
    private final NodeRepository nodes;
    private final OwnerRevisionGuard revisions;
    private final ReferenceValidator references;
    private final SafeYaml yaml;
    public ProfileService(ProfileRepository repository, NodeRepository nodes, OwnerRevisionGuard revisions, ReferenceValidator references, SafeYaml yaml) {
        this.repository = repository; this.nodes = nodes; this.revisions = revisions; this.references = references; this.yaml = yaml;
    }

    @Transactional public DraftSnapshot create(UUID owner, String name) {
        validateName(name);
        var document = new ProfileDocument(1, "mode: rule\n", List.of(), List.of(new RuleDefinition(UUID.randomUUID(),
                new RuleExpression("MATCH", "", null), TargetRef.reject(), List.of(), "", List.of())), List.of(), List.of());
        revisions.compareAndIncrement(owner, revisions.current(owner));
        return repository.insert(owner, UUID.randomUUID(), name, document);
    }

    @Transactional(readOnly = true) public DraftSnapshot load(UUID owner, UUID id) { return repository.draft(owner, id); }
    @Transactional(readOnly = true) public List<ProfileView> list(UUID owner) {
        return repository.list(owner).stream().map(row -> view(row, repository.draft(owner, UUID.fromString(row.id())))).toList();
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
        if (document == null || document.schemaVersion() != 1 || document.baseYaml() == null || document.baseYaml().length() > SafeYaml.MAX_BYTES
                || document.groups().size() > 1000 || document.rules().size() > 10_000 || document.providers().size() > 1000) {
            throw ApiException.invalid("草稿结构或大小不受支持");
        }
        var catalog = nodes.catalog(owner); var groupIds = new HashSet<UUID>(); var oldGroups = new HashSet<UUID>();
        document.groups().forEach(group -> groupIds.add(group.id())); previous.groups().forEach(group -> oldGroups.add(group.id()));
        var targets = Stream.concat(document.groups().stream().flatMap(group -> group.members().stream()), document.rules().stream().map(RuleDefinition::target)).toList();
        for (var target : targets) {
            if (target == null) { continue; }
            if (target.kind() == TargetRef.Kind.NODE && catalog.get(target.id()) == null) { throw ApiException.missing(); }
            if (target.kind() == TargetRef.Kind.GROUP && !groupIds.contains(target.id())) {
                if (oldGroups.contains(target.id())) { throw ApiException.invalid("删除代理组前，请替换或解除它在其他组和规则中的全部引用"); }
                throw ApiException.missing();
            }
        }
        var providerIds = new HashSet<UUID>(); var oldProviders = new HashSet<UUID>();
        document.providers().forEach(provider -> providerIds.add(provider.id())); previous.providers().forEach(provider -> oldProviders.add(provider.id()));
        for (var rule : document.rules()) {
            var provider = rule.expression() == null ? null : rule.expression().providerId();
            if (provider != null && !providerIds.contains(provider)) {
                if (oldProviders.contains(provider)) { throw ApiException.invalid("删除规则集前，请替换或解除它在规则中的全部引用"); }
                throw ApiException.missing();
            }
        }
        var report = references.validate(document, catalog);
        var structural = Set.of("DUPLICATE_GROUP_ID", "DUPLICATE_RULE_ID", "DUPLICATE_PROVIDER_ID", "DUPLICATE_MEMBER");
        if (report.errors().stream().anyMatch(issue -> structural.contains(issue.code()))) { throw ApiException.invalid("组、规则或规则集存在缺失或重复的 ID，或重复成员"); }
    }

    @Transactional(readOnly = true) public ValidationReport diagnostics(UUID owner, UUID id) { return diagnostics(owner, repository.draft(owner, id).document()); }
    public ValidationReport diagnostics(UUID owner, ProfileDocument document) {
        var report = references.validate(document, nodes.catalog(owner));
        try { yaml.load(document.baseYaml()); }
        catch (YamlInputException exception) { report = report.merge(ValidationReport.of(List.of(exception.diagnostic()))); }
        return report.merge(ValidationReport.of(document.importIssues()));
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
        return new ProfileView(UUID.fromString(row.id()), row.name(), Long.toString(row.version()), Long.toString(draft.version()),
                row.currentReleaseId() == null ? null : UUID.fromString(row.currentReleaseId()), row.createTime(), row.updateTime());
    }
}
