package com.interviewprep;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Fails if a module reaches into another module's internals. This is the check that keeps the
 * boundaries from quietly dissolving as features land, so that the AI module or the code runner
 * can still be lifted out into their own service later.
 */
class ApplicationModulesTest {

  @Test
  void modulesRespectTheirBoundaries() {
    ApplicationModules.of(Application.class).verify();
  }
}
