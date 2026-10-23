package com.afklive.streamer.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretBoxTest {

    static final String A = "dGVzdC1rZXktdGVzdC1rZXktdGVzdC1rZXktdGVzdCE=";
    static final String B = "YW5vdGhlci1rZXktYW5vdGhlci1rZXktYW5vdGhlciE=";

    @AfterEach
    void restore() {
        SecretBox.configure(A, List.of()); // the key the Spring tests use
    }

    @Test
    void encryptsWithAFreshIvAndReadsBack() {
        SecretBox.configure(A, List.of());
        String one = SecretBox.encrypt("abcd-efgh");
        String two = SecretBox.encrypt("abcd-efgh");
        assertThat(one).startsWith("enc:v2:").isNotEqualTo(two).doesNotContain("abcd");
        assertThat(SecretBox.decrypt(one)).isEqualTo("abcd-efgh");
        assertThat(SecretBox.encrypt(one)).isEqualTo(one); // never double-encrypted
        assertThat(SecretBox.decrypt("plain-old-value")).isEqualTo("plain-old-value");
    }

    @Test
    void aRotatedKeyStillReadsOldValuesAndWritesWithTheNewOne() {
        SecretBox.configure(A, List.of());
        String old = SecretBox.encrypt("secret");
        SecretBox.configure(B, List.of(A));
        assertThat(SecretBox.decrypt(old)).isEqualTo("secret");
        assertThat(SecretBox.isCurrent(old)).isFalse();
        String fresh = SecretBox.encrypt("secret");
        assertThat(SecretBox.isCurrent(fresh)).isTrue();

        SecretBox.configure(B, List.of()); // old key dropped too early
        assertThatThrownBy(() -> SecretBox.decrypt(old)).hasMessageContaining("APP_ENCRYPTION_OLD_KEYS");
    }

    @Test
    void theAppRefusesToRunWithoutAKey() {
        assertThatThrownBy(() -> SecretBox.configure("", List.of())).hasMessageContaining("APP_ENCRYPTION_KEY");
    }

    @Test
    void cidrMatching() {
        assertThat(Cidr.contains("10.0.0.0/8", "10.20.30.40")).isTrue();
        assertThat(Cidr.contains("10.0.0.0/8", "11.0.0.1")).isFalse();
        assertThat(Cidr.contains("127.0.0.0/8", "127.0.0.1")).isTrue();
        assertThat(Cidr.contains("fc00::/7", "fd12::1")).isTrue();
        assertThat(Cidr.contains("10.0.0.0/8", "example.com")).isFalse();
    }
}
