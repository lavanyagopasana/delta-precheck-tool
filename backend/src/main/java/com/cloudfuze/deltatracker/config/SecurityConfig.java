package com.cloudfuze.deltatracker.config;

import com.cloudfuze.deltatracker.entity.AppUserRole;
import com.cloudfuze.deltatracker.service.AppUserService;
import com.cloudfuze.deltatracker.util.JwtEmailUtil;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * failure. The sign-off endpoints (/api/combinations/{id}/signoffs/**) are further restricted to the
 * ADMIN, MIGRATION_MANAGER, DEV_LEAD, and QA_LEAD roles
 * regardless of azure.require-allowlist -- MIGRATION_ENGINEER keeps access to the rest of the app
 * but not sign-off. CSV import (/api/pairs/import, /api/servers/{id}/pairs/import) is restricted to
 * ADMIN, MIGRATION_ENGINEER, and MIGRATION_MANAGER, same regardless-of-bypass rule. Managing the
 * allowlist itself (Manage Access) is also ADMIN-only,
 * enforced in AppUserService.requireAdmin(). Viewing the pre-check form (GET on
 * /api/combinations/{id}/precheck-items/**, /api/combinations/{id}/precheck-submission/**) is open
 * to anyone on the allowlist, regardless of role. Filling it out (any other method on those same
 * paths -- updating an item, checking all, submitting) stays restricted to MIGRATION_ENGINEER and
 * MIGRATION_MANAGER only -- notably, ADMIN is NOT included there, unlike the sign-off and
 * CSV-import rules above. Pre-check/sign-off/Delta lifecycle are per-combination, not per-server --
 * see WorkspaceCombination.
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

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

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

    /**
     * Logs the two settings that decide whether a deployed frontend can talk to this backend at all.
     * Both fail silently when wrong: a blank client-id makes every /api/** route permitAll (the app
     * starts and serves happily, just with no authentication -- see .claude/rules/security-rules.md),
     * and a CORS list that doesn't contain the real frontend origin surfaces in the browser as an
     * opaque network error rather than anything identifiable in the logs. Printing both at startup
     * means a misconfigured deploy is diagnosable from the log alone, instead of only from the
     * symptom. Deliberately logs the effective values, not the raw env vars, so a default that
     * quietly applied is as visible as one that was set explicitly.
     */
    @PostConstruct
    void logEffectiveAuthAndCorsConfig() {
        List<String> origins = parseAllowedOrigins();
        if (authConfigured()) {
            log.info("Auth ENABLED: client-id={}, tenant-id={}, allowed-email-domain={}",
                    clientId,
                    StringUtils.hasText(tenantId) ? tenantId : "(any -- multi-tenant)",
                    StringUtils.hasText(allowedEmailDomain) ? allowedEmailDomain : "(none)");
        } else {
            log.warn("Auth DISABLED -- azure.client-id is blank, so every /api/** route is permitAll "
                    + "and /api/me returns a null email for everyone. Set AZURE_CLIENT_ID before "
                    + "exposing this anywhere real.");
        }
        log.info("CORS allowed origins for /api/**: {}", origins);
        if (origins.size() == 1 && origins.get(0).contains("localhost")) {
            log.warn("CORS is still at its localhost-only default. A deployed frontend on any other "
                    + "origin will be blocked by the browser -- set APP_ALLOWED_ORIGINS to that "
                    + "origin (comma-separated for more than one). Harmless for local development.");
        }
    }

    /**
     * Split out so the filter chain and the startup log can't disagree about what the effective list
     * is. Blank entries are dropped rather than passed through -- a trailing comma in
     * APP_ALLOWED_ORIGINS would otherwise register "" as an allowed origin.
     */
    private List<String> parseAllowedOrigins() {
        return Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(parseAllowedOrigins());
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
                        // PMO's server calls this directly -- there is no Azure AD token to check, so
                        // this route is permitAll here and instead guarded by PmoWebhookService's own
                        // X-API-Key check. Deliberately NOT under /api/pmo/** (that's ADMIN-JWT-gated
                        // below, for the human-triggered manual sync).
                        .requestMatchers(HttpMethod.POST, "/api/webhooks/pmo/delta-phase").permitAll()
                        .requestMatchers("/api/me").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/combinations/*/signoffs/**", "/api/signoff-approvals")
                                .access(allowlistRequired())
                        .requestMatchers("/api/combinations/*/signoffs/**").access(roleRequired(
                                AppUserRole.ADMIN, AppUserRole.MIGRATION_MANAGER, AppUserRole.DEV_LEAD, AppUserRole.QA_LEAD))
                        .requestMatchers(HttpMethod.POST, "/api/pairs/import", "/api/servers/*/pairs/import").access(roleRequired(
                                AppUserRole.ADMIN, AppUserRole.MIGRATION_ENGINEER, AppUserRole.MIGRATION_MANAGER))
                        // Edit history and CSV upload history: GET only, any allowlisted caller. These
                        // trails exist to be read -- restricting them to whoever may make the edit
                        // would defeat the disclosure they are for. Listed ahead of the write rules
                        // below so a role gate on editing never narrows who may READ the trail.
                        .requestMatchers(HttpMethod.GET, "/api/projects/*/history", "/api/projects/deleted",
                                "/api/servers/*/history", "/api/combinations/*/history",
                                "/api/servers/*/pair-imports")
                                .access(allowlistRequired())
                        // Deleting a combination's pairs -- admins and managers. Engineers can still
                        // IMPORT (above) but not clear the list, which is the destructive half.
                        .requestMatchers(HttpMethod.DELETE, "/api/servers/*/pairs").access(roleRequired(
                                AppUserRole.ADMIN, AppUserRole.MIGRATION_MANAGER))
                        // Creating a Server directly (the "Server URL" add flow) -- same role set as
                        // CSV import, since it's an alternate way of doing the same thing a CSV row does.
                        .requestMatchers(HttpMethod.POST, "/api/projects/*/servers").access(roleRequired(
                                AppUserRole.ADMIN, AppUserRole.MIGRATION_ENGINEER, AppUserRole.MIGRATION_MANAGER))
                        // Editing a Server's product type -- same role set as creating one.
                        .requestMatchers(HttpMethod.PATCH, "/api/servers/*").access(roleRequired(
                                AppUserRole.ADMIN, AppUserRole.MIGRATION_ENGINEER, AppUserRole.MIGRATION_MANAGER))
                        // Post-Delta lifecycle (Start / Finish the migration) -- engineer-driven, admins too.
                        // Per-combination now, not per-server. Finishing a Final Delta is what ends a
                        // combination for good, so this is also the path that makes a server
                        // decommissionable; the decommission action itself is admin-only, below.
                        .requestMatchers(HttpMethod.POST, "/api/combinations/*/delta/**").access(roleRequired(
                                AppUserRole.ADMIN, AppUserRole.MIGRATION_ENGINEER))
                        // Delta history (per-cycle snapshots) is read-only audit data -- visible to
                        // anyone on the allowlist, same as viewing a pre-check form. Stated explicitly
                        // rather than left to the anyRequest() default, per .claude/rules/api-conventions.md.
                        .requestMatchers(HttpMethod.GET, "/api/combinations/*/delta-cycles")
                                .access(allowlistRequired())
                        // Decommissioning a server -- admins and Migration Managers, who own delivery for
                        // their projects. It permanently erases the server and everything under it, so this
                        // is the most destructive route in the app; also re-checked in
                        // ServerService.requireAdminOrManager so the rule survives a routing change, and the
                        // all-Final-Deltas-complete guard still stops it erasing in-flight work. Listed
                        // before the PATCH/DELETE /api/servers/* rules below so it can't be widened by them.
                        .requestMatchers(HttpMethod.POST, "/api/servers/*/decommission").access(roleRequired(
                                AppUserRole.ADMIN, AppUserRole.MIGRATION_MANAGER))
                        // Deleting a server (admins and managers, anytime) -- same cascade as decommission
                        // but WITHOUT the all-Final-Deltas-complete guard, so this one can erase in-flight
                        // work. Also re-checked in ServerService.
                        .requestMatchers(HttpMethod.DELETE, "/api/servers/*").access(roleRequired(
                                AppUserRole.ADMIN, AppUserRole.MIGRATION_MANAGER))
                        // Deleting a project is gated here to the roles that could ever be allowed; the
                        // per-project ownership check (creator / managing MM / admin) and the
                        // Delta-initiated audit guard are enforced in ProjectService.delete.
                        .requestMatchers(HttpMethod.DELETE, "/api/projects/*").access(roleRequired(
                                AppUserRole.ADMIN, AppUserRole.MIGRATION_MANAGER, AppUserRole.MIGRATION_ENGINEER))
                        // Reading Metabase's database list (what the project page's dropdown offers).
                        // Any allowlisted caller: it is a list of database names, every caller is
                        // internal staff, and the DEV_LEAD/QA_LEAD approvers who may not SET a
                        // project's database still see it on the project page. Stated explicitly
                        // rather than left to the anyRequest() default, per
                        // .claude/rules/api-conventions.md.
                        .requestMatchers(HttpMethod.GET, "/api/metabase/databases")
                                .access(allowlistRequired())
                        // Reading a project's Metabase status. Read-only, so any allowlisted caller --
                        // the DEV_LEAD/QA_LEAD approvers who may not CHOOSE the database are exactly
                        // the people who need to read the figures they are approving against.
                        .requestMatchers(HttpMethod.GET, "/api/projects/*/metabase-status")
                                .access(allowlistRequired())
                        // Setting a project's Metabase database name. Needs its own matcher: a single
                        // "*" matches one path segment, so the /api/projects/* rule below never covers
                        // /api/projects/{id}/metabase and this would otherwise fall through to the
                        // allowlist-only default. Same role set as editing a project; the per-project
                        // check (admin / managing MM / assigned engineer -- deliberately NOT the
                        // DEV_LEAD/QA_LEAD approvers) is in ProjectService.updateMetabaseDatabaseName.
                        .requestMatchers(HttpMethod.PATCH, "/api/projects/*/metabase").access(roleRequired(
                                AppUserRole.ADMIN, AppUserRole.MIGRATION_MANAGER, AppUserRole.MIGRATION_ENGINEER))
                        // Removing one is admins and managers, stated here as well as in the service:
                        // it subtracts from figures a Delta was already approved against, and the
                        // route should not depend on the service check alone to say so.
                        .requestMatchers(HttpMethod.DELETE, "/api/projects/*/metabase")
                                .access(roleRequired(AppUserRole.ADMIN, AppUserRole.MIGRATION_MANAGER))
                        // Edit project details -- gated to roles that can ever edit; the per-project
                        // check (admin / current MM / creator / assigned engineer) is in ProjectService.
                        .requestMatchers(HttpMethod.PATCH, "/api/projects/*").access(roleRequired(
                                AppUserRole.ADMIN, AppUserRole.MIGRATION_MANAGER, AppUserRole.MIGRATION_ENGINEER))
                        // Includes the per-item edit trail (GET .../precheck-items/{id}/history):
                        // it is disclosure, so every allowlisted caller sees it -- including the Dev
                        // and QA Leads who approve an item they cannot edit, who are precisely the
                        // people the trail exists for now that managers can fill a form they approve.
                        .requestMatchers(HttpMethod.GET, "/api/combinations/*/precheck-items/**", "/api/combinations/*/precheck-submission/**")
                                .access(allowlistRequired())
                        // Filling out and submitting a pre-check: MIGRATION_ENGINEER, ADMIN and -- as of
                        // 2026-09-03 -- MIGRATION_MANAGER. DEV_LEAD/QA_LEAD were never here.
                        //
                        // The manager was deliberately EXCLUDED from 2026-08-06 until then, because they are
                        // the first approver in the chain and filling in the form they then approve collapses
                        // two steps into one person. That risk has not gone away; it is now accepted and
                        // mitigated by disclosure instead of prohibition -- every edit is recorded with its
                        // author and role in precheck_item_edits and shown to everyone on the item, so a
                        // manager approving their own entries is visible to whoever looks rather than
                        // prevented. Reverting this means reverting that trail's purpose too.
                        //
                        // ADMIN additionally holds the per-action bypasses in the services (the "someone else
                        // is filling this in" ownership lock, and the post-submission lock).
                        .requestMatchers("/api/combinations/*/precheck-items/**", "/api/combinations/*/precheck-submission/**")
                                .access(roleRequired(AppUserRole.ADMIN, AppUserRole.MIGRATION_ENGINEER,
                                        AppUserRole.MIGRATION_MANAGER))
                        // Teams: any allowlisted caller may READ them, because the project dashboard
                        // needs team membership to scope its engineer picker and every role opens that
                        // page. Every WRITE is ADMIN-only -- team membership decides which engineers a
                        // manager can assign, so letting a manager edit teams would let them widen
                        // their own pool. TeamController.requireAdmin repeats the check as
                        // defence-in-depth, matching AdminController.
                        // Triggering the PMO project sync is ADMIN-only: it creates projects in bulk,
                        // and the arriving projects need an admin to attach a Migration Manager before
                        // anyone can work them anyway (PmoSyncService's class comment explains why the
                        // manager can't be taken from PMO). PmoController.requireAdmin repeats the
                        // check as defence in depth, matching AdminController.
                        .requestMatchers("/api/pmo/**").access(roleRequired(AppUserRole.ADMIN))
                        .requestMatchers(HttpMethod.GET, "/api/teams").access(allowlistRequired())
                        .requestMatchers("/api/teams/**", "/api/teams").access(roleRequired(AppUserRole.ADMIN))
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
