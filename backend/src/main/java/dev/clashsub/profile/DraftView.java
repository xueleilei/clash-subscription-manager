package dev.clashsub.profile;

import dev.clashsub.common.ValidationReport;
import java.util.UUID;

public record DraftView(UUID profileId, String version, ProfileDocument document, ValidationReport diagnostics) {
    public static DraftView of(DraftSnapshot draft, ValidationReport diagnostics) {
        return new DraftView(draft.profileId(), Long.toString(draft.version()), draft.document(), diagnostics);
    }
    @Override public String toString() { return "DraftView[profileId=" + profileId + ", version=" + version + "]"; }
}
