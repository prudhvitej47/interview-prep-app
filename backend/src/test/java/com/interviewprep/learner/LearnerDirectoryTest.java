package com.interviewprep.learner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** The allowlist format is typed by hand into a file on the VM, so a mistake should fail loudly. */
class LearnerDirectoryTest {

  @Test
  void parsesLoginSlugAndName() {
    assertThat(LearnerDirectory.parse("a@x.com=alice:Alice Smith, B@X.com=bob:Bob"))
        .containsOnlyKeys("a@x.com", "b@x.com");
  }

  @Test
  void anEmptyListAllowsNobody() {
    assertThat(LearnerDirectory.parse("")).isEmpty();
    assertThat(LearnerDirectory.parse(null)).isEmpty();
  }

  @Test
  void aMalformedEntryIsRefusedAtStartupNotIgnored() {
    assertThatThrownBy(() -> LearnerDirectory.parse("a@x.com"))
        .hasMessageContaining("login=slug:Name");
    assertThatThrownBy(() -> LearnerDirectory.parse("a@x.com=Alice Smith"))
        .hasMessageContaining("slug");
  }
}
