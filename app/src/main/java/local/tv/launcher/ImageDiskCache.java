package local.tv.launcher;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Only complete, validated entries are published. A cache failure never prevents image delivery.
 */
final class ImageDiskCache {
  interface Validator {
    boolean valid(byte[] bytes);
  }

  private final File directory;
  private final int entryLimit;
  private final long budget;
  private final Validator validator;

  ImageDiskCache(File directory, int entryLimit, long budget, Validator validator) {
    this.directory = directory;
    this.entryLimit = entryLimit;
    this.budget = budget;
    this.validator = validator;
    directory.mkdirs();
  }

  synchronized byte[] read(String key) {
    File file = new File(directory, key);
    if (!file.isFile()) return null;
    try {
      if (file.length() <= 0 || file.length() > entryLimit) throw new IOException();
      byte[] bytes;
      try (InputStream in = new FileInputStream(file)) {
        bytes = bounded(in, entryLimit);
      }
      if (!validator.valid(bytes)) throw new IOException();
      file.setLastModified(System.currentTimeMillis());
      return bytes;
    } catch (IOException | RuntimeException invalid) {
      file.delete();
      return null;
    }
  }

  synchronized void write(String key, byte[] bytes) {
    if (bytes.length == 0 || bytes.length > entryLimit) return;
    File pending = null;
    try {
      pending = File.createTempFile("pending-", ".tmp", directory);
      try (FileOutputStream out = new FileOutputStream(pending)) {
        out.write(bytes);
      }
      Files.move(
          pending.toPath(),
          new File(directory, key).toPath(),
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING);
      trim();
    } catch (IOException ignored) {
      // Entries are disposable. Preserve any previous complete file on write failure.
    } finally {
      if (pending != null) pending.delete();
    }
  }

  private void trim() {
    File[] files = directory.listFiles(file -> !file.getName().endsWith(".tmp"));
    if (files == null) return;
    Arrays.sort(files, Comparator.comparingLong(File::lastModified));
    long bytes = 0;
    for (File file : files) bytes += file.length();
    for (File file : files) {
      if (bytes <= budget) break;
      long size = file.length();
      if (file.delete()) bytes -= size;
    }
  }

  static byte[] bounded(InputStream in, int limit) throws IOException {
    if (in == null) throw new IOException("Image unavailable");
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    int count;
    while ((count = in.read(buffer)) != -1) {
      if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
      if (out.size() + count > limit) throw new IOException("Image too large");
      out.write(buffer, 0, count);
    }
    return out.toByteArray();
  }
}
