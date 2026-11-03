package dev.clashsub.web;
import dev.clashsub.support.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import static org.assertj.core.api.Assertions.*;
class SpaRoutesIT extends MySqlIntegrationTest {
    @LocalServerPort int port;
    @Test void applicationRoutesServeTheEntryWithoutSwallowingApiSubscriptionOrAssetErrors() throws Exception {
        var client = new ApiTestClient(port);
        for (var route : List.of("/login", "/profiles", "/profiles/fixture-id", "/sources", "/nodes", "/import")) {
            var response = client.get(route); assertThat(response.statusCode()).as(route).isEqualTo(200);
            assertThat(response.body()).contains("fixture-application");
        }
        assertThat(client.get("/sub/invalid").statusCode()).isEqualTo(404);
        assertThat(client.get("/assets/absent.js").statusCode()).isEqualTo(404);
        assertThat(client.get("/api/v1/absent").statusCode()).isEqualTo(401);
        client.login(ADMIN, PASSWORD); var missing = client.get("/api/v1/absent");
        assertThat(missing.statusCode()).isEqualTo(404); assertThat(missing.body()).doesNotContain("fixture-application");
    }
}
