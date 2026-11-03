package dev.clashsub.auth;

import dev.clashsub.ClashSubApplication;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthenticationIT {
    private static final String USERNAME = "fixture-admin";
    private static final String PASSWORD = "Fixture-password-234567";
    private static final Path PASSWORD_FILE = passwordFile();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.13");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", MYSQL::getJdbcUrl);
        properties.add("spring.datasource.username", MYSQL::getUsername);
        properties.add("spring.datasource.password", MYSQL::getPassword);
        properties.add("clash.admin.username", () -> USERNAME);
        properties.add("clash.admin.password-file", PASSWORD_FILE::toString);
        properties.add("server.servlet.session.cookie.secure", () -> false);
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    private HttpClient client;

    @BeforeEach
    void client() {
        client = HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Test
    void issuesCsrfTokenForLogin() throws Exception {
        var response = get("/api/v1/csrf");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(data(response).path("token").asText()).isNotBlank();
        assertThat(response.headers().allValues("set-cookie").toString()).contains("HttpOnly");
    }

    @Test
    void anonymousSessionIsJsonUnauthorized() throws Exception {
        var response = get("/api/v1/session");
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(JSON.readTree(response.body()).path("code").asText()).isEqualTo("A0002");
    }

    @Test
    void validLoginEstablishesSessionAndLogoutRevokesIt() throws Exception {
        var csrf = data(get("/api/v1/csrf"));
        var response = login(PASSWORD, csrf);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(data(get("/api/v1/session")).path("username").asText()).isEqualTo(USERNAME);
        var logout = send("DELETE", "/api/v1/session", "", data(get("/api/v1/csrf")));
        assertThat(logout.statusCode()).isEqualTo(200);
        assertThat(get("/api/v1/session").statusCode()).isEqualTo(401);
    }

    @Test
    void wrongPasswordDoesNotAuthenticate() throws Exception {
        assertThat(login("incorrect-fixture", data(get("/api/v1/csrf"))).statusCode()).isEqualTo(401);
        assertThat(get("/api/v1/session").statusCode()).isEqualTo(401);
    }

    @Test
    void cookieAuthenticatedWriteRequiresCsrf() throws Exception {
        assertThat(login(PASSWORD, data(get("/api/v1/csrf"))).statusCode()).isEqualTo(200);
        assertThat(send("DELETE", "/api/v1/session", "", null).statusCode()).isEqualTo(403);
        assertThat(get("/api/v1/session").statusCode()).isEqualTo(200);
    }

    @Test
    void restartNeverResetsAnExistingAdministrator() throws Exception {
        var before = jdbc.queryForObject("SELECT password_hash FROM app_user WHERE username = ?", String.class, USERNAME);
        assertThat(before).isNotEqualTo(PASSWORD).startsWith("{bcrypt}");
        try (var restarted = new SpringApplicationBuilder(ClashSubApplication.class)
                .web(WebApplicationType.SERVLET)
                .run("--server.port=0", "--spring.datasource.url=" + MYSQL.getJdbcUrl(),
                        "--spring.datasource.username=" + MYSQL.getUsername(),
                        "--spring.datasource.password=" + MYSQL.getPassword(),
                        "--clash.admin.username=do-not-replace-admin",
                        "--clash.admin.password-file=/missing-bootstrap-secret")) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT password_hash FROM app_user WHERE username = ?", String.class, USERNAME))
                    .isEqualTo(before);
        }
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> login(String password, JsonNode csrf) throws Exception {
        return send("POST", "/api/v1/session", "username=" + USERNAME + "&password="
                + URLEncoder.encode(password, StandardCharsets.UTF_8), csrf);
    }

    private HttpResponse<String> send(String method, String path, String body, JsonNode csrf) throws Exception {
        var request = HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/x-www-form-urlencoded")
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        if (csrf != null) {
            request.header(csrf.path("headerName").asText(), csrf.path("token").asText());
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private JsonNode data(HttpResponse<String> response) throws Exception {
        return JSON.readTree(response.body()).path("data");
    }

    private static Path passwordFile() {
        try {
            var path = Files.createTempFile("clash-test-admin-", ".secret");
            Files.writeString(path, PASSWORD);
            path.toFile().deleteOnExit();
            return path;
        } catch (java.io.IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }
}
