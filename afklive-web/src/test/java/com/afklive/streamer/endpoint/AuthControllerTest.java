package com.afklive.streamer.endpoint;

import com.afklive.streamer.model.User;
import com.afklive.streamer.repository.UserRepository;
import com.afklive.streamer.service.EmailService;
import com.afklive.streamer.service.PlanService;
import com.afklive.streamer.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.NoOpPasswordEncoder;
import org.springframework.ui.ExtendedModelMap;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** OPS-1: links in emails use the configured public address; with email off nobody is told a link was sent. */
class AuthControllerTest {

    final UserRepository users = mock(UserRepository.class);
    final EmailService email = mock(EmailService.class);
    final UserService userService = new UserService(users, NoOpPasswordEncoder.getInstance(), email, mock(PlanService.class));

    private AuthController controller(String baseUrl) {
        return new AuthController(userService, email, baseUrl);
    }

    @Test
    void resetLinksUseTheConfiguredPublicAddressNotTheRequest() {
        when(email.isConfigured()).thenReturn(true);
        User u = new User("someone@example.com");
        when(users.findByEmail("someone@example.com")).thenReturn(Optional.of(u));
        ExtendedModelMap model = new ExtendedModelMap();

        String view = controller("https://afklive.in/").processForgotPassword("someone@example.com", model);

        assertThat(view).isEqualTo("login");
        assertThat(model.getAttribute("success")).asString().contains("reset link has been sent");
        verify(email).sendPasswordResetEmail(eq("someone@example.com"), startsWith("https://afklive.in/reset-password?token="));
    }

    @Test
    void verificationLinksUseTheConfiguredPublicAddress() {
        when(email.isConfigured()).thenReturn(true);
        when(users.findByEmail(anyString())).thenReturn(Optional.empty());
        when(users.save(any())).thenAnswer(i -> i.getArgument(0));
        ExtendedModelMap model = new ExtendedModelMap();

        controller("https://afklive.in").registerUser("new@example.com", "Passw0rd!", "New", model);

        verify(email).sendVerificationEmail(eq("new@example.com"), startsWith("https://afklive.in/verify-email?token="));
        assertThat(model.getAttribute("success")).asString().contains("check your email");
    }

    @Test
    void withEmailOffForgotPasswordSaysSoAndSendsNothing() {
        when(email.isConfigured()).thenReturn(false);
        User u = new User("someone@example.com");
        when(users.findByEmail("someone@example.com")).thenReturn(Optional.of(u));
        ExtendedModelMap model = new ExtendedModelMap();

        String view = controller("https://afklive.in").processForgotPassword("someone@example.com", model);

        assertThat(view).isEqualTo("forgot-password");
        assertThat(model.getAttribute("success")).isNull();
        assertThat(model.getAttribute("emailUnavailable")).asString().contains("support@afklive.in").contains("Google");
        assertThat(u.getResetToken()).isNull();
        verify(email, never()).sendPasswordResetEmail(anyString(), anyString());

        ExtendedModelMap page = new ExtendedModelMap();
        controller("https://afklive.in").forgotPassword(page);
        assertThat(page.getAttribute("emailUnavailable")).isNotNull();
    }

    @Test
    void withEmailOffAPasswordSignUpIsReadyAtOnce() {
        when(email.isConfigured()).thenReturn(false);
        when(users.findByEmail(anyString())).thenReturn(Optional.empty());
        when(users.save(any())).thenAnswer(i -> {
            User saved = i.getArgument(0);
            assertThat(saved.isEnabled()).isTrue();
            assertThat(saved.getVerificationToken()).isNull();
            return saved;
        });
        ExtendedModelMap model = new ExtendedModelMap();

        String view = controller("https://afklive.in").registerUser("new@example.com", "Passw0rd!", "New", model);

        assertThat(view).isEqualTo("login");
        assertThat(model.getAttribute("success")).asString().isEqualTo("Your account is ready. Sign in to start.");
        verify(email, never()).sendVerificationEmail(anyString(), anyString());
        verify(users).save(any());
    }

    @Test
    void theConfiguredAddressLosesTrailingSlashes() {
        assertThat(AuthController.publicBaseUrl(" https://afklive.in// ")).isEqualTo("https://afklive.in");
    }
}
