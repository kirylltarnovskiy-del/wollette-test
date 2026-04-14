package com.ratelimiter.application.config;

import com.ratelimiter.domain.algorithm.SlidingWindowCounterAlgorithm;
import com.ratelimiter.domain.algorithm.TokenBucketAlgorithm;
import com.ratelimiter.domain.port.RateLimiterStatePort;
import io.github.resilience4j.common.circuitbreaker.configuration.CircuitBreakerConfigCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(RateLimiterProperties.class)
public class RateLimiterConfiguration {

    @Bean
    public TokenBucketAlgorithm tokenBucketAlgorithm(RateLimiterStatePort rateLimiterStatePort) {
        return new TokenBucketAlgorithm(rateLimiterStatePort);
    }

    @Bean
    public SlidingWindowCounterAlgorithm slidingWindowCounterAlgorithm(RateLimiterStatePort rateLimiterStatePort) {
        return new SlidingWindowCounterAlgorithm(rateLimiterStatePort);
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
