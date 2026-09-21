package com.interviewprep.learner;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * For running the app on a laptop, where there is no {@code tailscale serve} to name the user.
 *
 * <p>Exists only when {@code app.identity.dev-login} is set, which nothing on the VM ever does. It
 * supplies the identity header only when a request arrives without one, and says so loudly at
 * startup so it cannot be switched on by accident without anyone noticing.
 */
@Component
@ConditionalOnProperty("app.identity.dev-login")
@Order(Ordered.HIGHEST_PRECEDENCE)
class DevLoginFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(DevLoginFilter.class);

  private final String login;

  DevLoginFilter(@Value("${app.identity.dev-login}") String login) {
    this.login = login;
    log.warn("DEVELOPMENT SIGN-IN IS ON: requests without a Tailscale identity act as {}. "
        + "This must never be set on the VM.", login);
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain chain) throws ServletException, IOException {
    if (request.getHeader(SecurityConfig.IDENTITY_HEADER) != null) {
      chain.doFilter(request, response);
      return;
    }
    chain.doFilter(new HttpServletRequestWrapper(request) {
      @Override
      public String getHeader(String name) {
        return SecurityConfig.IDENTITY_HEADER.equalsIgnoreCase(name) ? login : super.getHeader(name);
      }

      @Override
      public Enumeration<String> getHeaders(String name) {
        return SecurityConfig.IDENTITY_HEADER.equalsIgnoreCase(name)
            ? Collections.enumeration(java.util.List.of(login)) : super.getHeaders(name);
      }
    }, response);
  }
}
