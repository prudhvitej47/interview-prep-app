import { useEffect, useRef } from "react";
import { Annotation, Compartment, EditorState } from "@codemirror/state";
import { EditorView, keymap, lineNumbers } from "@codemirror/view";
import { defaultKeymap, history, historyKeymap, indentWithTab } from "@codemirror/commands";
import { bracketMatching, HighlightStyle, indentUnit, syntaxHighlighting } from "@codemirror/language";
import { java } from "@codemirror/lang-java";
import { tags } from "@lezer/highlight";
import type { EditorProps } from "./PlainEditor";

// Colours come from the page's own variables, so light and dark follow without a second theme.
const highlight = HighlightStyle.define([
  { tag: tags.keyword, fontWeight: "600" },
  { tag: [tags.string, tags.number, tags.bool, tags.null, tags.character], color: "var(--code-literal)" },
  { tag: [tags.comment, tags.lineComment, tags.blockComment], color: "var(--muted)", fontStyle: "italic" },
]);

// Marks a change made from outside (new code from the page), so it is not reported back as an edit.
const fromOutside = Annotation.define<boolean>();

/**
 * The optional editor (runner design decision 2): CodeMirror 6 with Java highlighting, no autocomplete.
 * Lazy-loaded, so it never weighs on the page for a learner who keeps the plain field. Esc then Tab
 * leaves it (CodeMirror's own rule for indentWithTab).
 */
export default function CodeMirrorEditor({ value, onChange, label, disabled }: EditorProps) {
  const host = useRef<HTMLDivElement>(null);
  const view = useRef<EditorView | null>(null);
  const report = useRef(onChange);
  const editable = useRef(new Compartment());

  useEffect(() => {
    report.current = onChange;
  }, [onChange]);

  useEffect(() => {
    const v = new EditorView({
      parent: host.current!,
      state: EditorState.create({
        doc: value,
        extensions: [
          lineNumbers(),
          history(),
          bracketMatching(),
          indentUnit.of("    "),
          EditorState.tabSize.of(4),
          keymap.of([...defaultKeymap, ...historyKeymap, indentWithTab]),
          java(),
          syntaxHighlighting(highlight),
          EditorView.contentAttributes.of({ "aria-label": label, spellcheck: "false", autocapitalize: "off" }),
          editable.current.of(EditorView.editable.of(!disabled)),
          EditorView.updateListener.of((u) => {
            if (u.docChanged && !u.transactions.some((t) => t.annotation(fromOutside))) {
              report.current(u.state.doc.toString());
            }
          }),
        ],
      }),
    });
    view.current = v;
    return () => v.destroy();
    // Created once; value and disabled are synced below.
  }, []);

  useEffect(() => {
    const v = view.current;
    if (v && v.state.doc.toString() !== value) {
      v.dispatch({ changes: { from: 0, to: v.state.doc.length, insert: value }, annotations: fromOutside.of(true) });
    }
  }, [value]);

  useEffect(() => {
    view.current?.dispatch({ effects: editable.current.reconfigure(EditorView.editable.of(!disabled)) });
  }, [disabled]);

  return <div ref={host} className="code-input code-mirror" />;
}
