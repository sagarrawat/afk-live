package com.afklive.streamer;

import com.afklive.streamer.model.Notification;
import com.afklive.streamer.model.PlanType;
import com.afklive.streamer.model.User;
import com.afklive.streamer.repository.UserRepository;
import com.afklive.streamer.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OPS-4: during a deploy two web slots run side by side, each with its own timers (alerts, billing,
 * schedules). Their passes can overlap to the millisecond, so anything they both do must still happen
 * once. Most work is claimed with a conditional update; alerts relied on "already sent?" followed by
 * an insert, which two instances could both pass.
 */
@SpringBootTest
class TwoWebInstancesTest extends PostgresIntegrationTest {

    @Autowired NotificationService notifications;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;

    @Test
    void anAlertWithADedupeKeyIsSentOnceEvenWhenTwoInstancesSendItAtTheSameMoment() throws Exception {
        User u = new User("twoweb-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com");
        u.setPlanType(PlanType.FREE);
        u.setEmailAlerts(false); // no mail in tests
        User owner = users.save(u);

        int instances = 6;
        int rounds = 15;
        ExecutorService pool = Executors.newFixedThreadPool(instances);
        try {
            for (int round = 0; round < rounds; round++) {
                String key = "ITEM_FAILED:" + round;
                CyclicBarrier together = new CyclicBarrier(instances);
                List<Future<?>> sent = new ArrayList<>();
                for (int i = 0; i < instances; i++) {
                    sent.add(pool.submit(() -> {
                        together.await(10, TimeUnit.SECONDS);
                        return notifications.notify(owner, new NotificationService.Alert(NotificationService.Types.ITEM_FAILED,
                                Notification.Level.WARNING, null, "A file was skipped", "body", "/studio",
                                key, Duration.ofDays(1), true));
                    }));
                }
                for (Future<?> f : sent) {
                    f.get(30, TimeUnit.SECONDS);
                }
            }
        } finally {
            pool.shutdownNow();
        }

        List<Integer> perKey = jdbc.queryForList(
                "SELECT count(*)::int FROM notifications WHERE user_id = ? GROUP BY dedupe_key ORDER BY dedupe_key",
                Integer.class, owner.getId());
        assertThat(perKey).hasSize(rounds).allMatch(n -> n == 1);
    }
}
