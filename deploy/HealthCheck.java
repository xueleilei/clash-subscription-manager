import java.net.URI;
import java.net.http.*;
import java.time.Duration;
public final class HealthCheck {
    public static void main(String[] args) {
        try {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:8080/actuator/health")).timeout(Duration.ofSeconds(12)).GET().build();
            var response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build().send(request, HttpResponse.BodyHandlers.discarding());
            System.exit(response.statusCode() == 200 ? 0 : 1);
        } catch (Exception exception) { System.exit(1); }
    }
}
