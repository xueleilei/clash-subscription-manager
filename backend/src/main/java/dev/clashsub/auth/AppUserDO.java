package dev.clashsub.auth;

/** Persistence record. Never return this type from an API. */
public record AppUserDO(String id, String username, String passwordHash, long dataRevision) {
    @Override public String toString() { return "AppUserDO[id=" + id + "]"; }
}
