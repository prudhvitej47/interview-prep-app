/**
 * The code runner: builds and runs a learner's Java against a problem's cases in a sandbox, and reports
 * per-case results. Depends on nothing in the app, and nothing in the app depends on it: the app reaches
 * it over HTTP only, so it can be lifted into its own service (runner design §3).
 */
@org.springframework.modulith.ApplicationModule(displayName = "Code runner")
package com.interviewprep.runner;
