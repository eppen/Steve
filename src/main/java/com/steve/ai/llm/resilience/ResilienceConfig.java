package com.steve.ai.llm.resilience;

/**
 * Tuning constants for {@link ResilientLLMClient} (retry, circuit breaker, rate limits).
 */
public final class ResilienceConfig {

    private static final int CIRCUIT_BREAKER_SLIDING_WINDOW_SIZE = 10;
    private static final float CIRCUIT_BREAKER_FAILURE_RATE_THRESHOLD = 50.0f;
    private static final int CIRCUIT_BREAKER_WAIT_DURATION_SECONDS = 30;

    private static final int RETRY_MAX_ATTEMPTS = 3;
    private static final int RETRY_INITIAL_INTERVAL_MS = 1000;

    private static final int RATE_LIMIT_PER_MINUTE = 10;
    private static final int BULKHEAD_MAX_CONCURRENT_CALLS = 5;

    private ResilienceConfig() {}

    public static int getCircuitBreakerSlidingWindowSize() {
        return CIRCUIT_BREAKER_SLIDING_WINDOW_SIZE;
    }

    public static float getCircuitBreakerFailureRateThreshold() {
        return CIRCUIT_BREAKER_FAILURE_RATE_THRESHOLD;
    }

    public static int getCircuitBreakerWaitDurationSeconds() {
        return CIRCUIT_BREAKER_WAIT_DURATION_SECONDS;
    }

    public static int getRetryMaxAttempts() {
        return RETRY_MAX_ATTEMPTS;
    }

    public static int getRetryInitialIntervalMs() {
        return RETRY_INITIAL_INTERVAL_MS;
    }

    public static int getRateLimitPerMinute() {
        return RATE_LIMIT_PER_MINUTE;
    }

    public static int getBulkheadMaxConcurrentCalls() {
        return BULKHEAD_MAX_CONCURRENT_CALLS;
    }
}
