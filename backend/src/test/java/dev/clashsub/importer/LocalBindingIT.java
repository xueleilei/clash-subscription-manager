package dev.clashsub.importer;

import dev.clashsub.common.ApiException;
import dev.clashsub.profile.TargetRef;
import dev.clashsub.sync.RefreshTestSupport;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.*;

class LocalBindingIT extends RefreshTestSupport {
    @Autowired ConfigImportService imports;
    @Autowired LocalBindingService bindings;
    @Test void bindingChangesDraftOnlyAndLaterAutomaticRefreshStillUsesOldLocalConnection() {
        var remoteSource = source("Remote", yaml("Remote").replace("fixture-first", "fixture-remote")); var remote = node(remoteSource, "Remote");
        var imported = imports.preview(owner, yaml("Local") + "proxy-groups: [{name: Choice, type: select, proxies: [Local]}]\nrules: ['MATCH,Choice']\n");
        var draft = imports.confirm(owner, imported.id(), Map.of()); var local = draft.document().groups().getFirst().members().getFirst().id();
        publisher.publish(owner, draft.profileId(), draft.version(), draft.document()); String old = artifact(draft.profileId());
        var preview = bindings.preview(owner, local, remote.id()); assertThat(preview.affectedProfiles()).contains(draft.profileId());
        bindings.apply(owner, preview.id()); assertThat(artifact(draft.profileId())).isEqualTo(old);
        var changed = profiles.load(owner, draft.profileId()); assertThat(changed.document().groups().getFirst().members()).containsExactly(TargetRef.node(remote.id()));
        assertThat(nodes.connection(owner, local).connection().path("password").asString()).isEqualTo("fixture-first");
        respond(remoteSource, yaml("Remote").replace("fixture-first", "fixture-next")); assertThat(run(remoteSource).status()).isEqualTo("SUCCESS");
        assertThat(artifact(draft.profileId())).isEqualTo(old);
        publisher.publish(owner, draft.profileId(), changed.version(), changed.document()); assertThat(artifact(draft.profileId())).contains("fixture-next");
    }
    @Test void dependentLocalChainsAreCopiedForDraftsInsteadOfMutatingPublishedNodeParameters() {
        var remoteSource = source("Remote", yaml("Remote")); var remote = node(remoteSource, "Remote");
        var imported = imports.preview(owner, yaml("Gateway", "Leaf").replace("name: 'Leaf',", "name: 'Leaf', dialer-proxy: Gateway,")
                + "proxy-groups: [{name: Choice, type: select, proxies: [Leaf]}]\nrules: ['MATCH,Choice']\n");
        var draft = imports.confirm(owner, imported.id(), Map.of()); publisher.publish(owner, draft.profileId(), draft.version(), draft.document());
        var leafId = draft.document().groups().getFirst().members().getFirst().id(); var gateway = nodes.connection(owner, leafId).dialerRef().id(); var old = artifact(draft.profileId());
        bindings.apply(owner, bindings.preview(owner, gateway, remote.id()).id());
        var newLeaf = profiles.load(owner, draft.profileId()).document().groups().getFirst().members().getFirst().id();
        assertThat(newLeaf).isNotEqualTo(leafId); assertThat(nodes.connection(owner, newLeaf).dialerRef()).isEqualTo(TargetRef.node(remote.id()));
        assertThat(nodes.connection(owner, leafId).dialerRef()).isEqualTo(TargetRef.node(gateway)); assertThat(artifact(draft.profileId())).isEqualTo(old);
    }
    @Test void bindingPreviewIsOwnerScopedAndRejectsConcurrentChanges() {
        var source = source("Remote", yaml("Remote")); var remote = node(source, "Remote"); var imported = imports.preview(owner, yaml("Local") + "rules: ['MATCH,Local']\n");
        var draft = imports.confirm(owner, imported.id(), Map.of()); var local = draft.document().rules().getFirst().target().id(); var preview = bindings.preview(owner, local, remote.id());
        assertThatThrownBy(() -> bindings.apply(UUID.randomUUID(), preview.id())).isInstanceOf(ApiException.class).extracting("status").isEqualTo(404);
        profiles.save(owner, draft.profileId(), draft.version(), draft.document());
        assertThatThrownBy(() -> bindings.apply(owner, preview.id())).isInstanceOf(ApiException.class).extracting("status").isEqualTo(409);
        assertThat(profiles.load(owner, draft.profileId()).document().rules().getFirst().target()).isEqualTo(TargetRef.node(local));
    }
}
