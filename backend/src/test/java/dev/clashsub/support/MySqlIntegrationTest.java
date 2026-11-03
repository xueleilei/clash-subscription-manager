package dev.clashsub.support;

import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.mysql.MySQLContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class MySqlIntegrationTest {
    public static final String ADMIN = "domain-fixture-admin";
    public static final String PASSWORD = "Domain-fixture-password-234567";
    protected static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.13");
    private static final Path PASSWORD_FILE = secret(PASSWORD.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    private static final Path KEY_FILE = secret(new byte[32]);
    static { MYSQL.start(); }

    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", MYSQL::getJdbcUrl);
        properties.add("spring.datasource.username", MYSQL::getUsername);
        properties.add("spring.datasource.password", MYSQL::getPassword);
        properties.add("clash.admin.username", () -> ADMIN);
        properties.add("clash.admin.password-file", PASSWORD_FILE::toString);
        properties.add("clash.encryption-key-file", KEY_FILE::toString);
        properties.add("server.servlet.session.cookie.secure", () -> false);
    }
    private static Path secret(byte[] bytes) {
        try { var path = Files.createTempFile("clash-fixture-", ".secret"); Files.write(path, bytes); path.toFile().deleteOnExit(); return path; }
        catch (java.io.IOException exception) { throw new java.io.UncheckedIOException(exception); }
    }
}
