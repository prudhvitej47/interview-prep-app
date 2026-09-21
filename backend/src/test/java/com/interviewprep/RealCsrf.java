package com.interviewprep;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import jakarta.servlet.http.Cookie;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Gets a CSRF token the way the browser does — from the cookie on a real response — and echoes it
 * back in the header, exactly as the front end's {@code api.ts} does.
 *
 * <p>Deliberately not Spring Security's {@code SecurityMockMvcRequestPostProcessors.csrf()}. That
 * helper swaps the token repository inside the shared {@code CsrfFilter} for a session-based test
 * double, and the change outlives the request: every later test in the same cached context then
 * sees a filter that no longer issues the cookie. It also means the tests would not be exercising
 * the mechanism that actually runs in production.
 */
public final class RealCsrf {

  private RealCsrf() {}

  public static RequestPostProcessor token(MockMvc mvc, String login) throws Exception {
    Cookie cookie = mvc.perform(get("/api/me").header("Tailscale-User-Login", login))
        .andReturn().getResponse().getCookie("XSRF-TOKEN");
    if (cookie == null) {
      throw new AssertionError("the server did not issue an XSRF-TOKEN cookie");
    }
    return request -> {
      request.setCookies(cookie);
      request.addHeader("X-XSRF-TOKEN", cookie.getValue());
      return request;
    };
  }
}
