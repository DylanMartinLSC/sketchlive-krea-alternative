package dev.sketchlive.core;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.*;

/** OpenAI CLIP byte-level BPE. SD2 uses BOS 49406, EOS 49407 and PAD 0. */
public final class ClipTokenizer {
  private final Map<String, Integer> vocab;
  private final Map<String, Integer> ranks = new HashMap<>();
  private final String[] bytes = new String[256];
  private final Map<String, List<String>> cache = new HashMap<>();
  private static final Pattern TOKENS =
      Pattern.compile(
          "<\\|startoftext\\|>|<\\|endoftext\\|>|!|'s|'t|'re|'ve|'m|'ll|'d|[\\p{L}]+|[\\p{N}]|[^\\s\\p{L}\\p{N}!]+",
          Pattern.CASE_INSENSITIVE);

  public ClipTokenizer(Reader vocabulary, Reader merges) throws IOException {
    vocab = new Gson().fromJson(vocabulary, new TypeToken<Map<String, Integer>>() {}.getType());
    List<Integer> values = new ArrayList<>();
    for (int i = 33; i <= 126; i++) values.add(i);
    for (int i = 161; i <= 172; i++) values.add(i);
    for (int i = 174; i <= 255; i++) values.add(i);
    int extra = 0;
    for (int i = 0; i < 256; i++)
      if (!values.contains(i)) bytes[i] = new String(Character.toChars(256 + extra++));
    for (int i : values) bytes[i] = new String(Character.toChars(i));
    BufferedReader reader = new BufferedReader(merges);
    String line;
    int rank = 0;
    while ((line = reader.readLine()) != null) {
      if (line.startsWith("#") || line.trim().isEmpty()) continue;
      ranks.put(line.trim(), rank++);
    }
  }

  private List<String> bpe(String word) {
    List<String> hit = cache.get(word);
    if (hit != null) return hit;
    List<String> parts = new ArrayList<>();
    for (int c : word.codePoints().toArray()) parts.add(new String(Character.toChars(c)));
    parts.set(parts.size() - 1, parts.get(parts.size() - 1) + "</w>");
    while (parts.size() > 1) {
      String best = null;
      int bestRank = Integer.MAX_VALUE;
      for (int i = 0; i < parts.size() - 1; i++) {
        String pair = parts.get(i) + " " + parts.get(i + 1);
        int r = ranks.getOrDefault(pair, Integer.MAX_VALUE);
        if (r < bestRank) {
          best = pair;
          bestRank = r;
        }
      }
      if (best == null) break;
      List<String> merged = new ArrayList<>();
      for (int i = 0; i < parts.size(); i++) {
        if (i + 1 < parts.size() && (parts.get(i) + " " + parts.get(i + 1)).equals(best))
          merged.add(parts.get(i) + parts.get(++i));
        else merged.add(parts.get(i));
      }
      parts = merged;
    }
    if (cache.size() > 512) cache.clear();
    cache.put(word, parts);
    return parts;
  }

  public long[] encode(String prompt) {
    String text =
        Normalizer.normalize(prompt, Normalizer.Form.NFC)
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replaceAll("[\\p{Z}\\s]+", " ")
            .trim()
            .toLowerCase(Locale.ROOT);
    long[] ids = new long[77];
    ids[0] = 49406;
    int pos = 1;
    Matcher matcher = TOKENS.matcher(text);
    outer:
    while (matcher.find()) {
      String token = matcher.group();
      if (token.equals("!")) {
        ids[pos++] = 0;
        if (pos == 76) break;
        continue;
      }
      if (token.equals("<|startoftext|>") || token.equals("<|endoftext|>")) {
        ids[pos++] = token.equals("<|startoftext|>") ? 49406 : 49407;
        if (pos == 76) break;
        continue;
      }
      StringBuilder encoded = new StringBuilder();
      for (byte b : token.getBytes(StandardCharsets.UTF_8)) encoded.append(bytes[b & 255]);
      for (String part : bpe(encoded.toString())) {
        ids[pos++] = vocab.getOrDefault(part, 49407);
        if (pos == 76) break outer;
      }
    }
    ids[pos] = 49407;
    return ids;
  }
}
