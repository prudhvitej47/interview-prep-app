package com.interviewprep;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * One real Postgres for the whole test run.
 *
 * <p>The container is started once and deliberately never stopped — Ryuk reaps it when the JVM
 * exits. It is the same major version the VM runs, because testing migrations against H2 would
 * prove nothing about the ones that matter.
 */
abstract class PostgresTestBase {

  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18-trixie")
          .withDatabaseName("interviewprep")
          .withUsername("interviewprep")
          .withPassword("interviewprep");

  static {
    POSTGRES.start();
  }

  @DynamicPropertySource
  static void datasourceProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }
}
