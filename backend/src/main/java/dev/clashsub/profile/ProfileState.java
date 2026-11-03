package dev.clashsub.profile;

import dev.clashsub.release.ReleaseSnapshot;
import java.util.UUID;

public record ProfileState(UUID profileId, long profileVersion, DraftSnapshot draft, ReleaseSnapshot published) { }
