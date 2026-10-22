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
}
