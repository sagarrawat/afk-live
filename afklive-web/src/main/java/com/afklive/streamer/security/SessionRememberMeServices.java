package com.afklive.streamer.security;

import com.afklive.streamer.service.AccountSecurityService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.rememberme.TokenBasedRememberMeServices;

import java.util.function.ToIntFunction;

/**
 * "Keep me signed in" cookies (ON-6). Like Spring's default, a cookie is signed with the account's
 * password hash, so changing the password ends them; here the signature also includes the account's
 * {@code sessions_version} (see {@link AccountSecurityService#rememberMePassword}), so "Sign out other
 * devices" ends them too. The device that asked gets a fresh cookie ({@link #reissue}).
 */
public class SessionRememberMeServices extends TokenBasedRememberMeServices {

    public SessionRememberMeServices(String key, UserDetailsService accounts, ToIntFunction<String> sessionsVersion) {
        super(key, stamped(accounts, sessionsVersion));
    }

    static UserDetailsService stamped(UserDetailsService accounts, ToIntFunction<String> sessionsVersion) {
        return username -> {
            UserDetails u = accounts.loadUserByUsername(username);
            return User.withUserDetails(u)
                    .password(AccountSecurityService.rememberMePassword(u.getPassword(), sessionsVersion.applyAsInt(username)))
                    .build();
        };
    }

    /** Always sign with the stamped value from the database, never a password held by the sign-in. */
    @Override
    protected String retrievePassword(Authentication authentication) {
        return null;
    }

    /** The email for password and Google sign-ins alike (Google's user-name attribute is the email). */
    @Override
    protected String retrieveUserName(Authentication authentication) {
        return authentication.getName();
    }

    /** Issues a new cookie to this browser, if it had one (after the version was raised). */
    public void reissue(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        if (authentication == null || extractRememberMeCookie(request) == null) {
            return;
        }
        String parameter = getParameter();
        HttpServletRequest asked = new HttpServletRequestWrapper(request) {
            @Override
            public String getParameter(String name) {
                return parameter.equals(name) ? "true" : super.getParameter(name);
            }
        };
        onLoginSuccess(asked, response, authentication);
    }
}
