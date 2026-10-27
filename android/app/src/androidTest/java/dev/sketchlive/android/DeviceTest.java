package dev.sketchlive.android;

import static org.junit.Assert.*;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.*;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class DeviceTest {
  private Object field(Object object, String name) {
    try {
      Field f = object.getClass().getDeclaredField(name);
      f.setAccessible(true);
      return f.get(object);
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  @Test
  public void downloadResumesAndVerifiesPinnedFile() throws Exception {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    ModelStore store = new ModelStore(context);
    Assume.assumeTrue(store.ready());
    java.io.File graph = new java.io.File(store.directory, "text_encoder.onnx");
    byte[] original;
    try (java.io.InputStream in = new java.io.FileInputStream(graph);
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      int n;
      while ((n = in.read(buffer)) != -1) bytes.write(buffer, 0, n);
      original = bytes.toByteArray();
    }
    java.io.File marker = new java.io.File(store.directory, "verified-" + ModelStore.REVISION);
    assertTrue(marker.delete());
    assertTrue(graph.delete());
    try (java.io.OutputStream out =
        new java.io.FileOutputStream(new java.io.File(store.directory, "text_encoder.onnx.part"))) {
      out.write(original, 0, original.length / 2);
    }
    assertFalse(store.ready());
    store.download(s -> {});
    assertTrue(store.ready());
    assertEquals(original.length, graph.length());
  }

  @Test
  public void drawingUndoRedoAndRestore() {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(
            () -> {
              SketchView view = new SketchView(context);
              view.color = Color.RED;
              view.fill();
              Bitmap red = view.snapshot();
              assertEquals(Color.RED, red.getPixel(10, 10));
              red.recycle();
              view.undo();
              Bitmap white = view.snapshot();
              assertEquals(Color.WHITE, white.getPixel(10, 10));
              white.recycle();
              view.redo();
              Bitmap again = view.snapshot();
              assertEquals(Color.RED, again.getPixel(10, 10));
              again.recycle();
              view.clear();
              Bitmap empty = view.snapshot();
              assertEquals(Color.WHITE, empty.getPixel(10, 10));
              empty.recycle();
            });
  }

  @Test
  public void actualOfflineGenerationThroughActivity() throws Exception {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    Assume.assumeTrue(
        "Install verified model files first for offline device integration",
        new ModelStore(context).ready());
    try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
      scenario.onActivity(
          activity -> {
            ((EditText) field(activity, "prompt"))
                .setText(
                    "a magical red mushroom cottage, glowing windows, mossy forest, cinematic"
                        + " fantasy art");
            ((EditText) field(activity, "seedInput")).setText("42");
            ((SeekBar) field(activity, "creativity")).setProgress(70);
            SketchView sketch = (SketchView) field(activity, "sketch");
            Bitmap fixture =
                BitmapFactory.decodeFile(
                    new java.io.File(activity.getFilesDir(), "fixture.png").toString());
            assertNotNull("Push the integration sketch fixture", fixture);
            sketch.place(fixture);
            fixture.recycle();
            assertTrue(((Button) field(activity, "generate")).isEnabled());
            ((Button) field(activity, "generate")).performClick();
          });
      AtomicReference<String> status = new AtomicReference<>("");
      long deadline = System.currentTimeMillis() + 240000;
      do {
        Thread.sleep(200);
        scenario.onActivity(
            activity -> status.set(((TextView) field(activity, "status")).getText().toString()));
      } while (!status.get().contains("generated on this phone")
          && !status.get().contains("failed")
          && System.currentTimeMillis() < deadline);
      assertTrue(status.get(), status.get().contains("generated on this phone"));
      scenario.onActivity(
          activity -> {
            Bitmap image = (Bitmap) field(activity, "latest");
            assertNotNull(image);
            assertEquals(384, image.getWidth());
            ((Button) field(activity, "drawTab")).performClick();
            assertEquals(View.VISIBLE, ((SketchView) field(activity, "sketch")).getVisibility());
            ((Button) field(activity, "resultTab")).performClick();
            assertEquals(View.VISIBLE, ((ImageView) field(activity, "result")).getVisibility());
            try {
              java.lang.reflect.Method export =
                  MainActivity.class.getDeclaredMethod("export", boolean.class);
              export.setAccessible(true);
              export.invoke(activity, false);
            } catch (Exception e) {
              throw new AssertionError(e);
            }
          });
      boolean saved = false;
      deadline = System.currentTimeMillis() + 10000;
      while (!saved && System.currentTimeMillis() < deadline) {
        try (android.database.Cursor cursor =
            context
                .getContentResolver()
                .query(
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    new String[] {"_id", "is_pending"},
                    "relative_path = ?",
                    new String[] {"Pictures/SketchLive/"},
                    null)) {
          if (cursor != null) while (cursor.moveToNext()) if (cursor.getInt(1) == 0) saved = true;
        }
        if (!saved) Thread.sleep(100);
      }
      assertTrue("PNG export must finish in Pictures/SketchLive", saved);
    }
  }
}
