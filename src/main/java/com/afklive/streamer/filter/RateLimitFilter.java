package com.afklive.streamer.filter;

import com.afklive.streamer.config.RateLimitConfig;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

@Component
@Order(1)
public class RateLimitFilter extends OncePerRequestFilter {

    private final Bucket bucket;

    @Autowired
    public RateLimitFilter(Bucket bucket) {
        this.bucket = bucket;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, 
                                   HttpServletResponse response, 
                                   FilterChain filterChain) 
        throws ServletException, IOException {
        
        if (request.getRequestURI().startsWith("/api/stream")) {
            if (!bucket.tryConsume(1)) {
                response.sendError(413, "Too many concurrent streams");
                return;
            }
        }
        
        filterChain.doFilter(request, response);
    }
}