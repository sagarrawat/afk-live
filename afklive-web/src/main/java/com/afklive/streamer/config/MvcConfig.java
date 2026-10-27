package com.afklive.streamer.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;

@Configuration
public class MvcConfig implements WebMvcConfigurer {

    /** Files whose names change when their content does (Vite's content hash): cached for a year, never rechecked. */
    static final CacheControl HASHED = CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable();
    /** Images and icons keep their names when they change, so a day. */
    static final CacheControl DAY = CacheControl.maxAge(Duration.ofDays(1)).cachePublic();
    /** Pages and stable-named stylesheets: kept, but checked with the server (a quick 304) before every use. */
    static final CacheControl REVALIDATE = CacheControl.noCache().cachePublic();

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
        // Old public addresses moved for good (301, so search engines carry them over to the new ones).
        registry.addRedirectViewController("/home.html", "/").setStatusCode(HttpStatus.MOVED_PERMANENTLY);
        registry.addRedirectViewController("/pricing.html", "/pricing").setStatusCode(HttpStatus.MOVED_PERMANENTLY);
        registry.addRedirectViewController("/privacy.html", "/privacy").setStatusCode(HttpStatus.MOVED_PERMANENTLY);
        registry.addRedirectViewController("/terms.html", "/terms").setStatusCode(HttpStatus.MOVED_PERMANENTLY);
        registry.addRedirectViewController("/features", "/#how").setStatusCode(HttpStatus.MOVED_PERMANENTLY);
        registry.addRedirectViewController("/features.html", "/#how").setStatusCode(HttpStatus.MOVED_PERMANENTLY);
        // The public site (afklive-ui/site, built into static/site/).
        registry.addViewController("/pricing").setViewName("forward:/site/pricing.html");
        // RE-7: the public status page (data from /api/public/status).
        registry.addViewController("/status").setViewName("forward:/site/status.html");
        registry.addViewController("/login").setViewName("login");
        registry.addViewController("/register").setViewName("register");
    }

    /**
     * Browser caching for the public site and the app's files (SEO and speed). A Cache-Control set here
     * also keeps Spring Security from adding its no-store, which it still adds to everything else
     * (signed-in pages and the API).
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/site/assets/**").addResourceLocations("classpath:/static/site/assets/").setCacheControl(HASHED);
        registry.addResourceHandler("/app/assets/**").addResourceLocations("classpath:/static/app/assets/").setCacheControl(HASHED);
        registry.addResourceHandler("/site/img/**").addResourceLocations("classpath:/static/site/img/").setCacheControl(DAY);
        registry.addResourceHandler("/site/brand/**").addResourceLocations("classpath:/static/site/brand/").setCacheControl(DAY);
        registry.addResourceHandler("/favicon.ico").addResourceLocations("classpath:/static/").setCacheControl(DAY);
        // The pages themselves (forwarded to from /, /pricing, /for/...) and site.css for the sign-in pages.
        registry.addResourceHandler("/site/**").addResourceLocations("classpath:/static/site/").setCacheControl(REVALIDATE);
    }
}
