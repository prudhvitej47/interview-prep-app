import { useLayoutEffect, useRef } from "react";
import { keyEdit } from "./indent";

export type EditorProps = { value: string; onChange: (value: string) => void; label: string; disabled?: boolean };

/**
 * The default editor: a monospace textarea, like the plain editors interviews use. Tab indents and
 * Enter keeps the indent. Shift+Tab, or Esc then Tab, moves on, so the keyboard is never trapped.
 */
export function PlainEditor({ value, onChange, label, disabled }: EditorProps) {
  const box = useRef<HTMLTextAreaElement>(null);
  const caret = useRef<number | null>(null);
  const released = useRef(false);

  useLayoutEffect(() => {
    if (caret.current !== null && box.current) {
      box.current.setSelectionRange(caret.current, caret.current);
      caret.current = null;
    }
  }, [value]);

  function onKeyDown(e: React.KeyboardEvent<HTMLTextAreaElement>) {
    if (e.key === "Escape") {
      released.current = true;
      return;
    }
    const indenting = e.key === "Tab" && !e.shiftKey && !released.current;
    const newline = e.key === "Enter" && !e.shiftKey && !e.nativeEvent.isComposing;
    if (e.key !== "Tab") released.current = false;
    if (!indenting && !newline) return;
    e.preventDefault();
    const t = e.currentTarget;
    const edit = keyEdit(t.value, t.selectionStart, t.selectionEnd, indenting ? "Tab" : "Enter");
    // The browser's own insert keeps Cmd+Z working (setting the value from code wipes undo) and fires
    // the input event that reaches onChange. Where editing commands are missing, set it directly.
    const inserted = edit.value.slice(t.selectionStart, edit.caret);
    if (typeof document.execCommand === "function" && document.execCommand("insertText", false, inserted)) return;
    caret.current = edit.caret;
    onChange(edit.value);
  }

  return (
    <textarea
      ref={box}
      className="code-input"
      aria-label={label}
      value={value}
      rows={16}
      wrap="off"
      spellCheck={false}
      autoCapitalize="off"
      autoCorrect="off"
      disabled={disabled}
      onKeyDown={onKeyDown}
      onFocus={() => {
        released.current = false;
      }}
      onChange={(e) => onChange(e.target.value)}
    />
  );
}
