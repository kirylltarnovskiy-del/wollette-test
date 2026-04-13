package com.ratelimiter.benchmark;

import com.ratelimiter.domain.algorithm.RateLimitAlgorithm;
import com.ratelimiter.domain.algorithm.SlidingWindowCounterAlgorithm;
import com.ratelimiter.domain.algorithm.TokenBucketAlgorithm;
import com.ratelimiter.domain.model.AlgorithmType;
import com.ratelimiter.domain.model.RateLimitRule;
import com.ratelimiter.domain.port.RateLimiterStatePort;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(java.util.concurrent.TimeUnit.SECONDS)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@Fork(1)
public class AlgorithmBenchmark {

    private final RateLimitRule tokenRule = new RateLimitRule("per-second", Duration.ofSeconds(1), 1000, 1500, AlgorithmType.TOKEN_BUCKET);
    private final RateLimitRule windowRule = new RateLimitRule("per-minute", Duration.ofMinutes(1), 50_000, 50_000, AlgorithmType.SLIDING_WINDOW_COUNTER);
    private final RateLimitAlgorithm token = new TokenBucketAlgorithm(new LocalStatePort());
    private final RateLimitAlgorithm counter = new SlidingWindowCounterAlgorithm(new LocalStatePort());

    @Benchmark
    @Threads(8)
    public boolean tokenBucket() {
        return token.allowRequest("bench-user:/request", tokenRule);
    }

    @Benchmark
    @Threads(8)
    public boolean slidingWindowCounter() {
        return counter.allowRequest("bench-user:/request", windowRule);
    }

    private static class LocalStatePort implements RateLimiterStatePort {
        private final Map<String, Long> tokenState = new ConcurrentHashMap<>();
        private final Map<String, Long> counter = new ConcurrentHashMap<>();

        @Override
        public boolean allowTokenBucket(String key, RateLimitRule rule, Instant now) {
            return tokenState.merge(key, rule.burstCapacity() - 1, (a, b) -> a > 0 ? a - 1 : -1) >= 0;
        }

        @Override
        public boolean allowSlidingWindowCounter(String key, RateLimitRule rule, Instant now) {
            long next = counter.merge(key, 1L, Long::sum);
            return next <= rule.maxRequests();
        }
    }
}
