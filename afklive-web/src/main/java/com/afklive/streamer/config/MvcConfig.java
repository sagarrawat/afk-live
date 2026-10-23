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
        // The old Alpine app is retired: one product, one app. Old links land in the app.
        registry.addRedirectViewController("/classic", "/studio");
        registry.addRedirectViewController("/classic.html", "/studio");
        registry.addRedirectViewController("/home.html", "/");
        registry.addRedirectViewController("/pricing.html", "/pricing");
        registry.addRedirectViewController("/privacy.html", "/privacy");
        registry.addRedirectViewController("/terms.html", "/terms");
        // The public site (afklive-ui/site, built into static/site/).
        registry.addViewController("/pricing").setViewName("forward:/site/pricing.html");
        // RE-7: the public status page (data from /api/public/status).
        registry.addViewController("/status").setViewName("forward:/site/status.html");
        registry.addRedirectViewController("/features", "/#playlists");
        registry.addViewController("/login").setViewName("login");
        registry.addViewController("/register").setViewName("register");
    }
}
