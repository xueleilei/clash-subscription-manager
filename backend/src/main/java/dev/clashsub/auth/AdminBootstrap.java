package dev.clashsub.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class AdminBootstrap implements ApplicationRunner {
    private final UserMapper users;
    private final PasswordEncoder passwords;
    private final String username;
    private final String passwordFile;

    public AdminBootstrap(UserMapper users, PasswordEncoder passwords,
            @Value("${clash.admin.username}") String username,
            @Value("${clash.admin.password-file}") String passwordFile) {
        this.users = users;
        this.passwords = passwords;
        this.username = username;
        this.passwordFile = passwordFile;
    }

    @Override public void run(ApplicationArguments arguments) throws IOException {
        if (users.count() > 0) { return; }
        if (username.isBlank() || username.length() > 128 || passwordFile.isBlank()) {
            throw new IllegalStateException("Initial administrator requires ADMIN_USERNAME and ADMIN_PASSWORD_FILE");
        }
        String password;
        try (var input = Files.newInputStream(Path.of(passwordFile))) {
            byte[] bytes = input.readNBytes(1025);
            if (bytes.length > 1024) { throw new IllegalStateException("Administrator password file is too large"); }
            password = new String(bytes, StandardCharsets.UTF_8).stripTrailing();
            java.util.Arrays.fill(bytes, (byte) 0);
        }
        if (password.length() < 12 || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalStateException("Administrator password must be at least 12 characters and at most 72 UTF-8 bytes");
        }
        users.insert(new AppUserDO(UUID.randomUUID().toString(), username, passwords.encode(password), 0));
    }
}
