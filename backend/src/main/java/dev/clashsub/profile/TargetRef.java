package dev.clashsub.profile;

import java.util.UUID;

public record TargetRef(Kind kind, UUID id, String unresolvedName) {
    public enum Kind { NODE, GROUP, DIRECT, REJECT, UNRESOLVED }
    public TargetRef {
        if (kind == null || ((kind == Kind.NODE || kind == Kind.GROUP) != (id != null))
                || ((kind == Kind.UNRESOLVED) != (unresolvedName != null))) {
            throw new IllegalArgumentException("Invalid target reference");
        }
    }
    public static TargetRef node(UUID id) { return new TargetRef(Kind.NODE, id, null); }
    public static TargetRef group(UUID id) { return new TargetRef(Kind.GROUP, id, null); }
    public static TargetRef direct() { return new TargetRef(Kind.DIRECT, null, null); }
    public static TargetRef reject() { return new TargetRef(Kind.REJECT, null, null); }
    public static TargetRef unresolved(String name) { return new TargetRef(Kind.UNRESOLVED, null, name); }
}
