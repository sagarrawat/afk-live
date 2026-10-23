package com.afklive.streamer.endpoint;

import com.afklive.streamer.exception.TooManyAttemptsException;
import com.afklive.streamer.security.AccountGate;
import com.afklive.streamer.service.AccountService;
import com.afklive.streamer.service.TwoFactorService;
import com.afklive.streamer.util.SecurityUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * The sign-in pages around ON-6 and ON-7: the second step (a code from the authenticator app or a
 * recovery code), and keeping an account that is scheduled for deletion.
 */
@Controller
public class SignInStepController {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);

    private final TwoFactorService twoFactor;
    private final AccountService accounts;

    public SignInStepController(TwoFactorService twoFactor, AccountService accounts) {
        this.twoFactor = twoFactor;
        this.accounts = accounts;
    }

    @GetMapping("/login/2fa")
    public String twoFactorPage(Authentication auth, HttpServletRequest request) {
        String email = SecurityUtils.getEmail(auth);
        if (!twoFactor.required(email) || email.equals(attr(request, AccountGate.VERIFIED))) {
            return "redirect:" + home(auth);
        }
        return "two-factor";
    }

    @PostMapping("/login/2fa")
    public String verify(@RequestParam(required = false) String code, Authentication auth, HttpServletRequest request, Model model) {
        String email = SecurityUtils.getEmail(auth);
        try {
            if (!twoFactor.verifySignIn(email, code)) {
                model.addAttribute("error", "That code didn't match. Enter the newest code from your app, or a recovery code.");
                return "two-factor";
            }
        } catch (TooManyAttemptsException e) {
            model.addAttribute("error", e.getMessage());
            return "two-factor";
        }
        request.changeSessionId(); // a fully signed-in session gets a new id
        request.getSession().setAttribute(AccountGate.VERIFIED, email);
        return "redirect:" + home(auth);
    }

    /** The sign-in page; offers to keep an account scheduled for deletion that this browser just signed in to. */
    @GetMapping("/login")
    public String login(HttpServletRequest request, Model model) {
        Object restore = attr(request, AccountGate.RESTORE);
        if (restore instanceof String email) {
            AccountService.DeletionState state;
            try {
                state = accounts.deletionState(email);
            } catch (Exception e) {
                state = null; // already deleted
            }
            if (state != null && state.scheduled()) {
                model.addAttribute("deletionDate", state.scheduledFor().format(DAY));
            } else {
                request.getSession().removeAttribute(AccountGate.RESTORE);
            }
        }
        return "login";
    }

    /** "Keep my account": cancels the scheduled deletion for the account this browser proved it owns. */
    @PostMapping("/account/restore")
    public String restore(HttpServletRequest request) {
        Object restore = attr(request, AccountGate.RESTORE);
        if (!(restore instanceof String email)) {
            return "redirect:/login";
        }
        accounts.cancelDeletion(email);
        request.getSession().removeAttribute(AccountGate.RESTORE);
        return "redirect:/login?restored";
    }

    private static String home(Authentication auth) {
        return auth.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority())) ? "/admin" : "/studio";
    }

    private static Object attr(HttpServletRequest request, String name) {
        HttpSession s = request.getSession(false);
        return s == null ? null : s.getAttribute(name);
    }
}
