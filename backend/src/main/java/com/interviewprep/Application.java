package com.interviewprep;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class Application {

  public static void main(String[] args) {
    SpringApplication.run(Application.class, args);
  }

  /** The time of day, as a bean so a test can fix the date (the planner's week depends on it). */
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
