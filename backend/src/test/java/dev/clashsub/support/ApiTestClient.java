package dev.clashsub.support;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

public final class ApiTestClient {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
    private final String origin;
    public ApiTestClient(int port) { this.origin = "http://127.0.0.1:" + port; }
    public HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(origin + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    public void login(String username, String password) throws Exception {
        var csrf = data(get("/api/v1/csrf"));
        var request = HttpRequest.newBuilder(URI.create(origin + "/api/v1/session"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header(csrf.path("headerName").asString(), csrf.path("token").asString())
                .POST(HttpRequest.BodyPublishers.ofString("username=" + URLEncoder.encode(username, StandardCharsets.UTF_8)
                        + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8))).build();
        assertThat(client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
    }
    public HttpResponse<String> send(String method, String path, Object body) throws Exception {
        var csrf = data(get("/api/v1/csrf"));
        var request = HttpRequest.newBuilder(URI.create(origin + path)).header("Content-Type", "application/json")
                .header(csrf.path("headerName").asString(), csrf.path("token").asString())
                .method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
    public JsonNode data(HttpResponse<String> response) { return JSON.readTree(response.body()).path("data"); }
}
