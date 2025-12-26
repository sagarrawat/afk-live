package com.afklive.streamer.service;

import io.github.bucket4j.Bucket;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

@Service
public class StreamManagerService {
    private final Bucket bucket;
    private final ConcurrentHashMap<String, Boolean> activeStreams = new ConcurrentHashMap<>();

    public StreamManagerService(Bucket bucket) {
        this.bucket = bucket;
    }

    public boolean tryStartStream(String streamId) {
        synchronized (bucket) {
            if (bucket.tryConsume(1)) {
                activeStreams.put(streamId, true);
                return true;
            }
            return false;
        }
    }

    public void endStream(String streamId) {
        synchronized (bucket) {
            if (activeStreams.remove(streamId) != null) {
                bucket.addTokens(1);
            }
        }
    }
}