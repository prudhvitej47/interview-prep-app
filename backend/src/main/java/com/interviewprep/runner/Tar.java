package com.interviewprep.runner;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * A minimal ustar archive of small text files. The sandbox reads its source as a tar on stdin, so no
 * host path is ever shared with a container (runner design §4).
 */
final class Tar {

  private Tar() {}

  static byte[] of(Map<String, String> files) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (Map.Entry<String, String> f : files.entrySet()) {
      byte[] body = f.getValue().getBytes(StandardCharsets.UTF_8);
      out.writeBytes(header(f.getKey(), body.length));
      out.writeBytes(body);
      out.writeBytes(new byte[(512 - body.length % 512) % 512]);
    }
    out.writeBytes(new byte[1024]); // two empty blocks end the archive
    return out.toByteArray();
  }

  private static byte[] header(String name, int size) {
    byte[] h = new byte[512];
    put(h, 0, 100, name);
    put(h, 100, 8, "0000644");
    put(h, 108, 8, "0000000");
    put(h, 116, 8, "0000000");
    put(h, 124, 12, "%011o".formatted(size));
    put(h, 136, 12, "%011o".formatted(0));
    for (int i = 148; i < 156; i++) {
      h[i] = ' '; // the checksum is computed with its own field as spaces
    }
    h[156] = '0';
    put(h, 257, 6, "ustar");
    put(h, 263, 2, "00");
    int sum = 0;
    for (byte b : h) {
      sum += b & 0xff;
    }
    put(h, 148, 7, "%06o".formatted(sum));
    h[155] = ' ';
    return h;
  }

  private static void put(byte[] h, int at, int length, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
    if (bytes.length > length) {
      throw new IllegalArgumentException("tar field too long: " + value);
    }
    System.arraycopy(bytes, 0, h, at, bytes.length);
  }
}
