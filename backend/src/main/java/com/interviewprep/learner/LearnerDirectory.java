package com.interviewprep.learner;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.userdetails.AuthenticationUserDetailsService;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Who is allowed in, and which learner each of them is.
 *
 * <p>The list comes from {@code app.learners}, set only in {@code /etc/interview-prep/app.env} on the
 * VM, so nobody's login is ever committed to this public repository:
 *
 * <pre>APP_LEARNERS="you@example.com=you:Your Name,other@example.com=other:Other Name"</pre>
 *
 * <p>A login on the list becomes its learner, created on first use. A login that is not on the list
 * gets nothing — being on the tailnet is necessary, not sufficient.
 */
@Component
class LearnerDirectory implements AuthenticationUserDetailsService<PreAuthenticatedAuthenticationToken> {

  private static final Logger log = LoggerFactory.getLogger(LearnerDirectory.class);

  private record Entry(String slug, String displayName) {}

  private final Map<String, Entry> byLogin;
  private final NamedParameterJdbcTemplate jdbc;

  LearnerDirectory(@Value("${app.learners:}") String learners, NamedParameterJdbcTemplate jdbc) {
    this.byLogin = parse(learners);
    this.jdbc = jdbc;
    if (byLogin.isEmpty()) {
      log.warn("app.learners is empty: every request to /api will be refused");
    } else {
      log.info("{} learner(s) allowed: {}", byLogin.size(),
          byLogin.values().stream().map(Entry::slug).toList());
    }
  }

  static Map<String, Entry> parse(String spec) {
    Map<String, Entry> result = new LinkedHashMap<>();
    if (spec == null || spec.isBlank()) {
      return result;
    }
    for (String item : spec.split(",")) {
      String[] loginAndRest = item.trim().split("=", 2);
      if (loginAndRest.length != 2) {
        throw new IllegalArgumentException("app.learners entry must be login=slug:Name, got: " + item);
      }
      String[] slugAndName = loginAndRest[1].split(":", 2);
      String slug = slugAndName[0].trim();
      if (!slug.matches("[a-z0-9-]+")) {
        throw new IllegalArgumentException("learner slug must be lowercase letters, digits or -: " + slug);
      }
      String name = slugAndName.length == 2 ? slugAndName[1].trim() : slug;
      result.put(normalise(loginAndRest[0]), new Entry(slug, name));
    }
    return result;
  }

  // Logins are email addresses; compare them the way people expect email to be compared.
  private static String normalise(String login) {
    return login.trim().toLowerCase(Locale.ROOT);
  }

  @Override
  public UserDetails loadUserDetails(PreAuthenticatedAuthenticationToken token) {
    String login = normalise(String.valueOf(token.getPrincipal()));
    Entry entry = byLogin.get(login);
    if (entry == null) {
      throw new UsernameNotFoundException("not an allowed learner");
    }
    // Insert-or-select in one statement: creates the learner on first sight, reads it every time
    // after, and never rewrites the row. Looked up per request rather than cached, so it stays
    // right however the table changes underneath it.
    Long id = jdbc.queryForObject(
        "with created as ("
            + "  insert into learner (slug, display_name, email) values (:slug, :name, :login)"
            + "  on conflict (slug) do nothing returning id)"
            + " select id from created union all select id from learner where slug = :slug limit 1",
        new MapSqlParameterSource()
            .addValue("slug", entry.slug())
            .addValue("name", entry.displayName())
            .addValue("login", login),
        Long.class);
    return new LearnerPrincipal(id, entry.slug(), entry.displayName());
  }
}
