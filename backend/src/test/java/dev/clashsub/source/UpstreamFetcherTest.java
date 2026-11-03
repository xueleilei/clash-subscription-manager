package dev.clashsub.source;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class UpstreamFetcherTest {
    private final List<HttpServer> servers = new ArrayList<>();
    private final FetchLimits limits = new FetchLimits(Duration.ofMillis(200), Duration.ofMillis(300), Duration.ofSeconds(2), 1024, 3);

    @AfterEach void stop() { servers.forEach(server -> server.stop(0)); }

    @Test void readsSuccessAndReturnsSafeAuthenticationFailure() throws Exception {
        var server = server();
        server.createContext("/ok", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer fixture-password");
            byte[] body = "proxies: []".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.createContext("/unauthorized", exchange -> { exchange.sendResponseHeaders(401, -1); exchange.close(); });
        var fetcher = local(limits);
        assertThat(fetcher.fetch(request(url(server, "/ok"))).body()).isEqualTo("proxies: []".getBytes(StandardCharsets.UTF_8));
        var failure = fetcher.fetch(request(url(server, "/unauthorized?secret-query=fixture-password")));
        assertThat(failure.status()).isEqualTo(401);
        assertThat(failure.errorCode()).isEqualTo("UPSTREAM_HTTP_401");
        assertThat(failure.toString()).doesNotContain("fixture-password", "secret-query");
    }

    @Test void rejectsHtmlAndBothCompressedAndExpandedSizeLimits() throws Exception {
        var server = server();
        server.createContext("/html", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/html");
            exchange.sendResponseHeaders(200, 0); exchange.getResponseBody().write("<html>Login</html>".getBytes()); exchange.close();
        });
        var zipped = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(zipped)) { gzip.write(new byte[4096]); }
        server.createContext("/gzip", exchange -> {
            exchange.getResponseHeaders().add("Content-Encoding", "gzip");
            exchange.sendResponseHeaders(200, zipped.size()); exchange.getResponseBody().write(zipped.toByteArray()); exchange.close();
        });
        server.createContext("/large", exchange -> { exchange.sendResponseHeaders(200, 4096); exchange.getResponseBody().write(new byte[4096]); exchange.close(); });
        assertThat(local(limits).fetch(request(url(server, "/html"))).errorCode()).isEqualTo("UPSTREAM_UNEXPECTED_CONTENT");
        assertThat(local(limits).fetch(request(url(server, "/gzip"))).errorCode()).isEqualTo("UPSTREAM_TOO_LARGE");
        assertThat(local(limits).fetch(request(url(server, "/large"))).errorCode()).isEqualTo("UPSTREAM_TOO_LARGE");
    }

    @Test void timeoutIsBounded() throws Exception {
        var server = server();
        server.createContext("/slow", exchange -> {
            try { Thread.sleep(600); exchange.sendResponseHeaders(200, -1); }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        var shortLimits = new FetchLimits(Duration.ofMillis(100), Duration.ofMillis(100), Duration.ofMillis(200), 1024, 3);
        long start = System.nanoTime();
        assertThat(local(shortLimits).fetch(request(url(server, "/slow"))).errorCode()).isEqualTo("UPSTREAM_TIMEOUT");
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
    }

    @Test void everyRedirectChecksAddressAndRedirectCount() throws Exception {
        var server = server();
        server.createContext("/private", exchange -> { exchange.getResponseHeaders().add("Location", "http://169.254.169.254/"); exchange.sendResponseHeaders(302, -1); exchange.close(); });
        server.createContext("/loop", exchange -> { exchange.getResponseHeaders().add("Location", "/loop"); exchange.sendResponseHeaders(302, -1); exchange.close(); });
        assertThat(local(limits).fetch(request(url(server, "/private"))).errorCode()).isEqualTo("UPSTREAM_ADDRESS_BLOCKED");
        assertThat(local(limits).fetch(request(url(server, "/loop"))).errorCode()).isEqualTo("UPSTREAM_REDIRECT_LIMIT");
    }

    @Test void defaultPolicyBlocksPrivateAndMappedIpv6WithoutConnecting() {
        var fetcher = new UpstreamFetcher(new SafeDnsResolver(List.of(), InetAddress::getAllByName), limits, Map.of());
        for (String target : List.of("http://127.0.0.1/", "http://[::ffff:127.0.0.1]/", "http://[::1]/", "http://10.0.0.1/", "http://169.254.169.254/")) {
            assertThat(fetcher.fetch(request(target)).errorCode()).as(target).isEqualTo("UPSTREAM_ADDRESS_BLOCKED");
        }
        assertThat(fetcher.fetch(request("file:///etc/passwd")).errorCode()).isEqualTo("UPSTREAM_INVALID_URL");
    }

    @Test void checkedDnsAddressIsPinnedAcrossConnection() throws Exception {
        var server = server();
        var lookups = new AtomicInteger();
        server.createContext("/", exchange -> { byte[] body = "proxies: []".getBytes(); exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close(); });
        var dns = new SafeDnsResolver(List.of("127.0.0.1/32"), host -> new InetAddress[]{
                InetAddress.getByName(lookups.incrementAndGet() == 1 ? "127.0.0.1" : "169.254.169.254")});
        var result = new UpstreamFetcher(dns, limits, Map.of()).fetch(request("http://rebind.test:" + server.getAddress().getPort() + "/"));
        assertThat(result.success()).isTrue();
        assertThat(lookups.get()).isEqualTo(1);
    }

    @Test void crossingHostsDropsAllCredentialHeaders() throws Exception {
        var server = server();
        var auth = new AtomicReference<String>(); var custom = new AtomicReference<String>(); var cookie = new AtomicReference<String>();
        server.createContext("/start", exchange -> { exchange.getResponseHeaders().add("Location", "http://second.test:" + server.getAddress().getPort() + "/end"); exchange.sendResponseHeaders(302, -1); exchange.close(); });
        server.createContext("/end", exchange -> {
            auth.set(exchange.getRequestHeaders().getFirst("Authorization")); custom.set(exchange.getRequestHeaders().getFirst("X-Api-Key")); cookie.set(exchange.getRequestHeaders().getFirst("Cookie"));
            byte[] body = "proxies: []".getBytes(); exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        var dns = new SafeDnsResolver(List.of("127.0.0.1/32"), host -> new InetAddress[]{InetAddress.getByName("127.0.0.1")});
        var request = new SourceRequest("http://first.test:" + server.getAddress().getPort() + "/start",
                Map.of("Authorization", "fixture-password", "X-Api-Key", "fixture-password", "Cookie", "fixture-password"), "fixture-UA", "");
        assertThat(new UpstreamFetcher(dns, limits, Map.of()).fetch(request).success()).isTrue();
        assertThat(auth.get()).isNull(); assertThat(custom.get()).isNull(); assertThat(cookie.get()).isNull();
    }

    @Test void rejectsCaseInsensitiveDuplicateHeaders() throws Exception {
        var server = server();
        server.createContext("/", exchange -> { exchange.sendResponseHeaders(200, -1); exchange.close(); });
        var request = new SourceRequest(url(server, "/"), Map.of("Authorization", "first", "authorization", "second"), "", "");
        assertThat(local(limits).fetch(request).errorCode()).isEqualTo("UPSTREAM_INVALID_HEADERS");
    }

    @Test void rejectsOversizedSlowResponseWithoutDrainingIt() throws Exception {
        var server = server();
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, 4096);
            exchange.getResponseBody().write(1); exchange.getResponseBody().flush();
            try { Thread.sleep(600); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        var shortLimits = new FetchLimits(Duration.ofMillis(100), Duration.ofMillis(100), Duration.ofMillis(200), 1024, 3);
        assertThat(local(shortLimits).fetch(request(url(server, "/"))).errorCode()).isEqualTo("UPSTREAM_TOO_LARGE");
    }

    @Test void tlsCertificateVerificationIsNotDisabledForPinnedDns() throws Exception {
        var store = java.security.KeyStore.getInstance("PKCS12");
        try (var input = java.nio.file.Files.newInputStream(java.nio.file.Path.of("../tests/fixtures/http/fixture-server.p12"))) {
            store.load(input, "fixture-only-password".toCharArray());
        }
        var managers = javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());
        managers.init(store, "fixture-only-password".toCharArray());
        var ssl = javax.net.ssl.SSLContext.getInstance("TLS"); ssl.init(managers.getKeyManagers(), null, null);
        var server = com.sun.net.httpserver.HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new com.sun.net.httpserver.HttpsConfigurator(ssl)); servers.add(server); server.start();
        server.createContext("/", exchange -> { exchange.sendResponseHeaders(200, -1); exchange.close(); });
        var dns = new SafeDnsResolver(List.of("127.0.0.1/32"), host -> new InetAddress[]{InetAddress.getByName("127.0.0.1")});
        var result = new UpstreamFetcher(dns, new FetchLimits(Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofSeconds(3), 1024, 3), Map.of())
                .fetch(request("https://fixture.test:" + server.getAddress().getPort() + "/"));
        assertThat(result.errorCode()).isEqualTo("UPSTREAM_NETWORK_ERROR");
    }

    @Test void expandedGzipLimitAbortsBeforeDrainingRemainingChunks() throws Exception {
        var server = server();
        var zipped = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(zipped)) { gzip.write(new byte[4096]); }
        server.createContext("/", exchange -> {
            exchange.getResponseHeaders().add("Content-Encoding", "gzip");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(zipped.toByteArray()); exchange.getResponseBody().flush();
            try { Thread.sleep(600); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        var shortLimits = new FetchLimits(Duration.ofMillis(100), Duration.ofMillis(100), Duration.ofMillis(200), 1024, 3);
        assertThat(local(shortLimits).fetch(request(url(server, "/"))).errorCode()).isEqualTo("UPSTREAM_TOO_LARGE");
    }

    private UpstreamFetcher local(FetchLimits limits) { return new UpstreamFetcher(new SafeDnsResolver(List.of("127.0.0.1/32"), InetAddress::getAllByName), limits, Map.of()); }
    private SourceRequest request(String url) { return new SourceRequest(url, Map.of("Authorization", "Bearer fixture-password"), "fixture-UA", ""); }
    private String url(HttpServer server, String path) { return "http://127.0.0.1:" + server.getAddress().getPort() + path; }
    private HttpServer server() throws Exception { var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); servers.add(server); server.start(); return server; }
}
