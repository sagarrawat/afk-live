package com.afklive.streamer.endpoint;

import com.afklive.streamer.exception.NotFoundException;
import com.afklive.streamer.service.BusinessDetails;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.util.HtmlUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * /contact: the site's contact page (afklive-ui/site/legal.py builds it like the other legal pages) with
 * the business details and grievance officer filled in from the server's settings ({@link BusinessDetails}),
 * so they're set per environment without rebuilding the site. The built page holds a
 * {@code <div data-fill="business">} and a {@code <div data-fill="grievance">} with what to show when a
 * setting is empty; this replaces their contents. Details that aren't set are left out, never shown as
 * placeholders.
 */
@Controller
@Slf4j
public class ContactPage {

    static final String FILE = "static/site/contact.html";
    private static final Pattern FILL = Pattern.compile("(<div data-fill=\"(business|grievance)\">)(.*?)(</div>)", Pattern.DOTALL);

    private final BusinessDetails details;
    private volatile String page;

    public ContactPage(BusinessDetails details) {
        this.details = details;
    }

    @GetMapping("/contact")
    public ResponseEntity<String> contact() {
        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                .cacheControl(CacheControl.noCache().cachePublic())
                .body(page());
    }

    private String page() {
        String p = page;
        if (p == null) {
            ClassPathResource built = new ClassPathResource(FILE);
            if (!built.exists()) {
                log.error("{} is missing: was the site built?", FILE);
                throw new NotFoundException("Page not found.");
            }
            try (InputStream in = built.getInputStream()) {
                p = fill(new String(in.readAllBytes(), StandardCharsets.UTF_8), details);
            } catch (IOException e) {
                throw new IllegalStateException("Could not read " + FILE, e);
            }
            page = p; // the settings and the built file don't change while the app runs
        }
        return p;
    }

    /** The built page with its {@code data-fill} blocks filled from {@code details}. */
    public static String fill(String html, BusinessDetails details) {
        Matcher m = FILL.matcher(html);
        StringBuilder out = new StringBuilder();
        int filled = 0;
        while (m.find()) {
            String inner = "business".equals(m.group(2)) ? business(details) : grievance(details);
            m.appendReplacement(out, Matcher.quoteReplacement(m.group(1) + inner + m.group(4)));
            filled++;
        }
        m.appendTail(out);
        if (filled != 2) {
            log.warn("The contact page had {} of its 2 data-fill blocks; check afklive-ui/site/legal.py.", filled);
        }
        return out.toString();
    }

    static String business(BusinessDetails d) {
        StringBuilder p = new StringBuilder("<p><strong>")
                .append(esc(d.name().orElse("AFK Live")))
                .append("</strong><br />")
                .append(esc(d.address().orElse("Bengaluru, Karnataka, India")))
                .append("</p>");
        d.gstin().ifPresent(g -> p.append("<p><strong>GSTIN:</strong> ").append(esc(g)).append("</p>"));
        p.append("<p><strong>Email:</strong> ").append(mailto(BusinessDetails.SUPPORT_EMAIL)).append("</p>");
        return p.toString();
    }

    static String grievance(BusinessDetails d) {
        String email = d.officerEmail().orElse(BusinessDetails.SUPPORT_EMAIL);
        String subject = d.officerEmail().isPresent() ? "" : " with &ldquo;Grievance&rdquo; in the subject line";
        if (d.officerName().isPresent()) {
            return "<p><strong>" + esc(d.officerName().get()) + "</strong>, Grievance Officer<br />"
                    + mailto(email) + subject + "</p>";
        }
        return "<p><strong>Grievance officer:</strong> write to " + mailto(email) + subject + ".</p>";
    }

    private static String mailto(String email) {
        String e = esc(email);
        return "<a href=\"mailto:" + e + "\">" + e + "</a>";
    }

    private static String esc(String s) {
        return HtmlUtils.htmlEscape(s, "UTF-8");
    }
}
