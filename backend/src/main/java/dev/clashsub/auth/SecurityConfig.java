package dev.clashsub.auth;

import dev.clashsub.common.ApiResult;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.savedrequest.NullRequestCache;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class SecurityConfig {
    @Bean PasswordEncoder passwords() { return PasswordEncoderFactories.createDelegatingPasswordEncoder(); }

    @Bean UserDetailsService userDetailsService(UserMapper users) {
        return username -> {
            var user = users.findByUsername(username);
            if (user == null) { throw new UsernameNotFoundException("Invalid credentials"); }
            return new AdminPrincipal(user);
        };
    }

    @Bean SecurityFilterChain security(HttpSecurity http, JsonMapper json) throws Exception {
        return http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/api/v1/csrf", "/actuator/health", "/sub/**").permitAll()
                        .requestMatchers("/api/**", "/actuator/**").authenticated()
                        .anyRequest().permitAll())
                .csrf(csrf -> csrf.csrfTokenRepository(new HttpSessionCsrfTokenRepository()))
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .formLogin(form -> form.loginProcessingUrl("/api/v1/session")
                        .successHandler((request, response, auth) -> write(json, response, 200, ApiResult.success(null)))
                        .failureHandler((request, response, error) -> write(json, response, 401, ApiResult.failure("A0002", "用户名或密码错误"))))
                .logout(logout -> logout.logoutRequestMatcher(request -> "DELETE".equals(request.getMethod())
                                && "/api/v1/session".equals(request.getServletPath()))
                        .logoutSuccessHandler((request, response, auth) -> write(json, response, 200, ApiResult.success(null))))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, error) -> write(json, response, 401, ApiResult.failure("A0002", "请先登录")))
                        .accessDeniedHandler((request, response, error) -> write(json, response, 403, ApiResult.failure("A0002", "请求校验失败，请刷新后重试"))))
                .build();
    }

    private static void write(JsonMapper json, HttpServletResponse response, int status, Object body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        json.writeValue(response.getOutputStream(), body);
    }
}
