package dev.sketchlive.android;

import android.content.Context;
import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

/** Pinned files, SHA-256 verification, resumable HTTPS download into app-private storage. */
final class ModelStore {
  static final String REVISION = "30015d1ff375a67d2ade66f9a43339adf5cd5639";
  static final String BASE =
      "https://huggingface.co/xay2001/sd-turbo-android/resolve/" + REVISION + "/sd-turbo-v1/";
  final File directory;
  private final List<JsonObject> files = new ArrayList<>();
  private volatile boolean cancelled;
  private volatile HttpURLConnection connection;

  ModelStore(Context context) throws IOException {
    directory = new File(context.getNoBackupFilesDir(), "sd-turbo-v1");
    if (!directory.exists() && !directory.mkdirs())
      throw new IOException("Cannot create model storage");
    try (Reader reader =
        new InputStreamReader(
            context.getAssets().open("model-manifest.json"), StandardCharsets.UTF_8)) {
      for (JsonElement element :
          JsonParser.parseReader(reader).getAsJsonObject().getAsJsonArray("files")) {
        JsonObject item = element.getAsJsonObject();
        if (!item.get("name").getAsString().contains("_512")) files.add(item);
      }
    }
  }

  boolean ready() {
    if (!new File(directory, "verified-" + REVISION).exists()) return false;
    for (JsonObject item : files)
      if (new File(directory, item.get("name").getAsString()).length()
          != item.get("size").getAsLong()) return false;
    return true;
  }

  void cancel() {
    cancelled = true;
    HttpURLConnection c = connection;
    if (c != null) c.disconnect();
  }

  void resetCancellation() {
    cancelled = false;
  }

  private void check() {
    if (cancelled) throw new CancellationException("Download paused; tap Download to resume");
  }

  void download(Consumer<String> progress) throws Exception {
    check();
    long total = 0, complete = 0;
    for (JsonObject item : files) total += item.get("size").getAsLong();
    for (JsonObject item : files) {
      check();
      String name = item.get("name").getAsString();
      long size = item.get("size").getAsLong();
      File destination = new File(directory, name), partial = new File(directory, name + ".part");
      if (destination.length() == size
          && hash(destination).equals(item.get("sha256").getAsString())) {
        complete += size;
        continue;
      }
      if (destination.exists() && !destination.delete())
        throw new IOException("Cannot replace " + name);
      if (partial.length() > size && !partial.delete())
        throw new IOException("Cannot reset " + name);
      long offset = partial.length();
      if (offset < size) {
        HttpURLConnection c = (HttpURLConnection) new URL(BASE + name).openConnection();
        c.setConnectTimeout(30000);
        c.setReadTimeout(30000);
        c.setRequestProperty("Accept-Encoding", "identity");
        if (offset > 0) c.setRequestProperty("Range", "bytes=" + offset + "-");
        connection = c;
        try {
          int code = c.getResponseCode();
          if (code != 200 && code != 206)
            throw new IOException(
                "Download HTTP " + code + " for " + name + ". Check your connection and retry.");
          if (code == 200) offset = 0;
          if (code == 206
              && !Optional.ofNullable(c.getHeaderField("Content-Range"))
                  .orElse("")
                  .startsWith("bytes " + offset + "-"))
            throw new IOException("Invalid download range");
          try (InputStream in = c.getInputStream();
              OutputStream out =
                  new BufferedOutputStream(new FileOutputStream(partial, offset > 0))) {
            byte[] buffer = new byte[256 * 1024];
            int n;
            long tick = 0;
            while ((n = in.read(buffer)) != -1) {
              check();
              offset += n;
              if (offset > size) throw new IOException("Unexpected model size");
              out.write(buffer, 0, n);
              if (System.currentTimeMillis() - tick > 300) {
                progress.accept(
                    String.format(
                        Locale.US,
                        "Downloading models · %.0f%% · %.0f / %.0f MB",
                        100.0 * (complete + offset) / total,
                        (complete + offset) / 1e6,
                        total / 1e6));
                tick = System.currentTimeMillis();
              }
            }
          }
        } finally {
          connection = null;
          c.disconnect();
        }
      }
      check();
      progress.accept("Verifying " + name + "…");
      if (partial.length() != size)
        throw new IOException("Incomplete download; tap Download to resume");
      if (!hash(partial).equals(item.get("sha256").getAsString())) {
        if (!partial.delete()) throw new IOException("Cannot discard damaged model");
        throw new IOException("Model checksum mismatch; tap Download to retry");
      }
      if (!partial.renameTo(destination)) throw new IOException("Cannot install " + name);
      complete += size;
    }
    check();
    try (FileOutputStream out = new FileOutputStream(new File(directory, "verified-" + REVISION))) {
      out.write(REVISION.getBytes(StandardCharsets.UTF_8));
    }
  }

  private String hash(File file) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    byte[] buffer = new byte[256 * 1024];
    try (InputStream in = new FileInputStream(file)) {
      int n;
      while ((n = in.read(buffer)) != -1) {
        check();
        digest.update(buffer, 0, n);
      }
    }
    StringBuilder out = new StringBuilder();
    for (byte b : digest.digest()) out.append(String.format(Locale.ROOT, "%02x", b & 255));
    return out.toString();
  }
}
