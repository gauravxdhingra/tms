package com.tms.platform.idempotency;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Servlet filter that enforces idempotency on POST/PATCH/DELETE endpoints.
 * Callers must supply the Idempotency-Key header.
 *
 * If the key is IN_FLIGHT:  returns 409 Conflict.
 * If the key is COMPLETED:  returns 200 with cached body (replay).
 * If the key is NEW:        allows the request through; response is cached on completion.
 *
 * Note: response caching for replay requires wrapping the response — services
 * that need replay (vs. just dedup) should use @IdempotentService directly.
 * This filter provides the dedup guarantee only.
 */
@Component
public class IdempotentRequestFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(IdempotentRequestFilter.class);
    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final IdempotencyStore store;

    public IdempotentRequestFilter(IdempotencyStore store) {
        this.store = store;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String method = request.getMethod();
        if (!"POST".equals(method) && !"PATCH".equals(method) && !"DELETE".equals(method)) {
            filterChain.doFilter(request, response);
            return;
        }

        String idempotencyKey = request.getHeader(IDEMPOTENCY_KEY_HEADER);
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }

        IdempotencyStore.IdempotencyState state = store.getState(idempotencyKey);

        if (state == IdempotencyStore.IdempotencyState.IN_FLIGHT) {
            log.warn("Idempotency key {} is in-flight, rejecting duplicate", idempotencyKey);
            response.setStatus(HttpStatus.CONFLICT.value());
            response.getWriter().write("{\"error\":\"Request with this Idempotency-Key is already processing\"}");
            return;
        }

        if (state == IdempotencyStore.IdempotencyState.COMPLETED) {
            log.debug("Idempotency key {} already completed, returning 200 (dedup — no body replay at filter level)", idempotencyKey);
            response.setStatus(HttpStatus.OK.value());
            response.getWriter().write("{\"idempotent\":true}");
            return;
        }

        // New key — claim it and proceed
        boolean claimed = store.claimKey(idempotencyKey);
        if (!claimed) {
            // Race condition — another thread claimed it first
            response.setStatus(HttpStatus.CONFLICT.value());
            return;
        }

        try {
            filterChain.doFilter(request, response);
        } catch (Exception e) {
            store.releaseKey(idempotencyKey);
            throw e;
        }
    }
}
