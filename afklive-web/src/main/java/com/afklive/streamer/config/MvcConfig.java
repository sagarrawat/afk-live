package com.afklive.streamer.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class MvcConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // Removed root mapping as it is handled by HomeController
        // The app (React SPA built from afklive-ui into static/app/). Client-side routes under /studio
        // all load the same page; the router picks the screen.
        registry.addViewController("/studio").setViewName("forward:/app/index.html");
        registry.addViewController("/studio/**").setViewName("forward:/app/index.html");
        registry.addRedirectViewController("/app", "/studio");
        // The previous Alpine app, kept for the YouTube upload and comment tools until they move over.
        registry.addViewController("/classic").setViewName("forward:/classic.html");
        registry.addViewController("/pricing").setViewName("forward:/pricing.html");
        registry.addViewController("/features").setViewName("forward:/features.html");
        registry.addViewController("/login").setViewName("login");
        registry.addViewController("/register").setViewName("register");
    }
}
