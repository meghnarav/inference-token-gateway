package com.tokengateway.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC configuration.
 *
 * - CORS: allows the frontend (served from the same origin or localhost dev) to call the API.
 * - Static resources: serves the frontend SPA from /static/** mapped to classpath:/static/.
 *   Spring Boot auto-configures this by default, but the explicit handler ensures
 *   correct cache headers for index.html in Docker deployments.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/generate")
                .allowedOriginPatterns("*")
                .allowedMethods("POST", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);

        registry.addMapping("/usage/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);

        registry.addMapping("/actuator/health")
                .allowedOriginPatterns("*")
                .allowedMethods("GET")
                .maxAge(3600);
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Spring Boot auto-configures classpath:/static/ at /**,
        // so we only need to ensure index.html is served with no-cache headers.
        registry.addResourceHandler("/index.html")
                .addResourceLocations("classpath:/static/")
                .setCachePeriod(0);
    }
}
