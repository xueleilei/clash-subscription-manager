package dev.clashsub.common;

import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OwnerRevisionGuard {
    private final OwnerRevisionMapper mapper;
    public OwnerRevisionGuard(OwnerRevisionMapper mapper) { this.mapper = mapper; }

    @Transactional(propagation = Propagation.MANDATORY)
    public void compareAndIncrement(UUID ownerId, long expected) {
        if (mapper.increment(ownerId.toString(), expected) != 1) { throw ApiException.conflict(); }
    }

    public long current(UUID ownerId) {
        var value = mapper.revision(ownerId.toString());
        if (value == null) { throw ApiException.missing(); }
        return value;
    }
}
