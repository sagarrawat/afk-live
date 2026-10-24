package com.afklive.streamer.exception;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    @Test
    void keepsOurOwnMessages() {
        assertThat(GlobalExceptionHandler.userFacing("Storage quota exceeded. Limit: 1024MB."))
                .isEqualTo("Storage quota exceeded. Limit: 1024MB.");
        assertThat(GlobalExceptionHandler.userFacing("Please paste a link from drive.google.com or docs.google.com."))
                .isEqualTo("Please paste a link from drive.google.com or docs.google.com.");
    }

    @Test
    void hidesFrameworkMessages() {
        assertThat(GlobalExceptionHandler.userFacing("No enum constant com.afklive.streamer.model.PlanType.X"))
                .isEqualTo("Invalid request.");
        assertThat(GlobalExceptionHandler.userFacing("For input string: \"abc\"")).isEqualTo("Invalid request.");
        assertThat(GlobalExceptionHandler.userFacing("Cannot invoke \"java.lang.String.length()\" because \"s\" is null"))
                .isEqualTo("Invalid request.");
        assertThat(GlobalExceptionHandler.userFacing(null)).isEqualTo("Invalid request.");
    }

    /** OPS-2: an unexpected error says "our side" and carries the request's code, which is in the logs. */
    @Test
    void anUnexpectedErrorBlamesUsAndCarriesTheRequestCode() {
        org.slf4j.MDC.put(com.afklive.streamer.filter.RequestIdFilter.MDC_KEY, "abcd2345");
        try {
            var r = new GlobalExceptionHandler().handleRuntimeException(new RuntimeException("db down"));
            assertThat(r.getStatusCode().value()).isEqualTo(500);
            assertThat(r.getBody()).containsEntry("requestId", "abcd2345");
            assertThat(String.valueOf(r.getBody().get("message"))).contains("our side").contains("abcd2345").doesNotContain("db down");
        } finally {
            org.slf4j.MDC.remove(com.afklive.streamer.filter.RequestIdFilter.MDC_KEY);
        }
        // A validation problem is the request's fault: no "our side".
        var bad = new GlobalExceptionHandler().handleBadRequest(new IllegalArgumentException("Say why."));
        assertThat(String.valueOf(bad.getBody().get("message"))).doesNotContain("our side");
    }
}
