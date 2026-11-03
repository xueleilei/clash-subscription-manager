package dev.clashsub.sync;
public record RetentionResult(int attemptsDeleted, int releasesDeleted, int snapshotsDeleted, int tombstoneSecretsCleared) {
    public RetentionResult plus(RetentionResult other) { return new RetentionResult(attemptsDeleted + other.attemptsDeleted, releasesDeleted + other.releasesDeleted, snapshotsDeleted + other.snapshotsDeleted, tombstoneSecretsCleared + other.tombstoneSecretsCleared); }
}
