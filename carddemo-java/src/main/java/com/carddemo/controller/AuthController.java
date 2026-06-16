package com.carddemo.controller;

import com.carddemo.model.dto.SignOnRequest;
import com.carddemo.model.dto.SignOnResponse;
import com.carddemo.model.entity.UserSecurity;
import com.carddemo.model.enums.UserType;
import com.carddemo.service.auth.AuthenticationService;
import jakarta.validation.Valid;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authentication REST controller. Re-platforms the CICS sign-on program COSGN00C
 * (BMS mapset COSGN00 / symbolic map COSGN00; reference only, lineage commit
 * 27d6c6f). Exposes a stateless, JWT-issuing sign-in endpoint that supersedes the
 * CARDDEMO-COMMAREA conversational session state.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationService authenticationService;
    private final JwtEncoder jwtEncoder;
    private final long jwtTtlSeconds;

    public AuthController(AuthenticationService authenticationService,
                          JwtEncoder jwtEncoder,
                          @Value("${carddemo.security.jwt.expiration-seconds:3600}") long jwtTtlSeconds) {
        this.authenticationService = authenticationService;
        this.jwtEncoder = jwtEncoder;
        this.jwtTtlSeconds = jwtTtlSeconds;
    }

    @PostMapping("/signin")
    public SignOnResponse signin(@Valid @RequestBody SignOnRequest request) {
        UserSecurity user = authenticationService.authenticate(request.userId(), request.password());
        UserType userType = user.getSecUsrType();
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(jwtTtlSeconds, ChronoUnit.SECONDS);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("carddemo")
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .subject(user.getSecUsrId())
                .claim("userType", userType.name())
                .claim("roles", List.of("ROLE_" + userType.name()))
                .build();
        // Explicitly select HS256 to match the symmetric HMAC signing key configured in
        // SecurityConfig. Without an explicit JWS header, NimbusJwtEncoder defaults to RS256 and
        // fails to select the HMAC key ("Failed to select a JWK signing key"), which would 500 the
        // sign-in endpoint at runtime. (Additional fix surfaced while adding F2 controller tests.)
        JwsHeader jwsHeader = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(jwsHeader, claims)).getTokenValue();
        return new SignOnResponse(token, user.getSecUsrId(), userType, null);
    }
}
