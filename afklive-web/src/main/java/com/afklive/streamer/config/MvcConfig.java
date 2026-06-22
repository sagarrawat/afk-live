package com.afklive.streamer.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class MvcConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // Removed root mapping as it is handled by HomeController
        registry.addViewController("/studio").setViewName("forward:/index.html");
        registry.addViewController("/studio/**").setViewName("forward:/index.html");
        registry.addViewController("/library").setViewName("forward:/index.html");
        registry.addViewController("/settings").setViewName("forward:/index.html");
        registry.addViewController("/forgot-password").setViewName("forward:/index.html");
        registry.addViewController("/terms").setViewName("forward:/index.html");
        registry.addViewController("/privacy").setViewName("forward:/index.html");
        registry.addViewController("/refund-policy").setViewName("forward:/index.html");
        registry.addViewController("/disclaimer").setViewName("forward:/index.html");
        registry.addViewController("/login").setViewName("login");
        registry.addViewController("/register").setViewName("register");
    }
}
