import { fireEvent, render, screen } from "@testing-library/react";
import { expect, it } from "vitest";
import { Predict } from "./Predict";

const md = (text: string) => <p>{text}</p>;
const choice = JSON.stringify({ question: "What happens?", choices: ["It hangs", "It throws"], answer: 0,
  reveal: "It hangs: two threads wait on each other.", output: "v1: stuck after 76 transfers" });
const number = JSON.stringify({ question: "How many of five runs froze?", number: 5, unit: "runs", reveal: "All five." });

it("keeps the reveal hidden until an answer is committed", () => {
  render(<Predict source={choice} render={md} />);
  expect(screen.queryByText(/two threads wait/)).toBeNull();
  const check = screen.getByRole("button", { name: "Check" });
  expect(check).toBeDisabled();
  fireEvent.click(screen.getByRole("radio", { name: "It throws" }));
  fireEvent.click(check);
  expect(screen.getByRole("status")).toHaveTextContent("Not quite. The answer is: It hangs");
  expect(screen.getByText(/two threads wait/)).toBeInTheDocument();
  expect(screen.getByText("v1: stuck after 76 transfers")).toBeInTheDocument();
});

it("locks the answer once checked", () => {
  render(<Predict source={choice} render={md} />);
  fireEvent.click(screen.getByRole("radio", { name: "It hangs" }));
  fireEvent.click(screen.getByRole("button", { name: "Check" }));
  expect(screen.getByRole("status")).toHaveTextContent("Right.");
  expect(screen.getByRole("radio", { name: "It throws" })).toBeDisabled();
  expect(screen.queryByRole("button", { name: "Check" })).toBeNull();
});

it("reads a typed number leniently and shows the unit", () => {
  render(<Predict source={number} render={md} />);
  const box = screen.getByRole("textbox", { name: "Your answer (runs)" });
  const check = screen.getByRole("button", { name: "Check" });
  fireEvent.change(box, { target: { value: "five" } });
  expect(check).toBeDisabled();
  fireEvent.change(box, { target: { value: " 5 " } });
  fireEvent.click(check);
  expect(screen.getByRole("status")).toHaveTextContent("Right.");
});

it("keeps two exercises on one page independent", () => {
  render(<><Predict source={choice} render={md} /><Predict source={choice} render={md} /></>);
  const [first, second] = screen.getAllByRole("radiogroup");
  const firstHangs = first.querySelector("input[value='0']") as HTMLInputElement;
  const secondHangs = second.querySelector("input[value='0']") as HTMLInputElement;
  expect(firstHangs.name).not.toBe(secondHangs.name);
  fireEvent.click(firstHangs);
  fireEvent.click(screen.getAllByRole("button", { name: "Check" })[0]);
  const [firstStatus, secondStatus] = screen.getAllByRole("status");
  expect(firstStatus).toHaveTextContent("Right.");
  expect(secondStatus).toBeEmptyDOMElement();
  expect(screen.getAllByRole("button", { name: "Check" })).toHaveLength(1);
});

it("shows a readable line instead of breaking the page when the fence is bad", () => {
  render(<Predict source="{" render={md} />);
  expect(screen.getByText(/This exercise could not be read: the fence is not valid JSON/)).toBeInTheDocument();
});

it("moves focus to the result after Check, so keyboard and screen-reader users keep their place", () => {
  render(<Predict source={choice} render={md} />);
  const status = screen.getByRole("status");
  expect(status).toBeEmptyDOMElement();
  fireEvent.click(screen.getByRole("radio", { name: "It hangs" }));
  fireEvent.click(screen.getByRole("button", { name: "Check" }));
  expect(status).toHaveTextContent("Right.");
  expect(document.activeElement).toBe(status);
});

it("names the choices by the question, and lets phones type a minus sign", () => {
  render(<Predict source={choice} render={md} />);
  expect(screen.getByRole("radiogroup", { name: "What happens?" })).toBeInTheDocument();
  render(<Predict source={number} render={md} />);
  expect(screen.getByRole("textbox", { name: "Your answer (runs)" })).toHaveAttribute("inputmode", "text");
});
