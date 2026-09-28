import type { PlanItem } from "../api";
import { RUNG_LABEL } from "../projects/rungs";
import { TYPE_LABEL } from "../unit/sections";

/** Where a plan item opens: its unit, or its question on the project's page. */
export const itemHref = (i: PlanItem): string =>
  i.kind === "project" ? `/projects/${i.projectId}#q-${i.projectQuestionId}` : `/units/${i.unitId}`;

/** Unique within a plan: a unit can be both learnt and reviewed, a question is planned once. */
export const itemKey = (i: PlanItem): string =>
  i.kind === "project" ? `project-${i.projectQuestionId}` : `${i.kind}-${i.unitId}`;

/** The small label beside an item's title. */
export const itemLabel = (i: PlanItem): string => {
  if (i.kind === "project") {
    return `Your project: ${i.projectName ?? ""}${i.rung ? ` · ${RUNG_LABEL[i.rung]}` : ""}`;
  }
  return i.kind === "review" ? "Review" : TYPE_LABEL[i.type] ?? i.type;
};
