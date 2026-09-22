package com.interviewprep.planner;

import com.interviewprep.curriculum.CurriculumQueries;
import com.interviewprep.curriculum.CurriculumQueries.Release;
import com.interviewprep.curriculum.CurriculumQueries.ReleaseUnit;
import com.interviewprep.learner.LearnerPrincipal;
import java.util.HashMap;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Where each new unit lands for a learner (proposal F10): their own choice if they made one, else
 * the proposal's suggestion from the release changelog, else next week.
 *
 * <ul>
 *   <li><b>next week</b>: an ordinary candidate from the next plan on;
 *   <li><b>now</b>: added to this week's plan straight away, and put first when a plan is made;
 *   <li><b>later</b> (end of track): left out of plans until the learner changes their mind.
 * </ul>
 */
@Component
class PlacementService {

  private final NamedParameterJdbcTemplate jdbc;
  private final CurriculumQueries curriculum;

  PlacementService(NamedParameterJdbcTemplate jdbc, CurriculumQueries curriculum) {
    this.jdbc = jdbc;
    this.curriculum = curriculum;
  }

  record Choice(String choice, String suggested, boolean chosen) {}

  /** For the latest release's units: what applies, what was suggested, and whether it was chosen. */
  Map<String, Choice> forLatestRelease(LearnerPrincipal me) {
    Release release = curriculum.latestRelease(me.slug());
    Map<String, Choice> out = new HashMap<>();
    if (release == null) {
      return out;
    }
    Map<String, String> suggested = Placements.suggested(release.changelog(), me.slug(), me.displayName());
    Map<String, String> chosen = explicit(me);
    for (ReleaseUnit u : release.units()) {
      String s = suggested.getOrDefault(u.id(), Placements.NEXT_WEEK);
      out.put(u.id(), new Choice(chosen.getOrDefault(u.id(), s), s, chosen.containsKey(u.id())));
    }
    return out;
  }

  /** Every unit's effective choice, where there is anything other than the default. */
  Map<String, String> effective(LearnerPrincipal me) {
    Map<String, String> out = new HashMap<>(explicit(me));
    forLatestRelease(me).forEach((unit, c) -> out.put(unit, c.choice()));
    return out;
  }

  /** The learner's own latest choice per unit, from any release. */
  private Map<String, String> explicit(LearnerPrincipal me) {
    Map<String, String> out = new HashMap<>();
    jdbc.query("select unit_id, choice from placement where learner_id = :learner order by decided_at, id",
        new MapSqlParameterSource("learner", me.id()), rs -> {
          out.put(rs.getString(1), rs.getString(2));
        });
    return out;
  }
}
