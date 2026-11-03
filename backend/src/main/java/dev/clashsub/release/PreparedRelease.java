package dev.clashsub.release;

import dev.clashsub.common.ValidationReport;
import dev.clashsub.compile.CompiledProfile;
import dev.clashsub.profile.ProfileDocument;

public record PreparedRelease(ProfileDocument definition, CompiledProfile compiled, ValidationReport validation) {
    public boolean valid() { return compiled != null && validation.valid(); }
    @Override public String toString() { return "PreparedRelease[valid=" + valid() + "]"; }
}
