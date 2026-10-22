package com.afklive.streamer;

import com.afklive.streamer.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the whole application against a real (embedded) PostgreSQL with no external secrets,
 * so a broken bean, migration or configuration fails the build instead of a deploy.
 */
@SpringBootTest
class StreamerApplicationTests extends PostgresIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Test
    void contextLoads() {
    }

    @Test
    void productionStartupSeedsNoAccounts() {
        // Dev seed data is in a Liquibase "dev" context and must never run by default.
        assertThat(userRepository.findByEmail("dev@afklive.local")).isEmpty();
        assertThat(userRepository.findByEmail("test@example.com")).isEmpty();
    }
}
