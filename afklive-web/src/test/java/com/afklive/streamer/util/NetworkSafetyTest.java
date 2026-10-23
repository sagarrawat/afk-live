package com.afklive.streamer.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NetworkSafetyTest {

    @AfterEach
    void resetResolver() {
        NetworkSafety.setResolverForTests(null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abcd-efgh-ijkl-mnop-qrst", "key", "live_123.abc"})
    void acceptsBareStreamKeys(String key) {
        assertThatCode(() -> NetworkSafety.requireSafeStreamDestination(key)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"key with spaces", "key;rm -rf", "a/b", "file:/etc/passwd"})
    void rejectsBareKeysWithOddCharacters(String key) {
        assertThatThrownBy(() -> NetworkSafety.requireSafeStreamDestination(key))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"rtmp://8.8.8.8/live/key", "rtmps://1.1.1.1:443/app/key", "srt://8.8.4.4:9000?streamid=x"})
    void acceptsPublicStreamUrls(String url) {
        assertThatCode(() -> NetworkSafety.requireSafeStreamDestination(url)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "rtmp://127.0.0.1/live",           // loopback
            "rtmp://10.0.0.5/live",            // private
            "rtmp://172.16.0.5/live",          // private
            "rtmp://192.168.1.10/live",        // private
            "rtmp://169.254.169.254/latest",   // cloud metadata (link-local)
            "rtmp://100.64.0.1/live",          // carrier-grade NAT
            "rtmp://0.0.0.0/live",             // any-local
            "rtmp://[::1]/live",               // IPv6 loopback
            "rtmp://[fd00::1]/live",           // IPv6 unique local
            "rtmp://[::ffff:127.0.0.1]/live",  // IPv4-mapped loopback
            "rtmp://[::127.0.0.1]/live",       // IPv4-compatible loopback
            "rtmp://[64:ff9b::a9fe:a9fe]/live",// NAT64 to cloud metadata
            "rtmp://[2002:7f00:1::]/live",     // 6to4 embedding 127.0.0.1
            "rtmp://[2001::1]/live",           // Teredo
            "rtmp://[2001:db8::1]/live",       // documentation
            "rtmp://[100::1]/live",            // discard-only
            "rtmp://192.0.2.10/live",          // documentation
            "rtmp://198.51.100.10/live",       // documentation
            "rtmp://203.0.113.10/live"         // documentation
    })
    void rejectsPrivateAndLocalAddresses(String url) {
        assertThatThrownBy(() -> NetworkSafety.requireSafeStreamDestination(url))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Private or local network");
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://8.8.8.8/live", "file:///etc/passwd", "gopher://8.8.8.8/x"})
    void rejectsNonStreamingSchemes(String url) {
        assertThatThrownBy(() -> NetworkSafety.requireSafeStreamDestination(url))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsHostnamesThatResolveToPrivateAddresses() {
        NetworkSafety.setResolverForTests(host -> new InetAddress[]{InetAddress.getByName("10.1.2.3")});
        assertThatThrownBy(() -> NetworkSafety.requireSafeStreamDestination("rtmp://sneaky.example.com/live"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Private or local network");
    }

    @Test
    void rejectsHostnamesWhenAnyResolvedAddressIsPrivate() {
        NetworkSafety.setResolverForTests(host -> new InetAddress[]{
                InetAddress.getByName("8.8.8.8"), InetAddress.getByName("127.0.0.1")});
        assertThatThrownBy(() -> NetworkSafety.requireSafeWebUrl("https://mixed.example.com/video"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsHostnamesThatResolveToPublicAddresses() {
        NetworkSafety.setResolverForTests(host -> new InetAddress[]{InetAddress.getByName("142.250.0.1")});
        assertThatCode(() -> NetworkSafety.requireSafeWebUrl("https://www.youtube.com/watch?v=abc"))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsGlobalIpv6() {
        assertThatCode(() -> NetworkSafety.requireSafeStreamDestination("rtmp://[2607:f8b0:4005:80a::200e]/live"))
                .doesNotThrowAnyException();
    }

    @Test
    void srtMustBeCallerMode() {
        assertThatThrownBy(() -> NetworkSafety.requireSafeStreamDestination("srt://8.8.8.8:9000?mode=listener"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void webUrlsMustBeHttp() {
        assertThatThrownBy(() -> NetworkSafety.requireSafeWebUrl("ftp://8.8.8.8/file"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
