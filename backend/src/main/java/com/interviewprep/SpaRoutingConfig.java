package com.interviewprep;

import java.io.IOException;
import java.time.Duration;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Serves the built React app, and hands unknown paths to its router instead of 404ing them.
 *
 * <p>The obvious shortcut — "a path containing a dot is a file, everything else is a route" —
 * does not work here, because unit ids are dotted: {@code /units/ds.transactions.idempotency-keys}
 * is a route, not a file. So the rule is based on what actually exists on disk instead:
 *
 * <ul>
 *   <li>the file exists → serve it
 *   <li>under {@code assets/} and missing → 404, so a bad build fails loudly rather than
 *       returning HTML where the browser expects JavaScript
 *   <li>under {@code api/} or {@code actuator/} and unmatched → 404 as JSON, not the SPA
 *   <li>anything else → {@code index.html}, and the React router decides
 * </ul>
 *
 * <p>Files under {@code assets/} are cached for a year: Vite puts a content hash in every name, so
 * a changed file is a new URL. That matters most for PGlite's 16 MB of WebAssembly, which would
 * otherwise be fetched again on every visit to a SQL problem.
 */
@Configuration
class SpaRoutingConfig implements WebMvcConfigurer {

  private static final ClassPathResource INDEX = new ClassPathResource("static/index.html");

  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    registry
        .addResourceHandler("/assets/**")
        .addResourceLocations("classpath:/static/assets/")
        .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());
    registry
        .addResourceHandler("/**")
        .addResourceLocations("classpath:/static/")
        .resourceChain(true)
        .addResolver(
            new PathResourceResolver() {
              @Override
              protected Resource getResource(String resourcePath, Resource location)
                  throws IOException {
                Resource requested = location.createRelative(resourcePath);
                if (requested.exists() && requested.isReadable()) {
                  return requested;
                }
                if (resourcePath.startsWith("assets/")
                    || resourcePath.startsWith("api/")
                    || resourcePath.startsWith("actuator/")) {
                  return null;
                }
                return INDEX;
              }
            });
  }
}
