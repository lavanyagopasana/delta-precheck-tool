package com.cloudfuze.deltatracker.config;

import com.cloudfuze.deltatracker.entity.AppUserRole;
import com.cloudfuze.deltatracker.service.AppUserService;
import com.cloudfuze.deltatracker.util.JwtEmailUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.util.StringUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Auth stays fully open (permitAll) until AZURE_CLIENT_ID is set, so the app keeps working during
 * local setup before the Entra ID app registration exists. Once set, every /api/** request must
 * carry a valid Microsoft ID token, AND (except for /api/me) that account's email must be in the
 * AppUser allowlist -- an admin has to explicitly add you before you can use anything else. If
 * azure.allowed-email-domain is set, tokens are additionally restricted to that email domain
 * (currently blank/off for testing -- see application.properties). /api/me stays reachable to any
 * valid token so the frontend can show a clear "pending approval" state instead of a generic
 * failure. The sign-off endpoints (/api/servers/{id}/signoffs/**) are further restricted to the
 * ADMIN, MIGRATION_MANAGER, DEV_LEAD, and QA_LEAD roles
 * regardless of azure.require-allowlist -- MIGRATION_ENGINEER keeps access to the rest of the app
 * but not sign-off. CSV import (/api/pairs/import, /api/servers/{id}/pairs/import) is restricted to
 * ADMIN, MIGRATION_ENGINEER, and MIGRATION_MANAGER, same regardless-of-bypass rule. Managing the
 * allowlist itself (Manage Access) is also ADMIN-only,
 * enforced in AppUserService.requireAdmin(). Viewing the pre-check form (GET on
 * /api/servers/{id}/precheck-items/**, /api/servers/{id}/precheck-submission/**) is open to anyone
 * on the allowlist, regardless of role. Filling it out (any other method on those same paths --
 * updating an item, checking all, submitting) stays restricted to MIGRATION_ENGINEER and
 * MIGRATION_MANAGER only -- notably, ADMIN is NOT included there, unlike the sign-off and
 * CSV-import rules above.
 *
 * Works with either a single-tenant or multi-tenant app registration. If azure.tenant-id is set,
 * the issuer must match that exact tenant (single-tenant registration). If it's blank, any Entra
 * ID tenant's issuer is accepted by pattern (multi-tenant registration), and the email-domain
 * check below is what actually restricts access. Either way, token signatures are validated
 * against Microsoft's shared JWKS endpoint (the signing keys aren't tenant-specific).
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final String JWK_SET_URI = "https://login.microsoftonline.com/organizations/discovery/v2.0/keys";
    private static final Pattern ISSUER_PATTERN =
            Pattern.compile("^https://login\\.microsoftonline\\.com/[0-9a-fA-F-]{36}/v2\\.0$");

    private final AppUserService appUserService;

    @Value("${azure.client-id:}")
    private String clientId;

    @Value("${azure.tenant-id:}")
    private String tenantId;

    // Blank = no domain restriction (temporary, for testing with non-cloudfuze.com accounts).
    // Set AZURE_ALLOWED_EMAIL_DOMAIN=cloudfuze.com to re-enable the restriction. The AppUser
    // allowlist still applies regardless -- this only controls who can get a valid token at all.
    @Value("${azure.allowed-email-domain:}")
    private String allowedEmailDomain;

    // Comma-separated list so a deployed frontend origin can be added without touching code --
    // set APP_ALLOWED_ORIGINS in production (localhost:3000 stays the default for local dev). Owned
    // here rather than in WebConfig because it must be wired into the Security filter chain via
    // .cors(...) -- a WebMvcConfigurer-only CORS registration never runs for requests Spring
    // Security itself rejects (401/403), which otherwise surface to the browser as an opaque CORS
    // failure instead of a readable auth error.
    @Value("${app.allowed-origins:http://localhost:3000}")
    private String allowedOrigins;

    public SecurityConfig(AppUserService appUserService) {
        this.appUserService = appUserService;
    }

    private boolean authConfigured() {
        return StringUtils.hasText(clientId);
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(Arrays.asList(allowedOrigins.split("\\s*,\\s*")));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        if (!authConfigured()) {
            http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
            return http.build();
        }

        http.authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/uploads/**").permitAll()
                        .requestMatchers("/api/me").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/servers/*/signoffs/**", "/api/signoff-approvals")
                                .access(allowlistRequired())
                        .requestMatchers("/api/servers/*/signoffs/**").access(roleRequired(
                                AppUserRole.ADMIN, AppUserRole.MIGRATION_MANAGER, AppUserRole.DEV_LEAD, AppUserRole.QA_LEAD))
                        .requestMatchers(HttpMethod.POST, "/api/pairs/import", "/api/servers/*/pairs/import").access(roleRequired(
                                AppUserRole.ADMIN, AppUserRole.MIGRATION_ENGINEER, AppUserRole.MIGRATION_MANAGER))
                        // Deleting a project is gated here to the roles that could ever be allowed; the
                        // per-project ownership check (creator / managing MM / admin) and the
                        // Delta-initiated audit guard are enforced in ProjectService.delete.
                        .requestMatchers(HttpMethod.DELETE, "/api/projects/*").access(roleRequired(
                                AppUserRole.ADMIN, AppUserRole.MIGRATION_MANAGER, AppUserRole.MIGRATION_ENGINEER))
                        // Edit project details -- gated to roles that can ever edit; the per-project
                        // check (admin / current MM / creator / assigned engineer) is in ProjectService.
                        .requestMatchers(HttpMethod.PATCH, "/api/projects/*").access(roleRequired(
                                AppUserRole.ADMIN, AppUserRole.MIGRATION_MANAGER, AppUserRole.MIGRATION_ENGINEER))
                        .requestMatchers(HttpMethod.GET, "/api/servers/*/precheck-items/**", "/api/servers/*/precheck-submission/**")
                                .access(allowlistRequired())
                        // ADMIN included here by explicit product decision -- admins have full access
                        // to everything, including filling out/submitting/withdrawing pre-checks. The
                        // per-action admin bypasses live in the services (ownership lock, submitted lock).
                        .requestMatchers("/api/servers/*/precheck-items/**", "/api/servers/*/precheck-submission/**")
                                .access(roleRequired(AppUserRole.ADMIN, AppUserRole.MIGRATION_ENGINEER, AppUserRole.MIGRATION_MANAGER))
                        .anyRequest().access(allowlistRequired()))
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.decoder(azureJwtDecoder())));

        return http.build();
    }

    private AuthorizationManager<RequestAuthorizationContext> allowlistRequired() {
        return (authentication, context) -> {
            if (!(authentication.get() instanceof JwtAuthenticationToken jwtAuth)) {
                return new AuthorizationDecision(false);
            }
            String email = JwtEmailUtil.extractEmail(jwtAuth.getToken());
            return new AuthorizationDecision(appUserService.isAllowed(email));
        };
    }

    private AuthorizationManager<RequestAuthorizationContext> roleRequired(AppUserRole... allowedRoles) {
        return (authentication, context) -> {
            if (!(authentication.get() instanceof JwtAuthenticationToken jwtAuth)) {
                return new AuthorizationDecision(false);
            }
            String email = JwtEmailUtil.extractEmail(jwtAuth.getToken());
            boolean granted = email != null && appUserService.roleOf(email)
                    .map(role -> {
                        for (AppUserRole allowed : allowedRoles) {
                            if (role == allowed) {
                                return true;
                            }
                        }
                        return false;
                    })
                    .orElse(false);
            return new AuthorizationDecision(granted);
        };
    }

    private NimbusJwtDecoder azureJwtDecoder() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(JWK_SET_URI).build();

        OAuth2TokenValidator<Jwt> issuerValidator = token -> {
            String issuer = token.getIssuer() != null ? token.getIssuer().toString() : null;
            boolean valid;
            if (StringUtils.hasText(tenantId)) {
                valid = issuer != null && issuer.equals("https://login.microsoftonline.com/" + tenantId + "/v2.0");
            } else {
                valid = issuer != null && ISSUER_PATTERN.matcher(issuer).matches();
            }
            return valid
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Unexpected issuer", null));
        };

        OAuth2TokenValidator<Jwt> audienceValidator = token ->
                token.getAudience().contains(clientId)
                        ? OAuth2TokenValidatorResult.success()
                        : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Unexpected audience", null));

        OAuth2TokenValidator<Jwt> domainValidator = token -> {
            if (!StringUtils.hasText(allowedEmailDomain)) {
                return OAuth2TokenValidatorResult.success();
            }
            String email = JwtEmailUtil.extractEmail(token);
            boolean valid = StringUtils.hasText(email)
                    && email.toLowerCase().endsWith("@" + allowedEmailDomain.toLowerCase());
            return valid
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token",
                            "Account is not a @" + allowedEmailDomain + " account", null));
        };

        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(), issuerValidator, audienceValidator, domainValidator));

        return decoder;
    }
}
