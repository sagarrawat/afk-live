package com.afklive.streamer.streaming;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Where a worker reads a phone's feed: built from the worker's own configuration, so the ingest
 * server's read credentials never go into the database. */
@Component
public class IngestReader {

    private final String template;
    private final String user;
    private final String pass;

    public IngestReader(@Value("${app.ingest.read-url:rtmp://127.0.0.1:1935/live/{key}?user={user}&pass={pass}}") String template,
                        @Value("${app.ingest.read-user:worker}") String user,
                        @Value("${app.ingest.read-pass:}") String pass) {
        this.template = template;
        this.user = user;
        this.pass = pass;
    }

    public String readUrl(String ingestKey) {
        if (ingestKey == null || !ingestKey.matches("[A-Za-z0-9]{8,64}")) {
            throw new IllegalArgumentException("Invalid ingest key");
        }
        return template.replace("{key}", ingestKey)
                .replace("{user}", URLEncoder.encode(user, StandardCharsets.UTF_8))
                .replace("{pass}", URLEncoder.encode(pass, StandardCharsets.UTF_8));
    }
}
