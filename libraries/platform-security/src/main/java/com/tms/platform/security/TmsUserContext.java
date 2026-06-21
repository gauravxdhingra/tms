package com.tms.platform.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Extracts TMS-specific claims from the JWT in the current request context.
 *
 * Keycloak puts roles in realm_access.roles[].
 * The legalEntityId comes from a custom claim set by the Keycloak mapper.
 * Services call this instead of touching SecurityContextHolder directly.
 */
@Component
public class TmsUserContext {

    public String getUserId() {
        return getJwt().map(jwt -> jwt.getClaimAsString("sub"))
                       .orElseThrow(() -> new UnauthenticatedException("No authenticated user in context"));
    }

    public String getLegalEntityId() {
        return getJwt().map(jwt -> jwt.getClaimAsString("legal_entity_id"))
                       .orElseThrow(() -> new UnauthenticatedException("No legal_entity_id claim in JWT"));
    }

    public List<String> getRoles() {
        return getJwt().map(jwt -> {
            var realmAccess = jwt.getClaimAsMap("realm_access");
            if (realmAccess == null) return List.<String>of();
            @SuppressWarnings("unchecked")
            var roles = (List<String>) realmAccess.get("roles");
            return roles != null ? roles : List.<String>of();
        }).orElse(List.of());
    }

    public boolean hasRole(String role) {
        return getRoles().contains(role);
    }

    public Optional<String> getCorrelationId() {
        return getJwt().map(jwt -> jwt.getClaimAsString("correlation_id"));
    }

    private Optional<Jwt> getJwt() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
            return Optional.empty();
        }
        return Optional.of(jwt);
    }
}
