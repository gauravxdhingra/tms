package com.tms.common.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Redis-backed store for idempotency key → cached response pairs.
 *
 * A two-phase state machine per key:
 *   1. IN_FLIGHT  — key set, but processing not yet complete
 *   2. COMPLETED  — key + serialized response stored
 *
 * If a duplicate arrives while IN_FLIGHT, the caller gets a 409 Conflict.
 * If a duplicate arrives while COMPLETED, the caller gets the cached response.
 */
@Component
public class IdempotencyStore {

    private static final String KEY_PREFIX     = "idem:";
    private static final String IN_FLIGHT_VAL  = "__IN_FLIGHT__";
    private static final Duration IN_FLIGHT_TTL = Duration.ofMinutes(5);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Duration completedTtl;

    public IdempotencyStore(StringRedisTemplate redis,
                            ObjectMapper objectMapper,
                            IdempotencyProperties properties) {
        this.redis         = redis;
        this.objectMapper  = objectMapper;
        this.completedTtl  = properties.getKeyTtl();
    }

    /**
     * Attempt to claim an idempotency key.
     * Returns true if the key was freshly claimed (proceed with processing).
     * Returns false if the key already exists (duplicate — inspect state with getState()).
     */
    public boolean claimKey(String idempotencyKey) {
        Boolean set = redis.opsForValue()
                           .setIfAbsent(KEY_PREFIX + idempotencyKey, IN_FLIGHT_VAL, IN_FLIGHT_TTL);
        return Boolean.TRUE.equals(set);
    }

    public IdempotencyState getState(String idempotencyKey) {
        String value = redis.opsForValue().get(KEY_PREFIX + idempotencyKey);
        if (value == null)              return IdempotencyState.NOT_FOUND;
        if (IN_FLIGHT_VAL.equals(value)) return IdempotencyState.IN_FLIGHT;
        return IdempotencyState.COMPLETED;
    }

    public <T> void storeResponse(String idempotencyKey, T response) {
        try {
            String json = objectMapper.writeValueAsString(response);
            redis.opsForValue().set(KEY_PREFIX + idempotencyKey, json, completedTtl);
        } catch (JsonProcessingException e) {
            throw new IdempotencyException("Failed to serialize idempotency response", e);
        }
    }

    public <T> Optional<T> getCachedResponse(String idempotencyKey, Class<T> responseType) {
        String json = redis.opsForValue().get(KEY_PREFIX + idempotencyKey);
        if (json == null || IN_FLIGHT_VAL.equals(json)) return Optional.empty();
        try {
            return Optional.of(objectMapper.readValue(json, responseType));
        } catch (JsonProcessingException e) {
            throw new IdempotencyException("Failed to deserialize cached idempotency response", e);
        }
    }

    public void releaseKey(String idempotencyKey) {
        redis.delete(KEY_PREFIX + idempotencyKey);
    }

    public enum IdempotencyState { NOT_FOUND, IN_FLIGHT, COMPLETED }
}
