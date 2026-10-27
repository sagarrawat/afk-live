package com.afklive.streamer.endpoint;

import com.afklive.streamer.service.BusinessDetails;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * /contact: business details and the grievance officer come from settings; anything not set (or still a
 * placeholder) is left out, and grievances fall back to the support address. Uses the real page source
 * (afklive-ui/site/contact.html, written by site/legal.py), whose data-fill blocks the build keeps as they are.
 */
class ContactPageTest {

    static final String SOURCE = read();

    static String read() {
        try {
            return Files.readString(Path.of("..", "afklive-ui", "site", "contact.html"));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static BusinessDetails details(String name, String address, String gstin, String officer, String officerEmail) {
        return new BusinessDetails(name, address, gstin, officer, officerEmail, "all");
    }

    static String block(String html, String name) {
        Matcher m = Pattern.compile("<div data-fill=\"" + name + "\">(.*?)</div>", Pattern.DOTALL).matcher(html);
        assertThat(m.find()).as(name).isTrue();
        return m.group(1);
    }

    @Test
    void showsEveryConfiguredDetail() {
        BusinessDetails d = details("Rawat Media Private Limited", "12 MG Road\nBengaluru 560001\nKarnataka", "29abcde1234f1z5",
                "Sagar Rawat", "grievance@afklive.in");
        String html = ContactPage.fill(SOURCE, d);
        String business = block(html, "business");
        assertThat(business).contains("<strong>Rawat Media Private Limited</strong>")
                .contains("12 MG Road, Bengaluru 560001, Karnataka")
                .contains("<strong>GSTIN:</strong> 29ABCDE1234F1Z5")
                .contains("mailto:support@afklive.in");
        String grievance = block(html, "grievance");
        assertThat(grievance).contains("<strong>Sagar Rawat</strong>, Grievance Officer")
                .contains("<a href=\"mailto:grievance@afklive.in\">grievance@afklive.in</a>")
                .doesNotContain("support@afklive.in");
        assertThat(d.missing()).isEmpty();
        // The rest of the page is untouched: timelines and the heading the terms link to.
        assertThat(html).contains("<h2 id=\"grievance-redressal\">").contains("within 24 hours").contains("<!-- include:footer -->");
    }

    @Test
    void leavesOutWhatIsNotSetAndSendsGrievancesToSupport() {
        BusinessDetails d = details("AFK Live", "", "", "", "");
        String html = ContactPage.fill(SOURCE, d);
        assertThat(block(html, "business")).contains("<strong>AFK Live</strong><br />Bengaluru, Karnataka, India").doesNotContain("GSTIN");
        assertThat(block(html, "grievance")).contains("write to <a href=\"mailto:support@afklive.in\">support@afklive.in</a>")
                .contains("Grievance&rdquo; in the subject line").doesNotContain("Grievance Officer<br");
        assertThat(d.missing()).containsExactly("APP_GRIEVANCE_OFFICER_NAME", "APP_GRIEVANCE_OFFICER_EMAIL", "APP_BILLING_SELLER_ADDRESS");
    }

    @Test
    void anOfficerWithoutAnEmailUsesTheSupportAddress() {
        String grievance = block(ContactPage.fill(SOURCE, details("AFK Live", "", "", "Sagar Rawat", "not an email")), "grievance");
        assertThat(grievance).contains("<strong>Sagar Rawat</strong>, Grievance Officer")
                .contains("mailto:support@afklive.in").contains("in the subject line");
    }

    @Test
    void neverPublishesPlaceholders() {
        BusinessDetails d = details("XXXX", "TODO: registered address", "29XXXXX0000X1Z5", "${APP_GRIEVANCE_OFFICER_NAME}", "test@test.com");
        String html = ContactPage.fill(SOURCE, d);
        assertThat(html).doesNotContain("XXXX").doesNotContain("TODO").doesNotContain("${").doesNotContain("test@test.com");
        assertThat(block(html, "business")).contains("<strong>AFK Live</strong>").doesNotContain("GSTIN");
        assertThat(details("AFK Live", "", "", "Name", "officer@example.com").officerEmail()).isEmpty();
        assertThat(details("AFK Live", "", "29ABCDE1234F1Z", "", "").gstin()).as("one character short").isEmpty();
    }

    @Test
    void escapesWhatItShows() {
        String html = ContactPage.fill(SOURCE, details("Rawat & Sons", "Shop 4, \"Lakeview\", Bengaluru", "", "D'Souza", ""));
        assertThat(block(html, "business")).contains("Rawat &amp; Sons").contains("&quot;Lakeview&quot;");
        assertThat(block(html, "grievance")).contains("D&#39;Souza");
    }

    @Test
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void warnsAtStartupWhenDetailsAreMissing(org.springframework.boot.test.system.CapturedOutput out) {
        details("AFK Live", "", "", "", "officer@afklive.in").warnIfIncomplete();
        assertThat(out.getOut()).contains("/contact) is missing APP_GRIEVANCE_OFFICER_NAME, APP_BILLING_SELLER_ADDRESS");
        int before = out.getOut().length();
        new BusinessDetails("AFK Live", "", "", "", "", "worker").warnIfIncomplete(); // workers don't serve pages
        details("AFK Live", "1 Road, Bengaluru", "", "Sagar Rawat", "officer@afklive.in").warnIfIncomplete();
        assertThat(out.getOut().substring(before)).doesNotContain("/contact)");
    }

    @Test
    void servesTheBuiltPageFilledIn() {
        ResponseEntity<String> r = new ContactPage(details("AFK Live", "", "", "Sagar Rawat", "grievance@afklive.in")).contact();
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getHeaders().getContentType().toString()).isEqualTo("text/html;charset=UTF-8");
        assertThat(r.getHeaders().getCacheControl()).isEqualTo("no-cache, public");
        assertThat(r.getBody()).contains("<strong>Sagar Rawat</strong>, Grievance Officer").contains("mailto:grievance@afklive.in");
    }
}
