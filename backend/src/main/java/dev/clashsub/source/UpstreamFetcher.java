package dev.clashsub.source;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class UpstreamFetcher {
    private static final String DEFAULT_UA = "ClashSubscriptionManager/0.1";
    private static final Semaphore SLOTS = new Semaphore(4, true);
    private static final java.util.concurrent.ExecutorService WORKERS = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("upstream-fetch-", 0).factory());
    private final SafeDnsResolver dns;
    private final FetchLimits limits;
    private final Map<String, String> trustedProxies;

    @Autowired public UpstreamFetcher(SafeDnsResolver dns, @Value("${clash.fetch.trusted-proxy-url:}") String proxy) {
        this(dns, FetchLimits.defaults(), proxy.isBlank() ? Map.of() : Map.of("default", proxy));
    }
    public UpstreamFetcher(SafeDnsResolver dns, FetchLimits limits, Map<String, String> trustedProxies) {
        this.dns = dns; this.limits = limits; this.trustedProxies = Map.copyOf(trustedProxies);
    }
    public List<String> proxyNames() { return trustedProxies.keySet().stream().sorted().toList(); }

    public FetchResult fetch(SourceRequest source) {
        var active = new AtomicReference<HttpGet>();
        try {
            SourceRequestValidator.validate(source);
            if (!source.outboundProxy().isEmpty() && !trustedProxies.containsKey(source.outboundProxy())) {
                return FetchResult.failure("UPSTREAM_PROXY_NOT_CONFIGURED");
            }
            if (!SLOTS.tryAcquire()) { return FetchResult.failure("UPSTREAM_BUSY"); }
            var future = WORKERS.submit(() -> {
                try { return fetchChecked(source, active); }
                finally { SLOTS.release(); }
            });
            try { return future.get(limits.total().toMillis(), TimeUnit.MILLISECONDS); }
            catch (TimeoutException exception) {
                var request = active.get(); if (request != null) { request.cancel(); }
                future.cancel(true); return FetchResult.failure("UPSTREAM_TIMEOUT");
            }
            catch (java.util.concurrent.ExecutionException exception) {
                var cause = exception.getCause();
                if (cause instanceof FetchPolicyException policy) { return FetchResult.failure(policy.code()); }
                if (cause instanceof java.net.SocketTimeoutException) { return FetchResult.failure("UPSTREAM_TIMEOUT"); }
                return FetchResult.failure("UPSTREAM_NETWORK_ERROR");
            }
        } catch (FetchPolicyException exception) { return FetchResult.failure(exception.code()); }
        catch (InterruptedException exception) {
            var request = active.get(); if (request != null) { request.cancel(); }
            Thread.currentThread().interrupt(); return FetchResult.failure("UPSTREAM_INTERRUPTED");
        }
    }

    private FetchResult fetchChecked(SourceRequest source, AtomicReference<HttpGet> active) throws IOException {
        URI current = SourceRequestValidator.url(source.url());
        Map<String, String> headers = source.headers();
        String userAgent = source.userAgent().isBlank() ? DEFAULT_UA : source.userAgent();
        for (int hop = 0; hop <= limits.maxRedirects(); hop++) {
            final String host = unbracket(current.getHost());
            final URI proxy = source.outboundProxy().isEmpty() ? null : URI.create(trustedProxies.get(source.outboundProxy()));
            final InetAddress[] pinned = dns.resolveAndCheck(host, proxy != null);
            var resolver = new DnsResolver() {
                @Override public InetAddress[] resolve(String name) throws UnknownHostException {
                    if (unbracket(name).equalsIgnoreCase(host)) { return pinned.clone(); }
                    if (proxy != null && name.equalsIgnoreCase(proxy.getHost())) { return InetAddress.getAllByName(name); }
                    throw new UnknownHostException();
                }
                @Override public String resolveCanonicalHostname(String name) { return name; }
            };
            var manager = PoolingHttpClientConnectionManagerBuilder.create().setDnsResolver(resolver)
                    .setMaxConnTotal(1).setMaxConnPerRoute(1)
                    .setDefaultConnectionConfig(ConnectionConfig.custom()
                            .setConnectTimeout(Timeout.ofMilliseconds(limits.connect().toMillis()))
                            .setSocketTimeout(Timeout.ofMilliseconds(limits.response().toMillis())).build()).build();
            var builder = HttpClients.custom().setConnectionManager(manager)
                    .disableRedirectHandling().disableAutomaticRetries().disableContentCompression().disableCookieManagement()
                    .setDefaultRequestConfig(RequestConfig.custom().setResponseTimeout(Timeout.ofMilliseconds(limits.response().toMillis()))
                            .setConnectionRequestTimeout(Timeout.ofMilliseconds(limits.connect().toMillis())).build());
            if (proxy != null) {
                if (!Set.of("http", "https").contains(proxy.getScheme()) || proxy.getHost() == null
                        || proxy.getQuery() != null || proxy.getFragment() != null) { throw new FetchPolicyException("UPSTREAM_PROXY_NOT_CONFIGURED"); }
                var proxyHost = new HttpHost(proxy.getScheme(), proxy.getHost(), proxy.getPort() < 0 ? (proxy.getScheme().equals("https") ? 443 : 80) : proxy.getPort());
                builder.setProxy(proxyHost);
                if (proxy.getRawUserInfo() != null) {
                    var credentials = new BasicCredentialsProvider();
                    var parts = proxy.getUserInfo().split(":", 2);
                    credentials.setCredentials(new AuthScope(proxyHost), new UsernamePasswordCredentials(parts[0], (parts.length == 2 ? parts[1] : "").toCharArray()));
                    builder.setDefaultCredentialsProvider(credentials);
                }
            }
            var request = new HttpGet(current);
            headers.forEach(request::setHeader);
            request.setHeader("User-Agent", userAgent); request.setHeader("Accept-Encoding", "gzip");
            active.set(request);
            try (var client = builder.build(); var response = client.executeOpen(null, request, null)) {
                int status = response.getCode();
                if (Set.of(301, 302, 303, 307, 308).contains(status)) {
                    if (hop == limits.maxRedirects()) { throw new FetchPolicyException("UPSTREAM_REDIRECT_LIMIT"); }
                    var location = response.getFirstHeader("Location");
                    if (location == null) { throw new FetchPolicyException("UPSTREAM_INVALID_REDIRECT"); }
                    URI next = SourceRequestValidator.url(current.resolve(location.getValue()).toString());
                    if (!sameOrigin(current, next)) { headers = Map.of(); userAgent = DEFAULT_UA; }
                    current = next;
                    request.cancel();
                    continue;
                }
                if (status != 200) { request.cancel(); return new FetchResult(new byte[0], status, "", Instant.now(), "UPSTREAM_HTTP_" + status); }
                String contentType = response.getFirstHeader("Content-Type") == null ? "" : response.getFirstHeader("Content-Type").getValue().split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
                if (contentType.contains("html")) { throw new FetchPolicyException("UPSTREAM_UNEXPECTED_CONTENT"); }
                var entity = response.getEntity();
                if (entity == null) { return new FetchResult(new byte[0], status, contentType, Instant.now(), null); }
                if (entity.getContentLength() > limits.maxBytes()) { throw new FetchPolicyException("UPSTREAM_TOO_LARGE"); }
                String encoding = response.getFirstHeader("Content-Encoding") == null ? "identity" : response.getFirstHeader("Content-Encoding").getValue().strip().toLowerCase(Locale.ROOT);
                if (!Set.of("gzip", "identity").contains(encoding)) { throw new FetchPolicyException("UPSTREAM_UNSUPPORTED_ENCODING"); }
                var bounded = new LimitedInputStream(entity.getContent(), limits.maxBytes());
                try (var input = encoding.equals("gzip") ? new GZIPInputStream(bounded) : bounded) {
                    var output = new ByteArrayOutputStream(); byte[] buffer = new byte[8192];
                    for (int read; (read = input.read(buffer)) != -1;) {
                        if ((long) output.size() + read > limits.maxBytes()) { throw new FetchPolicyException("UPSTREAM_TOO_LARGE"); }
                        output.write(buffer, 0, read);
                    }
                    byte[] bytes = output.toByteArray();
                    String prefix = new String(bytes, 0, Math.min(bytes.length, 256), StandardCharsets.UTF_8).stripLeading().toLowerCase(Locale.ROOT);
                    if (prefix.startsWith("<!doctype html") || prefix.startsWith("<html")) { throw new FetchPolicyException("UPSTREAM_UNEXPECTED_CONTENT"); }
                    return new FetchResult(bytes, 200, contentType, Instant.now(), null);
                }
            } finally { request.cancel(); active.compareAndSet(request, null); manager.close(); }
        }
        return FetchResult.failure("UPSTREAM_REDIRECT_LIMIT");
    }

    private static boolean sameOrigin(URI left, URI right) {
        int leftPort = left.getPort() < 0 ? (left.getScheme().equalsIgnoreCase("https") ? 443 : 80) : left.getPort();
        int rightPort = right.getPort() < 0 ? (right.getScheme().equalsIgnoreCase("https") ? 443 : 80) : right.getPort();
        return left.getScheme().equalsIgnoreCase(right.getScheme()) && left.getHost().equalsIgnoreCase(right.getHost()) && leftPort == rightPort;
    }
    private static String unbracket(String host) { return host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host; }
    private static class LimitedInputStream extends FilterInputStream {
        private final long limit; private long count;
        LimitedInputStream(InputStream input, long limit) { super(input); this.limit = limit; }
        @Override public int read() throws IOException { int value = in.read(); if (value >= 0) { check(1); } return value; }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException { int read = in.read(bytes, offset, length); if (read > 0) { check(read); } return read; }
        private void check(int increment) { count += increment; if (count > limit) { throw new FetchPolicyException("UPSTREAM_TOO_LARGE"); } }
    }
}
