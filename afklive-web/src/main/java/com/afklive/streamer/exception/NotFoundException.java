package com.afklive.streamer.exception;

/** A resource that doesn't exist or isn't the caller's: 404 (the two are indistinguishable on purpose). */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
