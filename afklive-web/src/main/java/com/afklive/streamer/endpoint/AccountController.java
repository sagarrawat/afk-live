package com.afklive.streamer.endpoint;

import com.afklive.streamer.security.AccountGate;
import com.afklive.streamer.service.AccountService;
import com.afklive.streamer.service.IngestService;
import com.afklive.streamer.service.TwoFactorService;
import com.afklive.streamer.util.SecurityUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * Account security and data (ON-6, ON-7): two-step sign-in setup, the data export, and deleting
 * the account.
 */
@RestController
@RequestMapping("/api/account")
public class AccountController {

    private final TwoFactorService twoFactor;
    private final AccountService accounts;
    private final IngestService qr;

    public AccountController(TwoFactorService twoFactor, AccountService accounts, IngestService qr) {
        this.twoFactor = twoFactor;
        this.accounts = accounts;
        this.qr = qr;
    }

    public record Security(boolean twoFactorEnabled, long recoveryCodesLeft, ZonedDateTime twoFactorSince) {
    }

    public record CodeRequest(String code) {
    }

    public record RecoveryCodes(List<String> recoveryCodes) {
    }

    public record DeleteRequest(String email) {
    }

    public record Deletion(ZonedDateTime scheduledFor) {
    }

    @GetMapping("/security")
    public Security security(Principal p) {
        TwoFactorService.Status s = twoFactor.status(email(p));
        return new Security(s.enabled(), s.recoveryCodesLeft(), s.enabledAt());
    }

    /** Starts two-step setup: the key to type in (if the QR code can't be scanned) and the app link. */
    @PostMapping("/2fa/setup")
    public ResponseEntity<TwoFactorService.Setup> setup(Principal p) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(twoFactor.beginSetup(email(p)));
    }

    /** The QR code for the key being set up (made on the server, like the phone setup codes). */
    @GetMapping(value = "/2fa/qr.png", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> setupQr(Principal p) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.IMAGE_PNG)
                .body(qr.qrPng(twoFactor.pendingUri(email(p)), 300));
    }

    /** Confirms setup with a code; returns the recovery codes, shown once. */
    @PostMapping("/2fa/enable")
    public ResponseEntity<RecoveryCodes> enable(@RequestBody CodeRequest body, Principal p, HttpServletRequest request) {
        String email = email(p);
        List<String> codes = twoFactor.enable(email, body == null ? null : body.code());
        // This session just proved it has the app: it stays fully signed in.
        request.getSession().setAttribute(AccountGate.VERIFIED, email);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new RecoveryCodes(codes));
    }

    @PostMapping("/2fa/disable")
    public Security disable(@RequestBody CodeRequest body, Principal p) {
        twoFactor.disable(email(p), body == null ? null : body.code());
        return security(p);
    }

    @PostMapping("/2fa/recovery-codes")
    public ResponseEntity<RecoveryCodes> newRecoveryCodes(@RequestBody CodeRequest body, Principal p) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new RecoveryCodes(twoFactor.regenerateRecoveryCodes(email(p), body == null ? null : body.code())));
    }

    /** ON-7: everything about the account as a zip (no stream keys or other secrets). */
    @GetMapping(value = "/export", produces = "application/zip")
    public ResponseEntity<byte[]> export(Principal p) {
        byte[] zip = accounts.export(email(p));
        String name = "afk-live-data-" + LocalDate.now(ZoneId.of("UTC")) + ".zip";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(zip);
    }

    /**
     * ON-7: closes the account now and deletes it after 7 days. The browser is signed out, but can
     * still choose "Keep my account" on the sign-in page.
     */
    @PostMapping("/delete")
    public Deletion delete(@RequestBody DeleteRequest body, Principal p, HttpServletRequest request,
                           jakarta.servlet.http.HttpServletResponse response) {
        String email = email(p);
        ZonedDateTime when = accounts.requestDeletion(email, body == null ? null : body.email());
        SecurityContextHolder.clearContext();
        jakarta.servlet.http.Cookie rememberMe = new jakarta.servlet.http.Cookie("remember-me", "");
        rememberMe.setMaxAge(0);
        rememberMe.setPath("/");
        response.addCookie(rememberMe);
        HttpSession old = request.getSession(false);
        if (old != null) {
            old.invalidate();
        }
        request.getSession(true).setAttribute(AccountGate.RESTORE, email);
        return new Deletion(when);
    }

    private static String email(Principal p) {
        if (p == null) {
            throw new org.springframework.security.access.AccessDeniedException("Sign in first.");
        }
        return SecurityUtils.getEmail(p);
    }
}
