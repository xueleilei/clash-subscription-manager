package dev.clashsub.compile;

import com.sun.net.httpserver.HttpServer;
import dev.clashsub.common.Diagnostic;
import dev.clashsub.parse.SafeYaml;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

class MihomoValidatorIT {
    @TempDir Path temp;
    private final Path root = Path.of("..").toAbsolutePath().normalize();
    private final Path executable = root.resolve(".cache/mihomo/" + (System.getProperty("os.name").contains("Mac") ? "darwin" : "linux")
            + "-" + (System.getProperty("os.arch").contains("aarch64") ? "arm64" : "amd64") + "/mihomo");
    private final Path resources = root.resolve(".cache/mihomo/resources");
    private final SafeYaml yaml = new SafeYaml();

    private MihomoValidator validator() { return new MihomoValidator(executable, resources, Duration.ofSeconds(10), yaml); }
    private String minimal() throws Exception { return Files.readString(root.resolve("tests/fixtures/mihomo/minimal.yaml")); }

    @Test void acceptsRealCoreAndRejectFallbackGroups() throws Exception {
        assertThat(Files.isExecutable(executable)).as("Run tools/mihomo/fetch.sh first").isTrue();
        assertThat(validator().validate(minimal()).errors()).isEmpty();
    }

    @Test void acceptsSupportedClashProtocolFixturesWithRealCore() throws Exception {
        var fixture = Files.readString(root.resolve("tests/fixtures/subscriptions/clash.yaml"));
        assertThat(validator().validate(fixture + "\nrules: ['MATCH,REJECT']\n").errors()).isEmpty();
    }

    @Test void acceptsAllSevenUriProtocolsWithRealCore() throws Exception {
        var parser = new dev.clashsub.parse.SubscriptionParser(new dev.clashsub.parse.ClashYamlParser(yaml, new dev.clashsub.parse.ProtocolFieldValidator()));
        var parsed = parser.parse(Files.readAllBytes(root.resolve("tests/fixtures/subscriptions/uris.txt")), dev.clashsub.parse.InputFormat.AUTO);
        assertThat(parsed.errors()).isEmpty();
        assertThat(parsed.nodes()).hasSize(7);
        var config = tools.jackson.databind.json.JsonMapper.builder().build().createObjectNode();
        var proxies = config.putArray("proxies");
        for (var node : parsed.nodes()) { proxies.add(node.connection().put("name", node.originalName()).put("type", node.protocol())); }
        config.putArray("rules").add("MATCH,REJECT");
        assertThat(validator().validate(yaml.dump(new dev.clashsub.parse.YamlDocument(config))).errors()).isEmpty();
    }

    @Test void rejectsBadProtocolCircularGroupsAndMissingTargets() {
        assertThat(validator().validate("proxies: [{name: bad, type: ss, server: example.com, port: 443, cipher: invalid, password: fixture}]\nrules: ['MATCH,REJECT']\n").valid()).isFalse();
        assertThat(validator().validate("proxy-groups: [{name: A, type: select, proxies: [B]}, {name: B, type: select, proxies: [A]}]\nrules: ['MATCH,A']\n").valid()).isFalse();
        assertThat(validator().validate("rules: ['DOMAIN,example.com,MISSING', 'MATCH,REJECT']\n").valid()).isFalse();
    }

    @Test void providerSubstitutionDoesNotFetchAndPreservesOriginal() throws Exception {
        var requests = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/bait", exchange -> { requests.incrementAndGet(); exchange.sendResponseHeaders(500, -1); exchange.close(); });
        server.start();
        try {
            var original = "rule-providers:\n  demo:\n    type: http\n    behavior: domain\n    format: yaml\n    url: http://127.0.0.1:" + server.getAddress().getPort() + "/bait\n    path: /host-secret-bait\n    interval: 86400\nrules: ['RULE-SET,demo,REJECT', 'MATCH,REJECT']\n";
            var copy = new ValidationCopyFactory(yaml, resources).create(original, temp);
            assertThat(copy.report().valid()).isTrue();
            assertThat(copy.yaml()).doesNotContain("/bait", "/host-secret-bait");
            assertThat(copy.changes()).isNotEmpty();
            assertThat(validator().validate(original).errors()).isEmpty();
            assertThat(requests.get()).isZero();
            assertThat(original).contains("/host-secret-bait", "/bait");
        } finally { server.stop(0); }
    }

    @Test void rejectsHostFileFieldsAndMalformedProviderBeforeSubstitution() {
        assertThat(validator().validate("tls: {certificate: /host-secret-bait}\nrules: ['MATCH,REJECT']\n").errors())
                .extracting(Diagnostic::code).contains("UNSAFE_VALIDATION_FIELD");
        assertThat(validator().validate("rule-providers: {x: {type: http, behavior: invalid, url: 'file:///etc/passwd', interval: 1}}\nrules: ['MATCH,REJECT']\n").valid()).isFalse();
    }

    @Test void copyCannotHideInvalidTypesInReplacedFields() {
        assertThat(validator().validate("geodata-mode: invalid\nrules: ['MATCH,REJECT']\n").valid()).isFalse();
        assertThat(validator().validate("geo-auto-update: [true]\nrules: ['MATCH,REJECT']\n").valid()).isFalse();
        assertThat(validator().validate("rule-providers: {x: {type: http, behavior: domain, url: 'https://example.com/rules', interval: 10, header: invalid}}\nrules: ['MATCH,REJECT']\n").valid()).isFalse();
    }
    @Test void zeroProviderSizeLimitMatchesThePinnedCoreSemantics() {
        String config = "rule-providers: {fixture: {type: http, behavior: domain, format: yaml, url: 'https://example.invalid/rules', interval: 3600, size-limit: 0}}\nrules: ['RULE-SET,fixture,REJECT', 'MATCH,REJECT']\n";
        assertThat(validator().validate(config).errors()).isEmpty();
    }

    @Test void killsTimedOutProcessAndBoundsOutput() throws Exception {
        var hanging = temp.resolve("hanging-core");
        Files.writeString(hanging, "#!/bin/sh\nsleep 60\n");
        hanging.toFile().setExecutable(true);
        var started = System.nanoTime();
        var timedOut = new MihomoValidator(hanging, resources, Duration.ofMillis(150), yaml).validate("rules: ['MATCH,REJECT']\n");
        assertThat(timedOut.errors()).extracting(Diagnostic::code).contains("CORE_TIMEOUT");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
        var verbose = temp.resolve("verbose-core");
        Files.writeString(verbose, "#!/bin/sh\nwhile :; do printf '%01000d' 0; done\n");
        verbose.toFile().setExecutable(true);
        assertThat(new MihomoValidator(verbose, resources, Duration.ofSeconds(3), yaml).validate("rules: ['MATCH,REJECT']\n").errors())
                .extracting(Diagnostic::code).contains("CORE_OUTPUT_LIMIT");
    }

    @Test void fixedResourcesPassInsideContainerWithNoNetwork() throws Exception {
        var directory = Files.createDirectory(temp.resolve("offline"));
        var copy = new ValidationCopyFactory(yaml, resources).create(minimal(), directory);
        assertThat(copy.report().errors()).isEmpty();
        Files.writeString(directory.resolve("config.yaml"), copy.yaml());
        var process = new ProcessBuilder(root.resolve("tools/mihomo/check-offline.sh").toString(), directory.toString())
                .redirectErrorStream(true).start();
        assertThat(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        var output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(process.exitValue()).as(output).isZero();
        assertThat(output).contains("test is successful");
    }
}
