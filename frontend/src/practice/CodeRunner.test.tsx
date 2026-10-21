import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { CodeRunner } from "./CodeRunner";
import { lastCallTo, mockFetch } from "../testing";

const UNIT = "dsa.window.max-sum";
const CODE = `/api/units/${UNIT}/code`;
const RUN = `/api/units/${UNIT}/run`;
const STARTER = "class Solution {\n    public int maxSum(int[] nums, int k) {\n        return 0;\n    }\n}\n";
const CASES = [
  { name: "the example", input: "nums = [2, 1], k = 1", expected: "2" },
  { name: "negatives", input: "nums = [-3, -1], k = 1", expected: "-1" },
];
const draft = (overrides: object = {}) => ({ code: "", savedAt: null, lastSubmit: null, starter: STARTER, ...overrides });
const finished = (overrides: object = {}) => ({
  status: "finished", compileErrors: null, hidden: null, output: null,
  cases: [
    { ...CASES[0], verdict: "pass", got: "2", error: null, ms: 3 },
    { ...CASES[1], verdict: "fail", got: "7", error: null, ms: 2 },
  ],
  ...overrides,
});

function show() {
  render(<CodeRunner unitId={UNIT} cases={CASES} hidden={1} />);
}

const editor = () => screen.getByLabelText("Your solution") as HTMLTextAreaElement;

