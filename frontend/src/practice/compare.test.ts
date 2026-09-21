import { verdict } from "./compare";

const answer = [[105, "Cloud Gym", 250000], [109, "Rail Tickets", 210000]];

test("the same rows are right, and numbers compare as numbers", () => {
  expect(verdict(answer, [["105", "Cloud Gym", "250000.00"], [109, "Rail Tickets", 210000]], true)).toEqual({ kind: "right" });
});

test("order counts only when the problem asks for it", () => {
  const swapped = [answer[1], answer[0]];
  expect(verdict(answer, swapped, false)).toEqual({ kind: "right" });
  expect(verdict(answer, swapped, true)).toEqual({ kind: "wrong-order" });
});

test("a missing row, a wrong value or an extra column is not the answer", () => {
  expect(verdict(answer, [answer[0]], false)).toEqual({ kind: "row-count", got: 1, want: 2 });
  expect(verdict(answer, [answer[0], [109, "Rail Tickets", 1]], false)).toEqual({ kind: "different" });
  expect(verdict(answer, answer.map((r) => [...r, "x"]), false)).toEqual({ kind: "different" });
});

test("a duplicate row does not stand in for a missing one", () => {
  expect(verdict(answer, [answer[0], answer[0]], false)).toEqual({ kind: "different" });
});

test("null only matches null", () => {
  expect(verdict([[null]], [[null]], true)).toEqual({ kind: "right" });
  expect(verdict([[null]], [[""]], true)).toEqual({ kind: "different" });
});
