package dev.clashsub.sync;

import dev.clashsub.parse.SafeYaml;
import dev.clashsub.source.SourceCommand;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SourceOutputNamesIT extends RefreshTestSupport {
    private static final SafeYaml YAML = new SafeYaml();

    @Test void publishedNodesAndTheirReferencesIdentifyTheirSubscription() {
        var first = source("机场甲", yaml("中国台湾·2"));
        var second = source("机场乙", yaml("中国台湾·2"));
        var firstNode = node(first, "中国台湾·2").id();
        var secondNode = node(second, "中国台湾·2").id();
        var profile = profile("选择节点", List.of(firstNode, secondNode), firstNode);

        var output = YAML.load(artifact(profile)).value();
        assertThat(output.path("proxies").valueStream().map(value -> value.path("name").asString()).toList())
                .containsExactlyInAnyOrder("中国台湾·2 · 机场甲", "中国台湾·2 · 机场乙");
        assertThat(output.path("proxy-groups").path(0).path("proxies").valueStream().map(value -> value.asString()).toList())
                .containsExactly("中国台湾·2 · 机场甲", "中国台湾·2 · 机场乙");
        assertThat(output.path("rules").path(0).asString())
                .isEqualTo("IP-CIDR,192.0.2.0/24,中国台湾·2 · 机场甲,no-resolve");
    }

    @Test void renamingSubscriptionChangesNextPublicationWithoutChangingNodeIdentityOrOldRelease() {
        var source = source("旧名称", yaml("香港"));
        var node = node(source, "香港").id();
        var profile = profile("选择节点", List.of(node), node);
        String oldYaml = artifact(profile);
        var oldDefinition = published(profile);
        sources.save(owner, new SourceCommand(source, sourceRepository.require(owner, source).configVersion(),
                "我的机场", null, null, null, null, null, null, null));

        assertThat(artifact(profile)).isEqualTo(oldYaml);
        assertThat(node(source, "香港").id()).isEqualTo(node);
        var draft = profileRepository.draft(owner, profile);
        var preview = publisher.preview(owner, profile, draft.version(), null);
        assertThat(preview.report().errors()).isEmpty();
        assertThat(YAML.load(preview.yaml()).value().path("proxies").path(0).path("name").asString())
                .isEqualTo("香港 · 我的机场");
        assertThat(artifact(profile)).isEqualTo(oldYaml);

        publisher.publish(owner, profile, draft.version(), null);
        assertThat(YAML.load(artifact(profile)).value().path("proxies").path(0).path("name").asString())
                .isEqualTo("香港 · 我的机场");
        assertThat(published(profile)).isEqualTo(oldDefinition);
    }

    @Test void automaticRefreshKeepsSourceLabelAndReferencesWhenNodeParametersChange() {
        var source = source("自动更新机场", yaml("日本"));
        var node = node(source, "日本").id();
        var profile = profile("选择节点", List.of(node), node);
        respond(source, yaml("日本").replace("port: 443", "port: 8443"));

        assertThat(run(source).status()).isEqualTo("SUCCESS");
        var output = YAML.load(artifact(profile)).value();
        assertThat(output.path("proxies").path(0).path("name").asString()).isEqualTo("日本 · 自动更新机场");
        assertThat(output.path("proxies").path(0).path("port").asInt()).isEqualTo(8443);
        assertThat(output.path("proxy-groups").path(0).path("proxies").path(0).asString())
                .isEqualTo("日本 · 自动更新机场");
        assertThat(node(source, "日本").id()).isEqualTo(node);
    }
}
