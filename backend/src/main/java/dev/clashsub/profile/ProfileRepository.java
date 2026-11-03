package dev.clashsub.profile;

import dev.clashsub.common.ApiException;
import dev.clashsub.common.EncryptedDocuments;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ProfileRepository {
    private final ProfileMapper mapper;
    private final EncryptedDocuments documents;
    public ProfileRepository(ProfileMapper mapper, EncryptedDocuments documents) { this.mapper = mapper; this.documents = documents; }
    public List<ProfileDO> list(UUID owner) { return mapper.list(owner.toString()); }
    public ProfileDO require(UUID owner, UUID id) {
        var row = mapper.find(owner.toString(), id.toString());
        if (row == null) { throw ApiException.missing(); }
        return row;
    }
    public DraftSnapshot draft(UUID owner, UUID id) {
        require(owner, id);
        var row = mapper.draft(owner.toString(), id.toString());
        if (row == null) { throw ApiException.missing(); }
        return new DraftSnapshot(id, row.version(), documents.read(row.documentCipher(), context(owner, id), ProfileDocument.class));
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public DraftSnapshot insert(UUID owner, UUID id, String name, ProfileDocument document) {
        mapper.insert(new ProfileDO(id.toString(), owner.toString(), name, 1, null, null, null));
        mapper.insertDraft(new ProfileDraftDO(UUID.randomUUID().toString(), owner.toString(), id.toString(), 1, documents.write(document, context(owner, id))));
        return new DraftSnapshot(id, 1, document);
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public DraftSnapshot updateDraft(UUID owner, UUID id, long expected, ProfileDocument document) {
        if (mapper.updateDraft(owner.toString(), id.toString(), expected, documents.write(document, context(owner, id))) != 1) { throw ApiException.conflict(); }
        return new DraftSnapshot(id, expected + 1, document);
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public void touch(UUID owner, UUID id, long expected) {
        if (mapper.touch(owner.toString(), id.toString(), expected) != 1) { throw ApiException.conflict(); }
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public void rename(UUID owner, UUID id, long expected, String name) {
        if (mapper.rename(owner.toString(), id.toString(), expected, name) != 1) { throw ApiException.conflict(); }
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public void delete(UUID owner, UUID id, long expected) {
        if (mapper.delete(owner.toString(), id.toString(), expected) != 1) { throw ApiException.conflict(); }
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public void pointRelease(UUID owner, UUID id, long expected, UUID release) {
        if (mapper.pointRelease(owner.toString(), id.toString(), expected, release.toString()) != 1) { throw ApiException.conflict(); }
    }
    private String context(UUID owner, UUID id) { return "profile-draft:" + owner + ":" + id; }
}
