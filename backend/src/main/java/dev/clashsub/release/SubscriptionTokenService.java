package dev.clashsub.release;

import dev.clashsub.common.ApiException;
import dev.clashsub.common.DefinitionHasher;
import dev.clashsub.common.EncryptedDocuments;
import dev.clashsub.common.OwnerRevisionGuard;
import dev.clashsub.source.SourceRequestValidator;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SubscriptionTokenService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final TokenMapper mapper;
    private final EncryptedDocuments documents;
    private final OwnerRevisionGuard revisions;
    private final ReleaseRepository releases;
    private final String baseUrl;
    public SubscriptionTokenService(TokenMapper mapper, EncryptedDocuments documents, OwnerRevisionGuard revisions, ReleaseRepository releases,
            @Value("${clash.public-base-url:http://localhost:8080}") String baseUrl) {
        this.mapper = mapper; this.documents = documents; this.revisions = revisions; this.releases = releases;
        try { if (SourceRequestValidator.url(baseUrl).getRawQuery() != null) { throw new IllegalArgumentException(); } }
        catch (RuntimeException exception) { throw new IllegalStateException("PUBLIC_BASE_URL must be an HTTP(S) origin or base path without credentials, query or fragment"); }
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public void initialize(UUID owner, UUID profile) {
        String token = newToken();
        if (mapper.initialize(owner.toString(), profile.toString(), DefinitionHasher.sha256(token), documents.write(token, context(owner, profile))) != 1) { throw ApiException.conflict(); }
    }
    @Transactional(readOnly = true)
    public SubscriptionLink link(UUID owner, UUID profile) {
        var row = require(owner, profile);
        if (row.tokenCipher() == null) { throw new ApiException(409, "A0003", "方案尚无订阅令牌，请使用重置链接创建"); }
        return link(profile, documents.read(row.tokenCipher(), context(owner, profile), String.class));
    }
    @Transactional
    public SubscriptionLink rotate(UUID owner, UUID profile) {
        var row = require(owner, profile); String token = newToken();
        revisions.compareAndIncrement(owner, revisions.current(owner));
        if (mapper.rotate(owner.toString(), profile.toString(), row.version(), DefinitionHasher.sha256(token), documents.write(token, context(owner, profile))) != 1) { throw ApiException.conflict(); }
        return link(profile, token);
    }
    @Transactional(readOnly = true)
    public PublishedYaml readPublished(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) { throw ApiException.missing(); }
        var row = mapper.byHash(DefinitionHasher.sha256(token));
        if (row == null) { throw ApiException.missing(); }
        if (row.currentReleaseId() == null) { throw new ApiException(409, "A0003", "此方案尚未成功发布"); }
        var profile = UUID.fromString(row.id()); var owner = UUID.fromString(row.ownerId()); var id = UUID.fromString(row.currentReleaseId());
        var stored = releases.require(owner, profile, id); var metadata = releases.metadata(owner, profile, id);
        return new PublishedYaml(stored.snapshot().yaml(), metadata.yamlDigest(), "profile-" + profile + ".yaml");
    }
    private TokenDO require(UUID owner, UUID profile) {
        var row = mapper.find(owner.toString(), profile.toString()); if (row == null) { throw ApiException.missing(); } return row;
    }
    private SubscriptionLink link(UUID profile, String token) { return new SubscriptionLink(profile, baseUrl + "/sub/" + token, token); }
    private String newToken() { byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private String context(UUID owner, UUID profile) { return "subscription-token:" + owner + ":" + profile; }
}
