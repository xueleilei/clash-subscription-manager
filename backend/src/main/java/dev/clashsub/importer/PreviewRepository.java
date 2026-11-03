package dev.clashsub.importer;

import dev.clashsub.common.*;
import dev.clashsub.profile.ProfileRepository;
import java.time.*;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class PreviewRepository {
    public record Stored<T>(PreviewDO metadata, T payload) { }
    private final PreviewMapper mapper;
    private final EncryptedDocuments documents;
    private final Clock clock;
    private final ProfileRepository profiles;
    public PreviewRepository(PreviewMapper mapper, EncryptedDocuments documents, Clock clock, ProfileRepository profiles) { this.mapper = mapper; this.documents = documents; this.clock = clock; this.profiles = profiles; }
    @Transactional public PreviewDO create(UUID owner, String kind, long revision, Object payload) {
        UUID id = UUID.randomUUID(); Instant now = clock.instant();
        var row = new PreviewDO(id.toString(), owner.toString(), kind, revision, documents.write(payload, context(owner, id)), now, now.plus(Duration.ofMinutes(30)), false);
        mapper.insert(row); return row;
    }
    public <T> Stored<T> require(UUID owner, UUID id, String kind, Class<T> type) {
        var row = mapper.find(owner.toString(), id.toString(), kind); if (row == null) { throw ApiException.missing(); }
        if (row.consumed() || !row.expiresAt().isAfter(clock.instant())) { throw ApiException.conflict(); }
        return new Stored<>(row, documents.read(row.payloadCipher(), context(owner, id), type));
    }
    @Transactional(propagation = Propagation.MANDATORY) public void consume(UUID owner, UUID id) {
        if (mapper.consume(owner.toString(), id.toString(), clock.instant()) != 1) { throw ApiException.conflict(); }
    }
    @Transactional(propagation = Propagation.MANDATORY) public void saveOriginal(UUID owner, UUID profile, String yaml) {
        UUID id = UUID.randomUUID(); var original = new OriginalImport(id, profile, yaml, clock.instant());
        mapper.batch(new ImportBatchDO(id.toString(), owner.toString(), profile.toString(), documents.write(original, "import-batch:" + owner + ":" + id), original.createdAt()));
    }
    public OriginalImport original(UUID owner, UUID profile) {
        profiles.require(owner, profile); var row = mapper.original(owner.toString(), profile.toString()); if (row == null) { throw ApiException.missing(); }
        return documents.read(row.payloadCipher(), "import-batch:" + owner + ":" + row.id(), OriginalImport.class);
    }
    private String context(UUID owner, UUID id) { return "import-preview:" + owner + ":" + id; }
}
