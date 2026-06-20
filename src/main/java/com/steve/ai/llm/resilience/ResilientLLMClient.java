package com.steve.ai.llm.resilience;

import com.steve.ai.llm.async.AsyncLLMClient;
import com.steve.ai.llm.async.LLMCache;
import com.steve.ai.llm.async.LLMException;
import com.steve.ai.llm.async.LLMResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Decorator that adds caching, retry, and a simple circuit breaker to an {@link AsyncLLMClient}.
 * Uses only JDK primitives so no third-party resilience library is required at runtime.
 */
public class ResilientLLMClient implements AsyncLLMClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(ResilientLLMClient.class);

    public enum CircuitState {
        CLOSED, OPEN, HALF_OPEN
    }

    private final AsyncLLMClient delegate;
    private final LLMCache cache;
    private final LLMFallbackHandler fallbackHandler;
    private final Semaphore bulkhead;
    private final AtomicInteger windowCalls = new AtomicInteger(0);
    private final AtomicInteger windowFailures = new AtomicInteger(0);
    private final AtomicLong windowStartedMs = new AtomicLong(System.currentTimeMillis());
    private final AtomicLong rateWindowStartedMs = new AtomicLong(System.currentTimeMillis());
    private final AtomicInteger rateWindowCalls = new AtomicInteger(0);

    private volatile CircuitState circuitState = CircuitState.CLOSED;
    private volatile long circuitOpenUntilMs = 0;

    public ResilientLLMClient(AsyncLLMClient delegate, LLMCache cache, LLMFallbackHandler fallbackHandler) {
        this.delegate = delegate;
        this.cache = cache;
        this.fallbackHandler = fallbackHandler;
        this.bulkhead = new Semaphore(ResilienceConfig.getBulkheadMaxConcurrentCalls(), true);
        LOGGER.info("Initializing resilient client for provider: {}", delegate.getProviderId());
    }

    @Override
    public CompletableFuture<LLMResponse> sendAsync(String prompt, Map<String, Object> params) {
        String model = (String) params.getOrDefault("model", "unknown");
        String providerId = delegate.getProviderId();

        Optional<LLMResponse> cached = cache.get(prompt, model, providerId);
        if (cached.isPresent()) {
            LOGGER.debug("[{}] Cache hit for prompt (hash: {})", providerId, prompt.hashCode());
            return CompletableFuture.completedFuture(cached.get());
        }

        if (!acquireRatePermit(providerId)) {
            return CompletableFuture.completedFuture(
                fallbackHandler.generateFallback(prompt, new LLMException("Rate limit exceeded", LLMException.ErrorType.RATE_LIMIT, providerId, true)));
        }

        if (!tryAcquireBulkhead(providerId)) {
            return CompletableFuture.completedFuture(
                fallbackHandler.generateFallback(prompt, new LLMException("Bulkhead full", LLMException.ErrorType.CLIENT_ERROR, providerId, true)));
        }

        if (isCircuitOpen()) {
            bulkhead.release();
            return CompletableFuture.completedFuture(
                fallbackHandler.generateFallback(prompt, new LLMException("Circuit breaker open", LLMException.ErrorType.SERVER_ERROR, providerId, false)));
        }

        return executeWithRetry(prompt, params, 0)
            .whenComplete((response, error) -> bulkhead.release());
    }

    private CompletableFuture<LLMResponse> executeWithRetry(String prompt, Map<String, Object> params, int attempt) {
        String providerId = delegate.getProviderId();
        String model = (String) params.getOrDefault("model", "unknown");

        return delegate.sendAsync(prompt, params)
            .thenApply(response -> {
                recordSuccess();
                cache.put(prompt, model, providerId, response);
                LOGGER.debug("[{}] Request successful (latency: {}ms, tokens: {})",
                    providerId, response.getLatencyMs(), response.getTokensUsed());
                return response;
            })
            .exceptionallyCompose(throwable -> {
                Throwable cause = unwrap(throwable);
                recordFailure();

                if (attempt + 1 < ResilienceConfig.getRetryMaxAttempts() && shouldRetry(cause)) {
                    long delayMs = ResilienceConfig.getRetryInitialIntervalMs() * (1L << attempt);
                    LOGGER.warn("[{}] Retry attempt {} after {}ms (reason: {})",
                        providerId, attempt + 1, delayMs, cause.getMessage());
                    return delayed(delayMs).thenCompose(v -> executeWithRetry(prompt, params, attempt + 1));
                }

                LOGGER.error("[{}] Request failed after retries, using fallback: {}", providerId, cause.getMessage());
                return CompletableFuture.completedFuture(fallbackHandler.generateFallback(prompt, cause));
            });
    }

    private boolean shouldRetry(Throwable throwable) {
        if (throwable instanceof IOException || throwable instanceof TimeoutException) {
            return true;
        }
        if (throwable instanceof LLMException) {
            return ((LLMException) throwable).isRetryable();
        }
        return false;
    }

    private void recordSuccess() {
        resetWindowIfExpired();
        windowCalls.incrementAndGet();
        if (circuitState == CircuitState.HALF_OPEN) {
            circuitState = CircuitState.CLOSED;
            windowFailures.set(0);
            windowCalls.set(0);
            LOGGER.info("[{}] Circuit breaker closed after successful probe", delegate.getProviderId());
        }
    }

    private void recordFailure() {
        resetWindowIfExpired();
        windowCalls.incrementAndGet();
        windowFailures.incrementAndGet();

        int calls = windowCalls.get();
        int failures = windowFailures.get();
        if (calls >= ResilienceConfig.getCircuitBreakerSlidingWindowSize()) {
            float failureRate = (failures * 100.0f) / calls;
            if (failureRate >= ResilienceConfig.getCircuitBreakerFailureRateThreshold()) {
                circuitState = CircuitState.OPEN;
                circuitOpenUntilMs = System.currentTimeMillis()
                    + ResilienceConfig.getCircuitBreakerWaitDurationSeconds() * 1000L;
                LOGGER.warn("[{}] Circuit breaker opened (failure rate {}%)", delegate.getProviderId(), failureRate);
            }
            windowCalls.set(0);
            windowFailures.set(0);
            windowStartedMs.set(System.currentTimeMillis());
        }
    }

    private void resetWindowIfExpired() {
        long elapsed = System.currentTimeMillis() - windowStartedMs.get();
        if (elapsed > ResilienceConfig.getCircuitBreakerWaitDurationSeconds() * 1000L) {
            windowCalls.set(0);
            windowFailures.set(0);
            windowStartedMs.set(System.currentTimeMillis());
        }
    }

    private boolean isCircuitOpen() {
        if (circuitState == CircuitState.OPEN) {
            if (System.currentTimeMillis() >= circuitOpenUntilMs) {
                circuitState = CircuitState.HALF_OPEN;
                LOGGER.info("[{}] Circuit breaker half-open", delegate.getProviderId());
                return false;
            }
            return true;
        }
        return false;
    }

    private boolean acquireRatePermit(String providerId) {
        long now = System.currentTimeMillis();
        if (now - rateWindowStartedMs.get() > TimeUnit.MINUTES.toMillis(1)) {
            rateWindowStartedMs.set(now);
            rateWindowCalls.set(0);
        }
        if (rateWindowCalls.incrementAndGet() > ResilienceConfig.getRateLimitPerMinute()) {
            LOGGER.warn("[{}] Rate limit exceeded ({} req/min)", providerId, ResilienceConfig.getRateLimitPerMinute());
            return false;
        }
        return true;
    }

    private boolean tryAcquireBulkhead(String providerId) {
        try {
            if (!bulkhead.tryAcquire(5, TimeUnit.SECONDS)) {
                LOGGER.warn("[{}] Bulkhead full (max concurrent: {})",
                    providerId, ResilienceConfig.getBulkheadMaxConcurrentCalls());
                return false;
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static CompletableFuture<Void> delayed(long delayMs) {
        return CompletableFuture.runAsync(
            () -> {},
            CompletableFuture.delayedExecutor(delayMs, TimeUnit.MILLISECONDS)
        );
    }

    private static Throwable unwrap(Throwable throwable) {
        if (throwable instanceof CompletionException && throwable.getCause() != null) {
            return throwable.getCause();
        }
        return throwable;
    }

    @Override
    public String getProviderId() {
        return delegate.getProviderId();
    }

    @Override
    public boolean isHealthy() {
        return circuitState != CircuitState.OPEN;
    }

    public CircuitState getCircuitBreakerState() {
        return circuitState;
    }

    public void resetCircuitBreaker() {
        circuitState = CircuitState.CLOSED;
        circuitOpenUntilMs = 0;
        windowCalls.set(0);
        windowFailures.set(0);
        LOGGER.info("[{}] Circuit breaker manually reset to CLOSED", delegate.getProviderId());
    }
}
