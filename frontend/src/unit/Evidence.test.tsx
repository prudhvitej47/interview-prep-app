import { fireEvent, render, screen } from "@testing-library/react";
import { expect, it } from "vitest";
import { Evidence } from "./Evidence";

const md = (text: string) => <p>{text}</p>;
const source = JSON.stringify({ prompt: "Which line takes the second lock?", explain: "Line 2 takes fees while holding the pair.",
  lines: ["first.lock.lock();", "postFee(fee);", "first.lock.unlock();"], cause: [2] });

it("keeps Check disabled until a line is selected", () => {
  render(<Evidence source={source} render={md} />);
  expect(screen.getByRole("button", { name: "Check" })).toBeDisabled();
  fireEvent.click(screen.getByRole("checkbox", { name: "Line 2: postFee(fee);" }));
  expect(screen.getByRole("button", { name: "Check" })).toBeEnabled();
});

it("marks the right selection and explains", () => {
  render(<Evidence source={source} render={md} />);
  fireEvent.click(screen.getByRole("checkbox", { name: "Line 2: postFee(fee);" }));
  fireEvent.click(screen.getByRole("button", { name: "Check" }));
  expect(screen.getByRole("status")).toHaveTextContent("Right.");
  expect(screen.getByText(/takes fees while holding/)).toBeInTheDocument();
});

it("says which lines were missed and which were not causes, in words", () => {
  render(<Evidence source={source} render={md} />);
  fireEvent.click(screen.getByRole("checkbox", { name: "Line 1: first.lock.lock();" }));
  fireEvent.click(screen.getByRole("button", { name: "Check" }));
  expect(screen.getByRole("status")).toHaveTextContent("Not quite. Missed: line 2. Not a cause: line 1.");
  expect(screen.getByText("cause", { selector: ".evidence-mark" })).toBeInTheDocument();
  expect(screen.getByText("not a cause", { selector: ".evidence-mark" })).toBeInTheDocument();
  expect(screen.getByRole("checkbox", { name: "Line 3: first.lock.unlock();" })).toBeDisabled();
});

it("shows a readable line instead of breaking the page when the fence is bad", () => {
  render(<Evidence source={JSON.stringify({ prompt: "p", lines: ["a"], cause: [2], explain: "e" })} render={md} />);
  expect(screen.getByText(/This exercise could not be read: cause names line 2/)).toBeInTheDocument();
});

it("puts each mark before its line, so a long line cannot scroll it out of view", () => {
  render(<Evidence source={source} render={md} />);
  fireEvent.click(screen.getByRole("checkbox", { name: "Line 1: first.lock.lock();" }));
  fireEvent.click(screen.getByRole("button", { name: "Check" }));
  const mark = screen.getByText("not a cause", { selector: ".evidence-mark" });
  expect(mark.nextElementSibling?.tagName).toBe("CODE");
});

it("moves focus to the result after Check", () => {
  render(<Evidence source={source} render={md} />);
  const status = screen.getByRole("status");
  expect(status).toBeEmptyDOMElement();
  fireEvent.click(screen.getByRole("checkbox", { name: "Line 2: postFee(fee);" }));
  fireEvent.click(screen.getByRole("button", { name: "Check" }));
  expect(status).toHaveTextContent("Right.");
  expect(document.activeElement).toBe(status);
});

it("groups the lines under the prompt, and after Check says which were causes", () => {
  render(<Evidence source={source} render={md} />);
  expect(screen.getByRole("group", { name: "Which line takes the second lock?" })).toBeInTheDocument();
  expect(screen.getByRole("list")).toBeInTheDocument(); // still a list for screen readers
  fireEvent.click(screen.getByRole("checkbox", { name: "Line 1: first.lock.lock();" }));
  fireEvent.click(screen.getByRole("checkbox", { name: "Line 2: postFee(fee);" }));
  fireEvent.click(screen.getByRole("button", { name: "Check" }));
  expect(screen.getByRole("checkbox", { name: "Line 2: postFee(fee);" })).toHaveAccessibleDescription("cause");
  expect(screen.getByRole("checkbox", { name: "Line 1: first.lock.lock();" })).toHaveAccessibleDescription("not a cause");
});
