package dev.clashsub.auth;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public final class AdminPrincipal implements UserDetails, CredentialsContainer {
    private final UUID id;
    private final String username;
    private String password;

    public AdminPrincipal(AppUserDO user) {
        this.id = UUID.fromString(user.id());
        this.username = user.username();
        this.password = user.passwordHash();
    }

    public UUID id() { return id; }
    @Override public String getUsername() { return username; }
    @Override public String getPassword() { return password; }
    @Override public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }
    @Override public void eraseCredentials() { password = null; }
    @Override public String toString() { return "AdminPrincipal[id=" + id + "]"; }
}
