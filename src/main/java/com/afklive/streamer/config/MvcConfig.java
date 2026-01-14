package com.afklive.streamer.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class MvcConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/").setViewName("forward:/home.html");
        registry.addViewController("/studio").setViewName("forward:/app.html");
        registry.addViewController("/app").setViewName("forward:/app.html");
        registry.addViewController("/pricing").setViewName("forward:/pricing.html");
        registry.addViewController("/features").setViewName("forward:/features.html");
    }
}
