package dev.clashsub.node;

import java.util.UUID;

public record NodeKey(UUID sourceId, String nameHash, String exactName) {
    public boolean matches(UUID source, String name) { return sourceId.equals(source) && exactName.equals(name); }
}
