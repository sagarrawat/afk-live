package com.afklive.streamer.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** NT-3. */
class AlertPreferencesTest {

    @Test
    void everythingIsOnUntilTheCreatorTurnsItOff() {
        assertThat(AlertPreferences.read(null).values()).allMatch(c -> c.email() && c.push());
        assertThat(AlertPreferences.read("not json")).hasSize(AlertPreferences.KINDS.size());
        String json = AlertPreferences.write(Map.of("PHONE", new AlertPreferences.Channels(false, true)));
        assertThat(AlertPreferences.forType(json, NotificationService.Types.PHONE_LOST)).isEqualTo(new AlertPreferences.Channels(false, true));
        assertThat(AlertPreferences.forType(json, NotificationService.Types.STREAM_DOWN)).isEqualTo(new AlertPreferences.Channels(true, true));
        assertThat(AlertPreferences.forType(json, "SOMETHING_NEW")).isEqualTo(new AlertPreferences.Channels(true, true));
    }

    @Test
    void everyAlertTypeBelongsToAKind() throws Exception {
        for (var f : NotificationService.Types.class.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) && f.getType() == String.class) {
                String type = (String) f.get(null);
                assertThat(AlertPreferences.KINDS.stream().anyMatch(k -> k.types().contains(type))).as(type).isTrue();
            }
        }
    }
}
