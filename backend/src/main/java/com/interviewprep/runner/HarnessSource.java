package com.interviewprep.runner;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The files a run compiles: the learner's Solution.java exactly as written (so compiler and stack-trace
 * line numbers are theirs), a generated Main.java, and TreeNode.java unless the learner declares their
 * own. A port of check_coding.py's harness with the runner design's three changes (§5): separate files,
 * one generated method pair per case, and structured result lines.
 */
final class HarnessSource {

  static final int CASE_LIMIT_MS = 2000;
  static final int OUTPUT_CAP_BYTES = 16 * 1024;

  private static final Pattern DECLARES_TREE = Pattern.compile("\\bclass\\s+TreeNode\\b");

  private HarnessSource() {}

  static Map<String, String> files(Wire.RunRequest request) {
    Map<String, String> files = new LinkedHashMap<>();
    files.put("Solution.java", request.source());
    files.put("Main.java", main(request));
    if (!DECLARES_TREE.matcher(request.source()).find()) {
      files.put("TreeNode.java", TREENODE);
    }
    return files;
  }

  private static String main(Wire.RunRequest request) {
    Wire.Signature sig = request.signature();
    boolean anyOrder = "any-order".equals(request.compare());
    StringBuilder runs = new StringBuilder();
    StringBuilder methods = new StringBuilder();
    List<Wire.CaseIn> cases = request.cases();
    for (int i = 0; i < cases.size(); i++) {
      Wire.CaseIn c = cases.get(i);
      StringBuilder args = new StringBuilder();
      for (Wire.Param p : sig.params()) {
        if (!args.isEmpty()) {
          args.append(", ");
        }
        args.append(JavaLiterals.literal(c.args().get(p.name()), p.type()));
      }
      runs.append("    run(").append(quote(c.id())).append(", ").append(i).append(", ")
          .append(anyOrder).append(", Main::expected").append(i).append(", Main::actual").append(i).append(");\n");
      methods.append("  static Object expected").append(i).append("() { return ")
          .append(JavaLiterals.literal(c.expected(), sig.returns())).append("; }\n");
      methods.append("  static Object actual").append(i).append("() { return new Solution().")
          .append(sig.method()).append("(").append(args).append("); }\n");
    }
    return MAIN.replace("%LIMIT%", Integer.toString(CASE_LIMIT_MS))
        .replace("%CAP%", Integer.toString(OUTPUT_CAP_BYTES))
        .replace("%RUNS%", runs)
        .replace("%METHODS%", methods);
  }

