package dev.clashsub.release;

import java.util.UUID;

public record SubscriptionLink(UUID profileId, String url, String token) {
    @Override public String toString() { return "SubscriptionLink[profileId=" + profileId + "]"; }
}
