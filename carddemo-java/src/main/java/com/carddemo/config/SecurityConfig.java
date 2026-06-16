package com.carddemo.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
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

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * Spring Security configuration for the CardDemo Java platform: the BCrypt password encoder, the
 * HMAC (HS256) JWT encoder/decoder, and the stateless HTTP security filter chain. Lineage
 * (REFERENCE ONLY): replaces the CICS sign-on program {@code COSGN00C} (transaction {@code CC00})
 * plaintext {@code SEC-USR-PWD} check ({@code CSUSR01Y}) with BCrypt and the
 * {@code CARDDEMO-COMMAREA} conversational state with stateless JWTs; AWS CardDemo commit
 * {@code 27d6c6f}.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * HMAC signing secret resolved from configuration (env {@code JWT_SECRET} in the base and local
     * profiles; a test-only default in {@code application-test.yml}). It is never hardcoded and
     * never logged.
     */
    @Value("${carddemo.security.jwt.secret:}")
    private String jwtSecret;

    /**
     * Builds the symmetric key used for HS256 token signing and verification.
     *
     * @return an {@code HmacSHA256} {@link SecretKey} derived from the configured secret
     * @throws IllegalStateException if the secret is missing, blank, or shorter than 32 characters,
     *                               since HS256 requires a key of at least 256 bits; the message
     *                               intentionally excludes the secret value
     */
    private SecretKey hmacKey() {
        if (jwtSecret == null || jwtSecret.isBlank() || jwtSecret.length() < 32) {
            throw new IllegalStateException(
                    "carddemo.security.jwt.secret must be set (>=32 chars); provide via JWT_SECRET env");
        }
        return new SecretKeySpec(jwtSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    /**
     * BCrypt password encoder (constraint C-003). It is the sole {@link PasswordEncoder} bean in
     * the application and replaces the {@code COSGN00C} plaintext password comparison while
     * preserving the sign-on flow.
     *
     * @return a {@link BCryptPasswordEncoder}
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * JWT encoder that signs tokens with the HS256 HMAC key. It is consumed by the authentication
     * controller to mint access tokens after a successful sign-on.
     *
     * @return a {@link NimbusJwtEncoder} backed by the configured secret
     */
    @Bean
    public JwtEncoder jwtEncoder() {
        return new NimbusJwtEncoder(new ImmutableSecret<>(hmacKey()));
    }

    /**
     * JWT decoder that validates inbound bearer tokens with the HS256 HMAC key. It is used by the
     * OAuth2 resource server filter to authenticate API requests.
     *
     * @return a {@link NimbusJwtDecoder} configured for {@link MacAlgorithm#HS256}
     */
    @Bean
    public JwtDecoder jwtDecoder() {
        return NimbusJwtDecoder.withSecretKey(hmacKey()).macAlgorithm(MacAlgorithm.HS256).build();
    }

    /**
     * Maps the JWT {@code roles} claim, whose values already carry the {@code ROLE_} prefix, to
     * Spring Security authorities without adding a further prefix; this keeps {@code hasRole("ADMIN")}
     * aligned with the authority {@code ROLE_ADMIN} and avoids a doubled {@code ROLE_ROLE_} prefix.
     *
     * @return a {@link JwtAuthenticationConverter} that reads the {@code roles} claim verbatim
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
     * Stateless HTTP security filter chain. CSRF protection is disabled (token-based REST), CORS
     * delegates to the {@code corsConfigurationSource} bean when one is present, no HTTP session is
     * created, and inbound bearer tokens are validated by the OAuth2 resource server using
     * {@link #jwtDecoder()} with the {@code roles}-claim authority mapping.
     *
     * @param http the {@link HttpSecurity} builder to configure
     * @return the built {@link SecurityFilterChain}
     * @throws Exception if the security configuration cannot be built
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/signin").permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus")
                        .permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt ->
                        jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));
        return http.build();
    }
}
