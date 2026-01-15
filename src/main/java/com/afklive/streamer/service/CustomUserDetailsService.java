package com.afklive.streamer.service;

import com.afklive.streamer.model.User;
import com.afklive.streamer.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Collections;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userRepository.findById(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));

        // Use a dummy password for OAuth users so they can't login via form until they set one
        String password = user.getPassword() != null ? user.getPassword() : "{noop}OAUTH_USER_NO_PASSWORD";

        return new org.springframework.security.core.userdetails.User(
                user.getUsername(),
                password,
                true, true, true, true,
                Collections.emptyList()
        );
    }
}