describe("CodeRunner", () => {
  beforeEach(() => {
    document.cookie = "XSRF-TOKEN=token-from-the-server";
    localStorage.clear();
  });
  afterEach(() => vi.unstubAllGlobals());

  it("starts from the signature stub, and shows the saved code and last submit on a revisit", async () => {
    mockFetch({ [CODE]: { body: draft() } });
    show();
    await waitFor(() => expect(editor().value).toBe(STARTER));
    expect(screen.getByRole("button", { name: "Save" })).toBeDisabled(); // the stub counts as saved
    vi.unstubAllGlobals();

    mockFetch({ [CODE]: { body: draft({ code: "class Solution { /* mine */ }", lastSubmit: { passed: 2, total: 3, at: "2026-10-11T06:00:00Z" } }) } });
    show();
    await waitFor(() => expect(screen.getAllByLabelText("Your solution").at(-1)).toHaveValue("class Solution { /* mine */ }"));
    // The date is in the reader's own locale ("11 Oct" or "Oct 11"), so compare with the same formatter.
    const day = new Date("2026-10-11T06:00:00Z").toLocaleDateString(undefined, { day: "numeric", month: "short" });
    expect(screen.getByText(`Last submit: 2 of 3 pass, ${day}`)).toBeInTheDocument();
  });

  it("runs the visible cases and shows each with what the code returned", async () => {
    const fetchMock = mockFetch({ [CODE]: { body: draft() }, [RUN]: { body: finished({ output: "debug line" }) } });
    show();
    await waitFor(() => expect(editor().value).toBe(STARTER));
    fireEvent.click(screen.getByRole("button", { name: "Run" }));
    const table = await screen.findByRole("table", { name: "Results" });
    expect(JSON.parse(lastCallTo(fetchMock, RUN)[1]!.body as string)).toEqual({ code: STARTER, submit: false });
    const rows = within(table).getAllByRole("row");
    expect(rows[1]).toHaveTextContent("the example");
    expect(rows[1]).toHaveTextContent("✓");
    expect(rows[2]).toHaveTextContent("7");
    expect(rows[2]).toHaveTextContent("✗");
    expect(screen.getByText("1 of 2 pass.")).toHaveClass("verdict", "wrong");
    expect(screen.getByText("debug line")).toBeInTheDocument();
    expect(screen.queryByText(/Hidden cases/)).not.toBeInTheDocument();
  });

  it("submits every case and shows hidden ones only as a count, then remembers the submit", async () => {
    mockFetch({
      [CODE]: { body: draft() },
      [RUN]: { body: finished({ hidden: { passed: 1, total: 1 }, cases: [
        { ...CASES[0], verdict: "pass", got: "2", error: null, ms: 3 },
        { ...CASES[1], verdict: "pass", got: "-1", error: null, ms: 2 },
      ] }) },
    });
    show();
    await waitFor(() => expect(editor().value).toBe(STARTER));
    fireEvent.click(screen.getByRole("button", { name: "Submit" }));
    expect(await screen.findByText("✓ All 3 cases pass.")).toHaveClass("verdict", "right");
    expect(screen.getByText("Hidden cases: 1 of 1 pass")).toBeInTheDocument();
    expect(screen.getByText(/Last submit: 3 of 3 pass/)).toBeInTheDocument();
    expect(within(screen.getByRole("table", { name: "Results" })).getAllByRole("row")).toHaveLength(3);
  });

  it("puts each case's result first, and an exception on its own row under the case", async () => {
    mockFetch({
      [CODE]: { body: draft() },
      [RUN]: { body: finished({ cases: [
        { ...CASES[0], verdict: "pass", got: "2", error: null, ms: 3 },
        { ...CASES[1], verdict: "error", got: null, error: "java.lang.ArithmeticException: / by zero", ms: 2 },
      ] }) },
    });
    show();
    await waitFor(() => expect(editor().value).toBe(STARTER));
    fireEvent.click(screen.getByRole("button", { name: "Run" }));
    const table = await screen.findByRole("table", { name: "Results" });
    const rows = within(table).getAllByRole("row");
    expect(within(rows[0]).getAllByRole("columnheader")[0]).toHaveTextContent("Result");
    expect(within(rows[1]).getAllByRole("cell")[0]).toHaveTextContent("✓");
    expect(within(rows[2]).getAllByRole("cell")[0]).toHaveTextContent("✗ error");
    expect(rows[3]).toHaveTextContent("throws java.lang.ArithmeticException: / by zero");
  });

  it("shows compiler text on a red line, and an exception in place of a value", async () => {
    mockFetch({
      [CODE]: { body: draft() },
      [RUN]: { body: { status: "compile-error", compileErrors: "Solution.java:3: error: ';' expected", cases: [], hidden: null, output: null } },
    });
    show();
    await waitFor(() => expect(editor().value).toBe(STARTER));
    fireEvent.click(screen.getByRole("button", { name: "Run" }));
    expect(await screen.findByText("Does not compile.")).toHaveClass("verdict", "wrong");
    expect(screen.getByText(/';' expected/).tagName).toBe("PRE");
    expect(screen.queryByRole("table", { name: "Results" })).not.toBeInTheDocument();
  });

  it("when runs are not possible yet, keeps the editor and Save, and offers the self-check", async () => {
    mockFetch({ [CODE]: { body: draft({ starter: null }) } });
    show();
    await screen.findByText(/can't run here yet/);
    expect(screen.queryByText(/Run tries the cases/)).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Run" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Submit" })).toBeDisabled();
    expect(editor()).not.toBeDisabled();
    expect(screen.getByRole("heading", { name: "Check your solution" })).toBeInTheDocument();
  });

  it("when the runner is down, says so, counts the code as saved and offers the self-check", async () => {
    mockFetch({ [CODE]: { body: draft() }, [RUN]: { status: 503, body: { status: "unavailable" } } });
    show();
    await waitFor(() => expect(editor().value).toBe(STARTER));
    fireEvent.change(editor(), { target: { value: "class Solution { /* edited */ }" } });
    expect(screen.getByRole("button", { name: "Save" })).toBeEnabled();
    fireEvent.click(screen.getByRole("button", { name: "Run" }));
    expect(await screen.findByText(/The code runner isn't answering/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Save" })).toBeDisabled();
    expect(screen.getByRole("heading", { name: "Check your solution" })).toBeInTheDocument();
  });

  it("says why a run was refused or failed, naming only what the server named", async () => {
    for (const [status, reason, words] of [
      [503, "busy", /busy with another run/],
      [429, "one-at-a-time", /previous run is still going/],
      [429, "too-many", /ten runs in a minute/],
      [400, "too-big", /limited to 32 KB/],
      [400, "something-else", /went wrong on the server/],
      [502, "runner-refused", /could not use this problem's cases/],
      [403, "", /Reload the page/],
      [404, "", /no longer available/],
      [500, "", /went wrong on the server/],
    ] as const) {
      vi.unstubAllGlobals();
      document.body.innerHTML = "";
      mockFetch({ [CODE]: { body: draft() }, [RUN]: { status, body: { status: reason } } });
      show();
      await waitFor(() => expect(editor().value).toBe(STARTER));
      fireEvent.click(screen.getByRole("button", { name: "Run" }));
      expect(await screen.findByText(words)).toBeInTheDocument();
    }
  });

  it("switches to the highlighting editor and back, keeping the code, and remembers the choice", async () => {
    mockFetch({ [CODE]: { body: draft() } });
    show();
    await waitFor(() => expect(editor().value).toBe(STARTER));
    await act(async () => fireEvent.click(screen.getByLabelText("Highlight syntax")));
    await waitFor(() => expect(document.querySelector(".cm-editor")).not.toBeNull());
    expect(document.querySelector(".cm-content")).toHaveTextContent("public int maxSum");
    expect(localStorage.getItem("interview-prep.editor")).toBe("codemirror");
    fireEvent.click(screen.getByLabelText("Highlight syntax"));
    expect(editor().value).toBe(STARTER);
  });

  it("stays locked until the code has loaded, and offers to try again", async () => {
    mockFetch({ [CODE]: { status: 500, body: {} } });
    show();
    expect(await screen.findByText("Could not load your code.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Try loading it again" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Run" })).toBeDisabled();
  });

  it("says a save over 32 KB is too big rather than asking to try again", async () => {
    mockFetch({ [CODE]: { body: draft() } });
    show();
    await waitFor(() => expect(editor().value).toBe(STARTER));
    fireEvent.change(editor(), { target: { value: "x".repeat(10) } });
    vi.unstubAllGlobals();
    mockFetch({ [CODE]: { status: 400, body: { status: "too-big" } } });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    expect(await screen.findByText("Code is limited to 32 KB.")).toBeInTheDocument();
  });

  it("points at the return type when a value looks right but did not pass", async () => {
    mockFetch({
      [CODE]: { body: draft() },
      [RUN]: { body: finished({ cases: [{ ...CASES[0], verdict: "fail", got: "2", error: null, ms: 3 }] }) },
    });
    show();
    await waitFor(() => expect(editor().value).toBe(STARTER));
    fireEvent.click(screen.getByRole("button", { name: "Run" }));
    expect(await screen.findByText(/looks the same as expected/)).toBeInTheDocument();
  });

  it("shows cases that share a name without mixing them up", async () => {
    const errors = vi.spyOn(console, "error").mockImplementation(() => {});
    mockFetch({
      [CODE]: { body: draft() },
      [RUN]: { body: finished({ cases: [
        { ...CASES[0], name: "same", verdict: "pass", got: "2", error: null, ms: 3 },
        { ...CASES[1], name: "same", verdict: "fail", got: "7", error: null, ms: 2 },
      ] }) },
    });
    show();
    await waitFor(() => expect(editor().value).toBe(STARTER));
    fireEvent.click(screen.getByRole("button", { name: "Run" }));
    const table = await screen.findByRole("table", { name: "Results" });
    expect(within(table).getAllByRole("row")).toHaveLength(3);
    expect(errors.mock.calls.flat().join(" ")).not.toMatch(/same key/);
    errors.mockRestore();
  });

  it("ties disabled Run and Submit to the reason they cannot run", async () => {
    mockFetch({ [CODE]: { body: draft({ starter: null }) } });
    show();
    await screen.findByText(/can't run here yet/);
    expect(screen.getByRole("button", { name: "Run" })).toHaveAccessibleDescription(/can't run here yet/);
    expect(screen.getByRole("button", { name: "Submit" })).toHaveAccessibleDescription(/can't run here yet/);
  });
});
