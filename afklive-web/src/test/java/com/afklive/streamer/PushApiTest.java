package com.afklive.streamer;

import com.afklive.streamer.model.User;
import com.afklive.streamer.repository.PushSubscriptionRepository;
import com.afklive.streamer.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** NT-1 / MB-2: devices subscribe to push alerts; only real browser push services are accepted. */
@SpringBootTest(properties = "app.push.vapid-key=MHcCAQEEIMTgddUrpJVC/Jajs/P93qo5t/fHU5WWrX8lPSkeTTo4oAoGCCqGSM49AwEHoUQDQgAERs1BC6y1UNrA2sbZwI5FPKZzkHUpvq7Nsv36ixOrisW2aY+6563nR1Gh+k/crAVb+a7er3iGZ5PujgFKdsgeoA==") // a throwaway test key
class PushApiTest extends PostgresIntegrationTest {

    // A browser's subscription keys (RFC 8291's example user agent).
    static final String P256DH = "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4";
    static final String AUTH = "BTBZMqHH6r4Tts7J_aSIgg";

    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired PushSubscriptionRepository subscriptions;
    MockMvc mvc;
    String email;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        email = "push-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        users.save(new User(email));
    }

    private String body(String endpoint) {
        return "{\"endpoint\":\"" + endpoint + "\",\"keys\":{\"p256dh\":\"" + P256DH + "\",\"auth\":\"" + AUTH + "\"}}";
    }

    @Test
    void aBrowserTurnsAlertsOnAndOff() throws Exception {
        mvc.perform(get("/api/notifications/push").with(user(email).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.publicKey").isString())
                .andExpect(jsonPath("$.devices").value(0));
        String endpoint = "https://fcm.googleapis.com/fcm/send/" + UUID.randomUUID();
        mvc.perform(post("/api/notifications/push").with(user(email).roles("USER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body(endpoint)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.devices").value(1));
        // The same browser again doesn't count twice.
        mvc.perform(post("/api/notifications/push").with(user(email).roles("USER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body(endpoint)))
                .andExpect(jsonPath("$.devices").value(1));
        mvc.perform(delete("/api/notifications/push").with(user(email).roles("USER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body(endpoint)))
                .andExpect(jsonPath("$.devices").value(0));
    }

    @Test
    void onlyBrowserPushServicesAreEverCalled() throws Exception {
        for (String bad : new String[]{"http://fcm.googleapis.com/x", "https://169.254.169.254/latest", "https://localhost/x",
                "https://fcm.googleapis.com.evil.example/x", "https://fcm.googleapis.com:8443/x", "file:///etc/passwd"}) {
            mvc.perform(post("/api/notifications/push").with(user(email).roles("USER")).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content(body(bad)))
                    .andExpect(status().isBadRequest());
        }
        assertThat(subscriptions.findAllByUserId(users.findByEmail(email).orElseThrow().getId())).isEmpty();
        for (String ok : new String[]{"https://updates.push.services.mozilla.com/wpush/v2/abc", "https://web.push.apple.com/QGx",
                "https://wns2-bl2p.notify.windows.com/w/?token=abc"}) {
            mvc.perform(post("/api/notifications/push").with(user(email).roles("USER")).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content(body(ok)))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void theServiceWorkerIsPublic() throws Exception {
        mvc.perform(get("/sw.js")).andExpect(status().isOk());
    }
}
