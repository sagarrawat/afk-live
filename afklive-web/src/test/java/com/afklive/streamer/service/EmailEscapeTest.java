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

    @Test
    void emailFootersLinkTheStatusPage() {
        EmailService email = new EmailService(null);
        ReflectionTestUtils.setField(email, "baseUrl", "https://afklive.in");
        assertThat(email.createBaseHtml("Hi", "<p>Body</p>")).contains("href='https://afklive.in/status'");
    }

    /** UX audit #23: support tickets now come from an in-app form; what a creator types can't inject HTML. */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void supportTicketEmailsEscapeWhatTheCreatorTyped() {
        org.springframework.web.client.RestTemplate rest = org.mockito.Mockito.mock(org.springframework.web.client.RestTemplate.class);
        EmailService email = new EmailService(rest);
        ReflectionTestUtils.setField(email, "baseUrl", "https://afklive.in");
        ReflectionTestUtils.setField(email, "zeptoMailUrl", "https://mail.test/send");
        ReflectionTestUtils.setField(email, "zeptoMailToken", "token");
        ReflectionTestUtils.setField(email, "supportEmail", "support@afklive.in");
        ReflectionTestUtils.setField(email, "noReplyEmail", "noreply@afklive.in");

        email.sendSupportTicket("a@example.com", "Other", "<img src=x onerror=alert(1)>");

        org.mockito.ArgumentCaptor<org.springframework.http.HttpEntity> sent = org.mockito.ArgumentCaptor.forClass(org.springframework.http.HttpEntity.class);
        org.mockito.Mockito.verify(rest).postForObject(org.mockito.ArgumentMatchers.eq("https://mail.test/send"), sent.capture(),
                org.mockito.ArgumentMatchers.eq(String.class));
        String body = String.valueOf(sent.getValue().getBody());
        assertThat(body).contains("&lt;img src=x onerror=alert(1)&gt;").doesNotContain("<img src=x");
    }
}