  /** A case id as a Java string literal in the generated program. */
  private static String quote(String s) {
    StringBuilder out = new StringBuilder("\"");
    for (char ch : s.toCharArray()) {
      switch (ch) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> out.append(ch < 0x20 ? "\\u%04x".formatted((int) ch) : String.valueOf(ch));
      }
    }
    return out.append('"').toString();
  }

  private static final String TREENODE = """
      class TreeNode {
          int val;
          TreeNode left, right;
          TreeNode(int val) { this.val = val; }
      }
      """;

  private static final String MAIN = """
      import java.io.*;
      import java.nio.charset.StandardCharsets;
      import java.util.*;
      import java.util.concurrent.Callable;

      public class Main {
        static final PrintStream OUT = System.out;
        static final CappedStream CAPTURED = new CappedStream(%CAP%);
        static final int LIMIT_MS = %LIMIT%;

        public static void main(String[] args) {
          PrintStream learner = new PrintStream(CAPTURED, true, StandardCharsets.UTF_8);
          System.setOut(learner);
          System.setErr(learner);
          OUT.println("@@START");
          OUT.flush();
      %RUNS%
          OUT.println("@@OUTPUT " + Harness.json(CAPTURED.text()));
          OUT.println("@@END");
          OUT.flush();
          Runtime.getRuntime().halt(0);
        }

        static void run(String id, int n, boolean anyOrder, Callable<Object> expected, Callable<Object> actual) {
          Object[] result = new Object[2];
          long[] took = new long[1];
          Thread t = new Thread(null, () -> {
            long start = System.nanoTime();
            try {
              result[0] = Harness.canon(actual.call(), anyOrder);
            } catch (Throwable e) {
              result[1] = e;
            }
            took[0] = (System.nanoTime() - start) / 1_000_000;
          }, "case-" + n, 16L << 20);
          t.setDaemon(true);
          t.start();
          try {
            t.join(LIMIT_MS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          if (t.isAlive()) {
            OUT.println("@@CASE " + Harness.line(id, "timeout", null, null, LIMIT_MS));
            OUT.println("@@TIMEOUT " + Harness.json(id));
            OUT.flush();
            Runtime.getRuntime().halt(3);
          }
          if (result[1] != null) {
            OUT.println("@@CASE " + Harness.line(id, "error", null, Harness.describe((Throwable) result[1]), took[0]));
            OUT.flush();
            return;
          }
          Object want;
          try {
            want = Harness.canon(expected.call(), anyOrder);
          } catch (Exception e) {
            throw new IllegalStateException(e);
          }
          boolean pass = Objects.equals(want, result[0]);
          OUT.println("@@CASE " + Harness.line(id, pass ? "pass" : "fail", Harness.show(result[0]), null, took[0]));
          OUT.flush();
        }

      %METHODS%
      }

      final class Harness {
        static TreeNode tree(Integer... values) {
          if (values.length == 0 || values[0] == null) return null;
          TreeNode root = new TreeNode(values[0]);
          ArrayDeque<TreeNode> queue = new ArrayDeque<>(List.of(root));
          int i = 1;
          while (!queue.isEmpty() && i < values.length) {
            TreeNode node = queue.poll();
            if (i < values.length && values[i] != null) { node.left = new TreeNode(values[i]); queue.add(node.left); }
            i++;
            if (i < values.length && values[i] != null) { node.right = new TreeNode(values[i]); queue.add(node.right); }
            i++;
          }
          return root;
        }

        /** check_coding.py's comparable form: arrays and lists become lists, trees their level order, doubles rounded. */
        static Object canon(Object value, boolean anyOrder) {
          Object c = canon(value);
          if (anyOrder && c instanceof List<?> list) {
            List<Object> sorted = new ArrayList<>(list);
            sorted.sort(Comparator.comparing(String::valueOf));
            return sorted;
          }
          return c;
        }

        static Object canon(Object value) {
          if (value == null) return null;
          if (value instanceof Double d) return Math.round(d * 1e6) / 1e6;
          if (value instanceof TreeNode t) {
            List<Object> out = new ArrayList<>();
            List<TreeNode> level = new ArrayList<>();
            level.add(t);
            while (!level.isEmpty()) {
              List<TreeNode> next = new ArrayList<>();
              for (TreeNode n : level) {
                out.add(n == null ? null : n.val);
                if (n != null) { next.add(n.left); next.add(n.right); }
              }
              level = next;
            }
            while (!out.isEmpty() && out.get(out.size() - 1) == null) out.remove(out.size() - 1);
            return out;
          }
          if (value.getClass().isArray()) {
            List<Object> out = new ArrayList<>();
            int n = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < n; i++) out.add(canon(java.lang.reflect.Array.get(value, i)));
            return out;
          }
          if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            for (Object o : list) out.add(canon(o));
            return out;
          }
          if (value instanceof Character ch) return String.valueOf(ch);
          return value;
        }

        /** A canonical value in the bundle's display style: [2, 1, 5], "abc", null, true, 9, 2.5. */
        static String show(Object c) {
          if (c == null) return "null";
          if (c instanceof String s) return json(s);
          if (c instanceof List<?> list) {
            StringJoiner j = new StringJoiner(", ", "[", "]");
            for (Object o : list) j.add(show(o));
            return j.toString();
          }
          return String.valueOf(c);
        }

        static String json(String s) {
          StringBuilder out = new StringBuilder("\\"");
          for (char ch : s.toCharArray()) {
            switch (ch) {
              case '"' -> out.append("\\\\\\"");
              case '\\\\' -> out.append("\\\\\\\\");
              case '\\n' -> out.append("\\\\n");
              case '\\r' -> out.append("\\\\r");
              case '\\t' -> out.append("\\\\t");
              default -> out.append(ch < 0x20 ? String.format("\\\\u%04x", (int) ch) : String.valueOf(ch));
            }
          }
          return out.append('"').toString();
        }

        static String line(String id, String verdict, String got, String error, long ms) {
          return "{\\"case\\":" + json(id) + ",\\"verdict\\":" + json(verdict)
              + ",\\"got\\":" + (got == null ? "null" : json(got))
              + ",\\"error\\":" + (error == null ? "null" : json(error)) + ",\\"ms\\":" + ms + "}";
        }

        static String describe(Throwable e) {
          String where = "";
          for (StackTraceElement f : e.getStackTrace()) {
            if ("Solution.java".equals(f.getFileName())) { where = " at Solution.java:" + f.getLineNumber(); break; }
          }
          String message = e.getMessage();
          return e.getClass().getSimpleName() + (message == null ? "" : ": " + message) + where;
        }
      }

      final class CappedStream extends OutputStream {
        private final int cap;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private boolean cut;

        CappedStream(int cap) { this.cap = cap; }

        @Override public synchronized void write(int b) {
          if (bytes.size() < cap) bytes.write(b); else cut = true;
        }

        @Override public synchronized void write(byte[] b, int off, int len) {
          int room = cap - bytes.size();
          if (room > 0) bytes.write(b, off, Math.min(room, len));
          if (len > room) cut = true;
        }

        synchronized String text() {
          return bytes.toString(StandardCharsets.UTF_8) + (cut ? "\\n... output cut at " + cap / 1024 + " KB" : "");
        }
      }
      """;
}
