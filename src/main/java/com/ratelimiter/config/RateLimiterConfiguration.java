package com.ratelimiter.config;

import com.ratelimiter.repository.RedisRateLimiterRepository;
import com.ratelimiter.service.algorithm.SlidingWindowCounterAlgorithm;
import com.ratelimiter.service.algorithm.TokenBucketAlgorithm;
import io.github.resilience4j.common.circuitbreaker.configuration.CircuitBreakerConfigCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(RateLimiterProperties.class)
public class RateLimiterConfiguration {

    @Bean
    public TokenBucketAlgorithm tokenBucketAlgorithm(RedisRateLimiterRepository repository) {
        return new TokenBucketAlgorithm(repository);
    }

    @Bean
    public SlidingWindowCounterAlgorithm slidingWindowCounterAlgorithm(RedisRateLimiterRepository repository) {
        return new SlidingWindowCounterAlgorithm(repository);
    }

    @Bean
    public CircuitBreakerConfigCustomizer redisRateLimiterCircuitBreaker() {
        return CircuitBreakerConfigCustomizer.of(
                "redisRateLimiter",
                builder -> builder
                        .failureRateThreshold(50.0f)
                        .slidingWindowSize(20)
                        .waitDurationInOpenState(java.time.Duration.ofSeconds(10))
                        .minimumNumberOfCalls(10)
                        .permittedNumberOfCallsInHalfOpenState(3)
        );
    }
}
