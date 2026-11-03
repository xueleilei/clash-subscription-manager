package dev.clashsub.release;

import dev.clashsub.common.ValidationReport;
import dev.clashsub.compile.CompiledProfile;
import dev.clashsub.profile.ProfileDocument;
import dev.clashsub.node.NodeCatalog;

public record PreparedRelease(ProfileDocument definition, CompiledProfile compiled, ValidationReport validation, NodeCatalog nodes) {
    public boolean valid() { return compiled != null && validation.valid(); }
    @Override public String toString() { return "PreparedRelease[valid=" + valid() + "]"; }
}
