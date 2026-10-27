package dev.sketchlive.android;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.text.*;
import android.view.*;
import android.widget.*;
import dev.sketchlive.core.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final Handler main = new Handler(Looper.getMainLooper());
  private final List<Bitmap> history = new ArrayList<>();
  private static final int BG = 0xff10141c,
      PANEL = 0xff1c2330,
      TEXT = 0xffeaf0fa,
      MUTED = 0xffaab8cd,
      ACCENT = 0xff8ea4ff;
  private SketchView sketch;
  private ImageView result, thumbnail;
  private TextView status, creativityLabel;
  private EditText prompt, seedInput;
  private Spinner style;
  private SeekBar creativity;
  private Switch live;
  private Button generate, enhance, download, stop, drawTab, resultTab;
  private LinearLayout historyRow;
  private ProgressBar progress;
  private FrameLayout stage;
  private ModelStore store;
  private volatile DiffusionEngine engine;
  private boolean busy, downloading, foreground, destroyed;
  private volatile boolean stopRequested;
  private long revision;
  private Bitmap latest;
  private String currentStyle = "Cinematic";
  private final Runnable autoGenerate = () -> generate(false, true);
  private final String[] styles = {
    "None", "Cinematic", "Photo", "Watercolor", "3D render", "Anime"
  };
  private final String[] suffixes = {
    "",
    "cinematic film still, dramatic lighting, depth of field",
    "photorealistic photo, natural light, highly detailed",
    "watercolor painting, paper texture, soft washes",
    "3d render, soft studio lighting, detailed materials",
    "anime style, cel shading, vibrant colors"
  };

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    try {
      store = new ModelStore(this);
    } catch (Exception e) {
      new AlertDialog.Builder(this)
          .setMessage(e.toString())
          .setPositiveButton("Close", (d, w) -> finish())
          .show();
      return;
    }
    buildUi();
    restore();
    refresh();
    if (!store.ready()) setStatus("Download the free model once, then draw completely offline.");
  }

  private int dp(float n) {
    return Math.round(n * getResources().getDisplayMetrics().density);
  }

  private GradientDrawable surface(int color, int radius) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(radius));
    return d;
  }

  private LinearLayout column() {
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(LinearLayout.VERTICAL);
    return l;
  }

  private LinearLayout row() {
    LinearLayout l = new LinearLayout(this);
    l.setGravity(Gravity.CENTER_VERTICAL);
    return l;
  }

  private TextView text(String value, int size, int color) {
    TextView v = new TextView(this);
    v.setText(value);
    v.setTextSize(size);
    v.setTextColor(color);
    return v;
  }

  private Button button(String label, Runnable click) {
    Button b = new Button(this);
    b.setText(label);
    b.setTextSize(12);
    b.setAllCaps(false);
    b.setTextColor(TEXT);
    b.setMinWidth(0);
    b.setMinimumWidth(0);
    b.setPadding(dp(8), 0, dp(8), 0);
    b.setOnClickListener(v -> click.run());
    return b;
  }

  private void cell(LinearLayout row, View v) {
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(48), 1);
    p.setMargins(dp(2), 0, dp(2), 0);
    row.addView(v, p);
  }

  private void gap(LinearLayout l, int h) {
    l.addView(new View(this), new LinearLayout.LayoutParams(1, dp(h)));
  }

  private void label(LinearLayout l, String title) {
    TextView t = text(title, 13, MUTED);
    t.setPadding(0, dp(14), 0, dp(5));
    l.addView(t);
  }

  private void buildUi() {
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.setBackgroundColor(BG);
    LinearLayout root = column();
    root.setPadding(dp(16), dp(12), dp(16), dp(24));
    scroll.addView(root);
    setContentView(scroll);
    // Target 35 edge-to-edge: keep controls out of status/navigation bars and display cutouts.
    scroll.setOnApplyWindowInsetsListener(
        (v, insets) -> {
          if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets bars =
                insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
          } else
            v.setPadding(
                insets.getSystemWindowInsetLeft(),
                insets.getSystemWindowInsetTop(),
                insets.getSystemWindowInsetRight(),
                insets.getSystemWindowInsetBottom());
          return insets;
        });
    LinearLayout title = row();
    TextView brand = text("SketchLive", 27, TEXT);
    brand.setTypeface(null, Typeface.BOLD);
    title.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
    title.addView(button("About", this::about));
    root.addView(title);
    root.addView(text("Your sketch. Your phone. Your imagination.", 13, MUTED));
    label(root, "DESCRIBE YOUR IMAGE");
    prompt = new EditText(this);
    prompt.setTextColor(TEXT);
    prompt.setHintTextColor(MUTED);
    prompt.setTextSize(15);
    prompt.setSingleLine(false);
    prompt.setMaxLines(3);
    prompt.setHint("A magical mushroom cottage in a mossy forest…");
    prompt.setBackground(surface(PANEL, 12));
    prompt.setPadding(dp(12), dp(10), dp(12), dp(10));
    root.addView(prompt);
    LinearLayout settings = row();
    style = new Spinner(this);
    ArrayAdapter<String> adapter =
        new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, styles);
    style.setAdapter(adapter);
    settings.addView(style, new LinearLayout.LayoutParams(0, dp(48), 1));
    seedInput = new EditText(this);
    seedInput.setSingleLine();
    seedInput.setTextColor(TEXT);
    seedInput.setTextSize(13);
    seedInput.setInputType(2);
    seedInput.setHint("Seed");
    settings.addView(seedInput, new LinearLayout.LayoutParams(dp(85), dp(48)));
    settings.addView(
        button(
            "Reroll",
            () -> {
              seedInput.setText(String.valueOf(new Random().nextInt(Integer.MAX_VALUE)));
              changed();
            }));
    root.addView(settings);
    creativityLabel = text("Creativity · 0.85", 13, MUTED);
    root.addView(creativityLabel);
    creativity = new SeekBar(this);
    creativity.setMax(75);
    creativity.setProgress(65);
    root.addView(creativity);
    LinearLayout actions = row();
    generate = button("Generate", () -> generate(false, false));
    enhance = button("Enhance", () -> generate(true, false));
    stop = button("Stop", this::stopWork);
    cell(actions, generate);
    cell(actions, enhance);
    cell(actions, stop);
    root.addView(actions);
    live = new Switch(this);
    live.setText("Live · update after each stroke");
    live.setTextColor(TEXT);
    live.setTextSize(14);
    root.addView(live);
    progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
    progress.setIndeterminate(true);
    root.addView(progress, new LinearLayout.LayoutParams(-1, dp(3)));
    status = text("Ready to draw", 13, MUTED);
    status.setPadding(0, dp(6), 0, dp(6));
    status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    root.addView(status);
    download = button("Download model · 2.42 GB", this::confirmDownload);
    root.addView(download, new LinearLayout.LayoutParams(-1, dp(48)));
    LinearLayout tabs = row();
    drawTab = button("Sketch", () -> showResult(false));
    resultTab = button("Result", () -> showResult(true));
    cell(tabs, drawTab);
    cell(tabs, resultTab);
    root.addView(tabs);
    stage =
        new FrameLayout(this) {
          @Override
          protected void onMeasure(int w, int h) {
            super.onMeasure(
                w, MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(w), MeasureSpec.EXACTLY));
          }
        };
    sketch = new SketchView(this);
    stage.addView(sketch, new FrameLayout.LayoutParams(-1, -1));
    result = new ImageView(this);
    result.setScaleType(ImageView.ScaleType.FIT_CENTER);
    result.setBackgroundColor(PANEL);
    result.setContentDescription("Generated image");
    stage.addView(result, new FrameLayout.LayoutParams(-1, -1));
    result.setVisibility(View.GONE);
    root.addView(stage, new LinearLayout.LayoutParams(-1, -2));
    HorizontalScrollView colors = new HorizontalScrollView(this);
    colors.setHorizontalScrollBarEnabled(false);
    LinearLayout swatches = row();
    for (String hex :
        new String[] {
          "#000000", "#ffffff", "#142b25", "#173442", "#294a60", "#38bdf8", "#365b32", "#16a34a",
          "#d9bd88", "#ffdc77", "#df8436", "#b52d35", "#7c3aed"
        }) {
      int color = Color.parseColor(hex);
      View swatch = new View(this);
      swatch.setBackground(surface(color, 24));
      swatch.setContentDescription("Brush color " + hex);
      swatch.setOnClickListener(
          v -> {
            sketch.color = color;
            sketch.eraser = false;
            showResult(false);
          });
      LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(30), dp(30));
      p.setMargins(dp(4), dp(10), dp(4), dp(8));
      swatches.addView(swatch, p);
    }
    colors.addView(swatches);
    root.addView(colors);
    LinearLayout tools = row();
    cell(
        tools,
        button(
            "Brush",
            () -> {
              sketch.eraser = false;
              showResult(false);
            }));
    cell(
        tools,
        button(
            "Eraser",
            () -> {
              sketch.eraser = true;
              showResult(false);
            }));
    cell(
        tools,
        button(
            "Fill",
            () -> {
              sketch.fill();
              showResult(false);
            }));
    cell(
        tools,
        button(
            "Clear",
            () ->
                new AlertDialog.Builder(this)
                    .setMessage("Clear the sketch?")
                    .setNegativeButton("Keep", null)
                    .setPositiveButton(
                        "Clear",
                        (d, w) -> {
                          sketch.clear();
                          showResult(false);
                        })
                    .show()));
    root.addView(tools);
    LinearLayout size = row();
    size.addView(text("Brush size", 12, MUTED));
    SeekBar brush = new SeekBar(this);
    brush.setMax(116);
    brush.setProgress(18);
    size.addView(brush, new LinearLayout.LayoutParams(0, dp(38), 1));
    root.addView(size);
    brush.setOnSeekBarChangeListener(
        new SeekBar.OnSeekBarChangeListener() {
          public void onProgressChanged(SeekBar b, int n, boolean user) {
            sketch.brushSize = n + 4;
          }

          public void onStartTrackingTouch(SeekBar b) {}

          public void onStopTrackingTouch(SeekBar b) {}
        });
    LinearLayout editing = row();
    cell(editing, button("Undo", sketch::undo));
    cell(editing, button("Redo", sketch::redo));
    cell(
        editing,
        button(
            "Import",
            () -> {
              Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
              i.setType("image/*");
              i.addCategory(Intent.CATEGORY_OPENABLE);
              startActivityForResult(i, 20);
            }));
    cell(
        editing,
        button(
            "Use result",
            () -> {
              if (latest != null) {
                sketch.place(latest);
                showResult(false);
              }
            }));
    root.addView(editing);
    label(root, "LATEST RESULT · TAP TO EXPAND");
    LinearLayout output = row();
    thumbnail = new ImageView(this);
    thumbnail.setScaleType(ImageView.ScaleType.FIT_CENTER);
    thumbnail.setBackground(surface(PANEL, 8));
    thumbnail.setContentDescription("Latest result preview");
    thumbnail.setOnClickListener(v -> showResult(true));
    output.addView(thumbnail, new LinearLayout.LayoutParams(dp(100), dp(100)));
    LinearLayout exports = column();
    exports.addView(button("Save PNG", () -> export(false)));
    exports.addView(button("Share", () -> export(true)));
    output.addView(exports, new LinearLayout.LayoutParams(0, -2, 1));
    root.addView(output);
    label(root, "RECENT RESULTS · STORED ONLY ON THIS PHONE");
    HorizontalScrollView historyScroll = new HorizontalScrollView(this);
    historyRow = row();
    historyScroll.addView(historyRow);
    root.addView(historyScroll);
    gap(root, 12);
    root.addView(
        text(
            "384 × 384 · one-step local diffusion\n"
                + "First generation loads the model. Speed depends on your phone.",
            12,
            MUTED));
    sketch.changed = this::changed;
    prompt.addTextChangedListener(
        new TextWatcher() {
          public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

          public void onTextChanged(CharSequence s, int start, int before, int count) {
            changed();
          }

          public void afterTextChanged(Editable e) {}
        });
    creativity.setOnSeekBarChangeListener(
        new SeekBar.OnSeekBarChangeListener() {
          public void onProgressChanged(SeekBar bar, int value, boolean user) {
            creativityLabel.setText(
                String.format(Locale.US, "Creativity · %.2f", (value + 20) / 100f));
            if (user) changed();
          }

          public void onStartTrackingTouch(SeekBar bar) {}

          public void onStopTrackingTouch(SeekBar bar) {}
        });
    style.setOnItemSelectedListener(
        new AdapterView.OnItemSelectedListener() {
          public void onItemSelected(AdapterView<?> a, View v, int n, long id) {
            currentStyle = styles[n];
            changed();
          }

          public void onNothingSelected(AdapterView<?> a) {}
        });
    seedInput.setOnFocusChangeListener(
        (v, focus) -> {
          if (!focus) changed();
        });
    live.setOnCheckedChangeListener(
        (v, on) -> {
          if (on && !store.ready()) {
            live.setChecked(false);
            confirmDownload();
          } else if (on) changed();
          else main.removeCallbacks(autoGenerate);
        });
    showResult(false);
  }

  private void showResult(boolean show) {
    if (show && latest == null) {
      toast("Generate an image first");
      return;
    }
    result.setVisibility(show ? View.VISIBLE : View.GONE);
    sketch.setVisibility(show ? View.GONE : View.VISIBLE);
    drawTab.setTextColor(show ? MUTED : ACCENT);
    resultTab.setTextColor(show ? ACCENT : MUTED);
  }

  private void changed() {
    revision++;
    main.removeCallbacks(autoGenerate);
    if (live.isChecked() && foreground && !busy) main.postDelayed(autoGenerate, 650);
  }

  private void refresh() {
    boolean ready = store.ready();
    generate.setEnabled(ready && !busy);
    enhance.setEnabled(ready && !busy);
    download.setVisibility(ready ? View.GONE : View.VISIBLE);
    download.setEnabled(!busy);
    stop.setEnabled(busy);
    progress.setVisibility(busy ? View.VISIBLE : View.GONE);
  }

  private void setStatus(String value) {
    if (!destroyed) status.setText(value);
  }

  private void postStatus(String value) {
    main.post(() -> setStatus(value));
  }

  private void toast(String value) {
    Toast.makeText(this, value, Toast.LENGTH_SHORT).show();
  }

  private void confirmDownload() {
    if (busy) return;
    new AlertDialog.Builder(this)
        .setTitle("Download the local model")
        .setMessage(
            "One-time download: 2.42 GB. Allow at least 5 GB of free storage. Wi-Fi is recommended."
                + " Generation runs only on this phone after setup; no account or paid API.\n\n"
                + "SD-Turbo weights use Stability AI's license; the app code is MIT. See About for"
                + " the model license.\n\n"
                + "Download can be paused and resumed.")
        .setNegativeButton("Later", null)
        .setNeutralButton(
            "Model license",
            (d, w) ->
                startActivity(
                    new Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse(
                            "https://huggingface.co/stabilityai/sd-turbo/blob/main/LICENSE"))))
        .setPositiveButton("Download", (d, w) -> download())
        .show();
  }

  private void download() {
    if (store.directory.getUsableSpace() < Math.max(100_000_000L, 2_500_000_000L - partialSize())) {
      toast("Free more storage before downloading");
      return;
    }
    busy = true;
    downloading = true;
    store.resetCancellation();
    refresh();
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    worker.execute(
        () -> {
          String message;
          try {
            store.download(this::postStatus);
            message = "Model ready. You can now generate offline.";
          } catch (Exception e) {
            message =
                e instanceof CancellationException
                    ? e.getMessage()
                    : "Download paused: " + e.getMessage();
          }
          String finalMessage = message;
          main.post(
              () -> {
                if (destroyed) return;
                busy = false;
                downloading = false;
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                setStatus(finalMessage);
                refresh();
              });
        });
  }

  private long partialSize() {
    long total = 0;
    File[] files = store.directory.listFiles();
    if (files != null) for (File f : files) total += f.length();
    return total;
  }

  private void generate(boolean enhanced, boolean automatic) {
    main.removeCallbacks(autoGenerate);
    if (busy || !foreground || !store.ready()) return;
    String description = prompt.getText().toString().trim();
    if (description.isEmpty()) {
      if (!automatic) toast("Describe what you want to draw");
      return;
    }
    long seed;
    try {
      seed = Long.parseLong(seedInput.getText().toString());
      if (seed < 0 || seed > Integer.MAX_VALUE) throw new NumberFormatException();
    } catch (NumberFormatException e) {
      toast("Seed must be between 0 and 2147483647");
      return;
    }
    final long usedSeed = seed, version = revision;
    final float amount = (creativity.getProgress() + 20) / 100f;
    int selected = style.getSelectedItemPosition();
    final String fullPrompt = description + (selected > 0 ? ", " + suffixes[selected] : "");
    Bitmap input = sketch.snapshot();
    busy = true;
    stopRequested = false;
    if (engine != null) engine.resetCancellation();
    refresh();
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    setStatus("Preparing your sketch…");
    worker.execute(
        () -> {
          long started = System.nanoTime();
          Bitmap output = null;
          String failure = null;
          try {
            if (engine == null) {
              try (Reader v =
                      new InputStreamReader(
                          getAssets().open("vocab.json"), StandardCharsets.UTF_8);
                  Reader m =
                      new InputStreamReader(
                          getAssets().open("merges.txt"), StandardCharsets.UTF_8)) {
                engine =
                    new DiffusionEngine(
                        store.directory,
                        new ClipTokenizer(v, m),
                        Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 1)));
              }
            }
            float[] rgb = toPlanar(input);
            input.recycle();
            if (stopRequested) throw new CancellationException();
            output =
                fromPlanar(
                    engine.generate(rgb, fullPrompt, amount, usedSeed, enhanced, this::postStatus));
            if (stopRequested) {
              output.recycle();
              output = null;
              throw new CancellationException();
            }
          } catch (OutOfMemoryError e) {
            failure =
                "Not enough memory. Close other apps and try again. This model is intended for"
                    + " phones with 8 GB RAM or more.";
            releaseEngine();
          } catch (LinkageError e) {
            failure = "This device could not load the inference runtime: " + e.getMessage();
            releaseEngine();
          } catch (Exception e) {
            failure =
                (e instanceof CancellationException || !foreground)
                    ? "Generation stopped"
                    : "Generation failed: " + e.getMessage();
            releaseEngine();
          } finally {
            if (!input.isRecycled()) input.recycle();
          }
          Bitmap finished = output;
          String error = failure;
          float seconds = (System.nanoTime() - started) / 1e9f;
          main.post(
              () -> {
                if (destroyed) {
                  if (finished != null) finished.recycle();
                  return;
                }
                busy = false;
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                if (finished != null) {
                  addResult(finished);
                  setStatus(
                      String.format(
                          Locale.US,
                          "%s · %.1f s · generated on this phone",
                          enhanced ? "Enhanced" : "Ready",
                          seconds));
                  if (!automatic) showResult(true);
                } else {
                  setStatus(error);
                  live.setChecked(false);
                }
                refresh();
                if (live.isChecked() && foreground && revision != version)
                  main.postDelayed(autoGenerate, 300);
              });
        });
  }

  private void stopWork() {
    stopRequested = true;
    live.setChecked(false);
    main.removeCallbacks(autoGenerate);
    if (downloading) store.cancel();
    else if (engine != null) engine.cancel();
    setStatus("Stopping…");
  }

  private void releaseEngine() {
    try {
      if (engine != null) engine.close();
    } catch (Exception ignored) {
    }
    engine = null;
  }

  private float[] toPlanar(Bitmap source) {
    Bitmap small = Bitmap.createScaledBitmap(source, 384, 384, true);
    int[] pixels = new int[384 * 384];
    small.getPixels(pixels, 0, 384, 0, 0, 384, 384);
    if (small != source) small.recycle();
    float[] out = new float[pixels.length * 3];
    for (int i = 0; i < pixels.length; i++) {
      out[i] = ((pixels[i] >> 16) & 255) / 127.5f - 1;
      out[i + pixels.length] = ((pixels[i] >> 8) & 255) / 127.5f - 1;
      out[i + 2 * pixels.length] = (pixels[i] & 255) / 127.5f - 1;
    }
    return out;
  }

  private Bitmap fromPlanar(float[] rgb) {
    int n = 384 * 384;
    int[] pixels = new int[n];
    for (int i = 0; i < n; i++)
      pixels[i] = Color.rgb(channel(rgb[i]), channel(rgb[i + n]), channel(rgb[i + 2 * n]));
    return Bitmap.createBitmap(pixels, 384, 384, Bitmap.Config.ARGB_8888);
  }

  private int channel(float v) {
    return Math.min(255, Math.max(0, Math.round((v + 1) * 127.5f)));
  }

  private void addResult(Bitmap b) {
    latest = b;
    result.setImageBitmap(b);
    thumbnail.setImageBitmap(b);
    history.add(0, b);
    if (history.size() > 8) {
      Bitmap old = history.remove(history.size() - 1);
      if (old != latest) old.recycle();
    }
    historyRow.removeAllViews();
    for (Bitmap item : history) {
      ImageView image = new ImageView(this);
      image.setImageBitmap(item);
      image.setContentDescription("Open previous result");
      LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(64), dp(64));
      p.setMargins(0, 0, dp(8), 0);
      historyRow.addView(image, p);
      image.setOnClickListener(
          v -> {
            latest = item;
            result.setImageBitmap(item);
            thumbnail.setImageBitmap(item);
            showResult(true);
          });
    }
    worker.execute(() -> saveImage(b, new File(getFilesDir(), "result.png")));
  }

  private void export(boolean share) {
    if (latest == null) {
      toast("Generate an image first");
      return;
    }
    Bitmap copy = latest.copy(Bitmap.Config.ARGB_8888, false);
    worker.execute(
        () -> {
          Uri uri = null;
          try {
            ContentValues values = new ContentValues();
            values.put(
                MediaStore.Images.Media.DISPLAY_NAME,
                "SketchLive-" + System.currentTimeMillis() + ".png");
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SketchLive");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
            uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IOException("Cannot create image");
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
              if (out == null || !copy.compress(Bitmap.CompressFormat.PNG, 100, out))
                throw new IOException("Cannot write image");
            }
            values.clear();
            values.put(MediaStore.Images.Media.IS_PENDING, 0);
            getContentResolver().update(uri, values, null, null);
            Uri saved = uri;
            main.post(
                () -> {
                  if (destroyed) return;
                  if (share) {
                    Intent intent = new Intent(Intent.ACTION_SEND);
                    intent.setType("image/png");
                    intent.putExtra(Intent.EXTRA_STREAM, saved);
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(intent, "Share your SketchLive image"));
                  } else toast("Saved to Pictures/SketchLive");
                });
          } catch (Exception e) {
            if (uri != null) getContentResolver().delete(uri, null, null);
            main.post(() -> toast("Could not save: " + e.getMessage()));
          } finally {
            copy.recycle();
          }
        });
  }

  @Override
  protected void onActivityResult(int request, int code, Intent data) {
    super.onActivityResult(request, code, data);
    if (request == 20 && code == RESULT_OK && data != null) {
      Uri uri = data.getData();
      worker.execute(
          () -> {
            try {
              ImageDecoder.Source source = ImageDecoder.createSource(getContentResolver(), uri);
              Bitmap b =
                  ImageDecoder.decodeBitmap(
                      source,
                      (decoder, info, s) -> {
                        decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                        int max = Math.max(info.getSize().getWidth(), info.getSize().getHeight());
                        if (max > 1536) decoder.setTargetSampleSize((max + 1535) / 1536);
                      });
              main.post(
                  () -> {
                    if (!destroyed) {
                      sketch.place(b);
                      showResult(false);
                    }
                    b.recycle();
                  });
            } catch (Exception e) {
              main.post(() -> toast("Cannot import this image"));
            }
          });
    }
  }

  private void about() {
    new AlertDialog.Builder(this)
        .setTitle("SketchLive · local Android")
        .setMessage(
            "Free, open-source sketch-to-image. No account, subscription, server, or image uploads."
                + " Internet is used only to download model files from Hugging Face.\n\n"
                + "Android 10+, 64-bit, 8 GB RAM recommended. One-step previews at 384 px; Enhance"
                + " uses two steps with the tiny decoder. Android results and speed vary by"
                + " device.\n\n"
                + "App: MIT. ONNX Runtime: MIT. Gson: Apache 2.0. TAESD: MIT. SD-Turbo weights:"
                + " Stability AI terms. Conversion by xay2001/sd-turbo-android.\n\n"
                + "Model storage: 2.42 GB in app-private storage. Uninstalling removes it. Sketch"
                + " and latest result are restored when you reopen the app.")
        .setPositiveButton("OK", null)
        .setNeutralButton(
            "Model card",
            (d, w) ->
                startActivity(
                    new Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://huggingface.co/xay2001/sd-turbo-android"))))
        .show();
  }

  private void restore() {
    android.content.SharedPreferences prefs = getPreferences(0);
    prompt.setText(
        prefs.getString(
            "prompt", "a magical red mushroom cottage, glowing windows, mossy forest, fireflies"));
    seedInput.setText(prefs.getString("seed", "42"));
    creativity.setProgress(prefs.getInt("creativity", 65));
    String saved = prefs.getString("style", "Cinematic");
    for (int i = 0; i < styles.length; i++) if (styles[i].equals(saved)) style.setSelection(i);
    sketch.restore(new File(getFilesDir(), "sketch.png"));
    Bitmap previous = BitmapFactory.decodeFile(new File(getFilesDir(), "result.png").toString());
    if (previous != null) addResult(previous);
  }

  private void saveImage(Bitmap bitmap, File destination) {
    try (FileOutputStream out = new FileOutputStream(destination)) {
      bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
    } catch (Exception ignored) {
    }
  }

  @Override
  protected void onResume() {
    super.onResume();
    foreground = true;
  }

  @Override
  protected void onPause() {
    super.onPause();
    foreground = false;
    if (sketch == null) return;
    main.removeCallbacks(autoGenerate);
    if (!downloading) {
      stopRequested = true;
      live.setChecked(false);
      if (engine != null) engine.cancel();
    }
    getPreferences(0)
        .edit()
        .putString("prompt", prompt.getText().toString())
        .putString("seed", seedInput.getText().toString())
        .putInt("creativity", creativity.getProgress())
        .putString("style", currentStyle)
        .apply();
    Bitmap copy = sketch.snapshot();
    worker.execute(
        () -> {
          saveImage(copy, new File(getFilesDir(), "sketch.png"));
          copy.recycle();
        });
  }

  @Override
  protected void onDestroy() {
    destroyed = true;
    if (store != null) store.cancel();
    if (engine != null) engine.cancel();
    main.removeCallbacksAndMessages(null);
    worker.execute(this::releaseEngine);
    worker.shutdown();
    super.onDestroy();
  }
}
