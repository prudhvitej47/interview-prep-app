package com.interviewprep.learner;

import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * The learner making the current request. Any module takes it with
 * {@code @AuthenticationPrincipal LearnerPrincipal me} and scopes its queries by {@link #id()}.
 *
 * <p>There is no password: who you are was already established by Tailscale before the request
 * reached the app.
 */
public record LearnerPrincipal(long id, String slug, String displayName) implements UserDetails {

  @Override
  public Collection<? extends GrantedAuthority> getAuthorities() {
    return List.of(new SimpleGrantedAuthority("ROLE_LEARNER"));
  }

  @Override
  public String getPassword() {
    return "";
  }

  @Override
  public String getUsername() {
    return slug;
  }
}
