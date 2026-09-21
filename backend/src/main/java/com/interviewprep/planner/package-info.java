/**
 * The weekly plan: how each learner's study time is split across domains, which units fill it, and
 * which days they fall on (proposal section F). Reads the curriculum, the learner's settings and
 * ratings, their progress and the interview evidence; owns only the plans it makes.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Planner")
package com.interviewprep.planner;
