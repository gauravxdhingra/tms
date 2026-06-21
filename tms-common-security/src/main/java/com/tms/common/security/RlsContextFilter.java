package com.tms.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Sets the Postgres session variable used by Row-Level Security policies.
 * Must run AFTER the Spring Security filter chain so the JWT is already parsed.
 *
 * Each service's RLS policies filter reads/writes:
 *   CREATE POLICY entity_isolation ON payments
 *     USING (legal_entity_id = current_setting('app.legal_entity_id'));
 *
 * The SET LOCAL scopes the variable to the current transaction only,
 * so connection pool reuse cannot leak data between entities.
 */
@Component
public class RlsContextFilter extends OncePerRequestFilter {

    private final TmsUserContext userContext;
    private final JdbcTemplate   jdbcTemplate;

    public RlsContextFilter(TmsUserContext userContext, JdbcTemplate jdbcTemplate) {
        this.userContext  = userContext;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            String legalEntityId = userContext.getLegalEntityId();
            // SET LOCAL is transaction-scoped; safe with connection pooling
            jdbcTemplate.execute("SET LOCAL app.legal_entity_id = '" +
                legalEntityId.replace("'", "''") + "'");
        } catch (UnauthenticatedException ignored) {
            // Unauthenticated requests are rejected by Spring Security before reaching services
        }

        filterChain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator") || path.startsWith("/error");
    }
}
