package dev.clashsub.source;

import dev.clashsub.common.ApiException;
import dev.clashsub.common.EncryptedDocuments;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class SourceRepository {
    private final SourceMapper mapper;
    private final EncryptedDocuments documents;
    public SourceRepository(SourceMapper mapper, EncryptedDocuments documents) { this.mapper = mapper; this.documents = documents; }
    public List<SourceDO> list(UUID owner) { return mapper.list(owner.toString()); }
    public SourceDO require(UUID owner, UUID id) {
        var row = mapper.find(owner.toString(), id.toString());
        if (row == null) { throw ApiException.missing(); }
        return row;
    }
    public SourceRequest request(SourceDO row) { return documents.read(row.requestCipher(), context(row.ownerId(), row.id()), SourceRequest.class); }
    public String encrypt(UUID owner, UUID id, SourceRequest request) { return documents.write(request, context(owner.toString(), id.toString())); }
    @Transactional(propagation = Propagation.MANDATORY)
    public void insert(SourceDO row) { mapper.insert(row); }
    @Transactional(propagation = Propagation.MANDATORY)
    public void update(SourceDO row, long expected) {
        if (mapper.updateConfig(row, expected) != 1) { throw ApiException.conflict(); }
        mapper.expireDecisions(row.ownerId(), row.id());
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public void advanceSnapshot(UUID owner, UUID source, long config, long previous, long next, Instant now) {
        if (next != previous + 1 || mapper.advanceSnapshot(owner.toString(), source.toString(), config, previous, next, now) != 1) { throw ApiException.conflict(); }
        mapper.expireDecisions(owner.toString(), source.toString());
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public void unchangedSuccess(UUID owner, UUID source, long config, long snapshot, Instant now) {
        if (mapper.unchangedSuccess(owner.toString(), source.toString(), config, snapshot, now) != 1) { throw ApiException.conflict(); }
        mapper.expireDecisions(owner.toString(), source.toString());
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public void attemptStatus(UUID owner, UUID source, long config, long snapshot, String status, String error, Instant now) {
        mapper.attemptStatus(owner.toString(), source.toString(), config, snapshot, status, error, now);
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public void saveSnapshot(UUID owner, UUID source, long version, StoredSourceSnapshot payload, Instant now) {
        require(owner, source);
        UUID id = UUID.randomUUID();
        mapper.insertSnapshot(new SourceSnapshotDO(id.toString(), owner.toString(), source.toString(), version,
                documents.write(payload, "source-snapshot:" + owner + ":" + id), now));
    }
    public StoredSourceSnapshot snapshot(UUID owner, UUID source, long version) {
        var row = mapper.snapshot(owner.toString(), source.toString(), version);
        if (row == null) { throw ApiException.missing(); }
        return documents.read(row.payloadCipher(), "source-snapshot:" + owner + ":" + row.id(), StoredSourceSnapshot.class);
    }
    private String context(String owner, String source) { return "source:" + owner + ":" + source; }
}
