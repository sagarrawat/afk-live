package com.afklive.streamer.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * Cloudflare Turnstile for the public title writer (MK-7): only when {@code APP_TURNSTILE_SECRET} and
 * {@code APP_TURNSTILE_SITE_KEY} are both set. Without them it's skipped and the per-visitor and daily limits
 * are the only guard. The secret is sent in the request body to Cloudflare and never logged.
 */
@Service
@Slf4j
public class TurnstileVerifier {

    static final String URL = "https://challenges.cloudflare.com/turnstile/v0/siteverify";

    private final String secret;
    private final String siteKey;
    private final RestTemplate http;

    @Autowired
    public TurnstileVerifier(@Value("${app.turnstile.secret:}") String secret, @Value("${app.turnstile.site-key:}") String siteKey) {
        this(secret, siteKey, AiService.timed());
    }

    TurnstileVerifier(String secret, String siteKey, RestTemplate http) {
        this.secret = secret == null ? "" : secret.strip();
        this.siteKey = siteKey == null ? "" : siteKey.strip();
        this.http = http;
    }

    /** True when both keys are set: the page shows the widget and every request must pass it. */
    public boolean enabled() {
        return !secret.isEmpty() && !siteKey.isEmpty();
    }

    /** The public site key for the page, or null when Turnstile is off. */
    public String siteKey() {
        return enabled() ? siteKey : null;
    }

    /** True when Turnstile is off, or Cloudflare says this token is good. A failed check (or no answer) is false. */
    @SuppressWarnings("rawtypes")
    public boolean verify(String token, String ip) {
        if (!enabled()) {
            return true;
        }
        if (token == null || token.isBlank() || token.length() > 2048) {
            return false;
        }
        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("secret", secret);
            form.add("response", token);
            if (ip != null) {
                form.add("remoteip", ip);
            }
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            Map r = http.postForObject(URL, new HttpEntity<>(form, headers), Map.class);
            return r != null && Boolean.TRUE.equals(r.get("success"));
        } catch (Exception e) {
            log.warn("Turnstile check failed: {}", e.getClass().getSimpleName());
            return false;
        }
    }
}
