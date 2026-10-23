package com.afklive.streamer.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EmailEscapeTest {

    @Test
    void streamNamesCantInjectHtmlIntoAlertEmails() {
        assertThat(EmailService.escape("<script>alert('x')</script> & \"y\""))
                .isEqualTo("&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt; &amp; &quot;y&quot;");
    }
}
