package com.ratelimiter.controller;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@SuppressWarnings("resource")
class RateLimiterIntegrationTest {

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7.2-alpine")
            .withExposedPorts(6379);

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void redisProps(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @TestConfiguration
    static class TestRedisConfiguration {
        @Bean
        @Primary
        @SuppressWarnings("resource")
        RedisConnectionFactory redisConnectionFactory() {
            RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration(
                    redis.getHost(),
                    redis.getMappedPort(6379)
            );
            return new LettuceConnectionFactory(standalone);
        }
    }

    @Test
    void requestAndStatsEndpointsAreAvailable() throws Exception {
        String payload = """
                {"userId":"free-user","endpoint":"/payments"}
                """;
        mockMvc.perform(post("/request")
                        .contentType("application/json")
                        .content(payload))
                .andExpect(status().is2xxSuccessful());

        mockMvc.perform(get("/stats"))
                .andExpect(status().isOk());
    }

    @Test
    void rateLimitIsEnforced() throws Exception {
        String payload = """
                {"userId":"burst-test-user","endpoint":"/api"}
                """;

        // FREE tier TOKEN_BUCKET: maxRequests=10, burstCapacity=15 per second.
        // Firing 20 rapid requests must produce at least one 429.
        int rejected = 0;
        for (int i = 0; i < 20; i++) {
            MvcResult result = mockMvc.perform(post("/request")
                            .contentType("application/json")
                            .content(payload))
                    .andReturn();
            if (result.getResponse().getStatus() == 429) {
                rejected++;
            }
        }
        assertTrue(rejected > 0, "Expected at least one 429 after exceeding burst capacity of 15");
    }

    @Test
    void userUsageEndpointReturnsData() throws Exception {
        String userId = "usage-tracked-user";
        String payload = String.format("""
                {"userId":"%s","endpoint":"/api"}
                """, userId);

        mockMvc.perform(post("/request")
                        .contentType("application/json")
                        .content(payload))
                .andExpect(status().is2xxSuccessful());

        // Analytics are written asynchronously via Redis Streams.
        // Wait until the stream consumer processes the event and the usage is visible.
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                mockMvc.perform(get("/users/" + userId + "/usage"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.userId").value(userId))
                        .andExpect(jsonPath("$.total").value(greaterThan(0)))
        );
    }
}
