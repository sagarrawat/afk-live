package com.afklive.streamer.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;

/** Where a signed-in person lands: admins in the admin console, everyone else in the studio. */
public final class SignInTargets {

    public static final String ADMIN_HOME = "/studio/admin";
    public static final String USER_HOME = "/studio";

    private SignInTargets() {
    }

    public static String home(Authentication auth) {
        return auth == null ? USER_HOME : home(auth.getAuthorities());
    }

    public static String home(Collection<? extends GrantedAuthority> authorities) {
        return authorities != null && authorities.stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority())) ? ADMIN_HOME : USER_HOME;
    }

    public static String homeForRole(String role) {
        return "ROLE_ADMIN".equals(role) ? ADMIN_HOME : USER_HOME;
    }
}
