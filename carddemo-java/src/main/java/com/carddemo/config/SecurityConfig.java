package com.carddemo.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Spring Security backbone for the CardDemo migration: a BCrypt {@link PasswordEncoder}, an HMAC
 * (HS256) {@link JwtEncoder}/{@link JwtDecoder} pair, and a stateless HTTP {@link SecurityFilterChain}.
 * Lineage: AWS CardDemo sign-on program {@code COSGN00C} plus user-security copybook
 * {@code CSUSR01Y} (source commit {@code 27d6c6f}); REFERENCE ONLY, no COBOL is transcribed.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * Minimum HMAC key length in bytes. HS256 mandates a key of at least 256 bits (32 bytes); a
     * shorter key is rejected at startup to prevent an insecure signing/verification configuration.
     */
    private static final int MIN_SECRET_LENGTH_BYTES = 32;

    /**
     * Symmetric signing secret resolved from configuration (env {@code JWT_SECRET} in the base and
     * local profiles, a fixed test value in {@code application-test.yml}). The empty default keeps
     * context refresh deferred until {@link #hmacKey()} enforces the fail-fast contract; the value is
     * never hardcoded and never logged.
     */
    @Value("${carddemo.security.jwt.secret:}")
    private String jwtSecret;

    /**
     * BCrypt password encoder that replaces the COBOL plaintext password comparison while preserving
     * the original sign-on flow (constraint C-003). Sole definer of the {@link PasswordEncoder} bean
     * consumed by the authentication service.
     *
     * @return a BCrypt-backed {@link PasswordEncoder}
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * JWT encoder used by the authentication controller to mint signed bearer tokens. Backed by the
     * symmetric HMAC key so tokens are self-contained and verifiable without server-held session state.
     *
     * @return a Nimbus-backed HS256 {@link JwtEncoder}
     */
    @Bean
    public JwtEncoder jwtEncoder() {
        return new NimbusJwtEncoder(new ImmutableSecret<>(hmacKey()));
    }

    /**
     * JWT decoder used by the OAuth2 resource server to validate inbound bearer tokens. Configured
     * explicitly (rather than auto-configured) because the secret lives under the custom
     * {@code carddemo.security.jwt.secret} property key.
     *
     * @return a Nimbus-backed HS256 {@link JwtDecoder}
     */
    @Bean
    public JwtDecoder jwtDecoder() {
        return NimbusJwtDecoder.withSecretKey(hmacKey()).macAlgorithm(MacAlgorithm.HS256).build();
    }

    /**
     * Stateless HTTP security filter chain. CSRF is disabled (token-based REST), CORS defers to the
     * {@code corsConfigurationSource} bean when present, no HTTP session is created, and authorization
     * is path-based: the sign-on endpoint and the permitted actuator probes are public, the admin API
     * requires {@code ROLE_ADMIN}, and every other API call requires an authenticated principal.
     * Inbound tokens are validated by the OAuth2 resource server using the roles-claim converter.
     *
     * @param http the {@link HttpSecurity} builder supplied by Spring Security
     * @return the configured {@link SecurityFilterChain}
     * @throws Exception if the filter chain cannot be built
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .cors(Customizer.withDefaults())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/signin").permitAll()
                .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .requestMatchers("/api/**").authenticated()
                .anyRequest().authenticated())
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));
        return http.build();
    }

    /**
     * Builds the converter that turns the JWT {@code roles} claim into granted authorities. The claim
     * values already carry the {@code ROLE_} prefix, so the authority prefix is cleared to avoid a
     * doubled {@code ROLE_ROLE_} authority; with this mapping {@code hasRole("ADMIN")} matches the
     * {@code ROLE_ADMIN} authority.
     *
     * @return a {@link JwtAuthenticationConverter} bound to the {@code roles} claim
     */
    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authoritiesConverter = new JwtGrantedAuthoritiesConverter();
        authoritiesConverter.setAuthoritiesClaimName("roles");
        authoritiesConverter.setAuthorityPrefix("");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authoritiesConverter);
        return converter;
    }

    /**
     * Derives the symmetric HMAC-SHA256 signing key from the configured secret, failing fast when the
     * secret is missing, blank, or shorter than the HS256 minimum. The thrown message never includes
     * the secret value.
     *
     * @return the {@link SecretKey} used for both token signing and verification
     * @throws IllegalStateException if the secret is absent, blank, or under 32 bytes
     */
    private SecretKey hmacKey() {
        if (jwtSecret == null || jwtSecret.isBlank()
                || jwtSecret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_LENGTH_BYTES) {
            throw new IllegalStateException(
                "carddemo.security.jwt.secret must be set (>=32 chars); provide via JWT_SECRET env");
        }
        return new SecretKeySpec(jwtSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }
}
