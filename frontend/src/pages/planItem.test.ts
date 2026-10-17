import { describe, expect, it } from "vitest";
import type { PlanItem } from "../api";
import { itemLabel } from "./planItem";

const item = (over: Partial<PlanItem>): PlanItem => ({
  unitId: "u", title: "T", type: "concept", day: 1, kind: "learn", minutes: 40, reason: "", done: false, ...over,
});

describe("itemLabel", () => {
  it("says when learning a unit means a lab on the learner's laptop", () => {
    expect(itemLabel(item({ hasLab: true }))).toBe("Concept · lab on your laptop");
    expect(itemLabel(item({}))).toBe("Concept");
  });

  it("does not repeat the lab on a review", () => {
    expect(itemLabel(item({ kind: "review", hasLab: true }))).toBe("Review");
  });
});
