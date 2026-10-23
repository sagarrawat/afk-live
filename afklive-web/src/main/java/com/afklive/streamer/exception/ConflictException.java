package com.afklive.streamer.exception;

/** The request is valid but clashes with the current state (e.g. "already live", "not live"): 409. */
public class ConflictException extends IllegalStateException {
    public ConflictException(String message) {
        super(message);
    }
}
