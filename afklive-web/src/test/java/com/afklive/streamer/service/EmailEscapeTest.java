package com.afklive.streamer.service;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class EmailEscapeTest {

    @Test
    void streamNamesCantInjectHtmlIntoAlertEmails() {
        assertThat(EmailService.escape("<script>alert('x')</script> & \"y\""))
                .isEqualTo("&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt; &amp; &quot;y&quot;");
    }

    @Test
    void emailsCarryTheBrandLogoAsAnImageMailClientsCanShow() {
        EmailService email = new EmailService(null);
        ReflectionTestUtils.setField(email, "baseUrl", "https://afklive.in");
        String html = email.createBaseHtml("Hi", "<p>Body</p>");
        assertThat(html).contains("https://afklive.in/site/brand/logo-email.png").contains("alt='AFK Live'")
                .doesNotContain("#667eea").doesNotContain("<svg");
    }
}
