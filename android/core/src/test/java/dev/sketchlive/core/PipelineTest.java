package dev.sketchlive.core;

import static org.junit.Assert.*;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.Arrays;
import javax.imageio.ImageIO;
import org.junit.*;

public class PipelineTest {
  private ClipTokenizer tokenizer() throws IOException {
    File assets = new File(System.getProperty("assetsDir"));
    try (Reader v =
            new InputStreamReader(
                new FileInputStream(new File(assets, "vocab.json")),
                java.nio.charset.StandardCharsets.UTF_8);
        Reader m =
            new InputStreamReader(
                new FileInputStream(new File(assets, "merges.txt")),
                java.nio.charset.StandardCharsets.UTF_8)) {
      return new ClipTokenizer(v, m);
    }
  }

  private void tokens(String text, long... expected) throws IOException {
    long[] actual = tokenizer().encode(text), padded = new long[77];
    System.arraycopy(expected, 0, padded, 0, expected.length);
    assertArrayEquals(padded, actual);
  }

  @Test
  public void tokenizerMatchesUpstream() throws Exception {
    tokens("a magical red mushroom cottage", 49406, 320, 7823, 736, 13011, 12155, 49407);
    tokens("Hello, world!", 49406, 3306, 267, 1002, 0, 49407);
    tokens("café robot 🤖", 49406, 15304, 8797, 46146, 49407);
    tokens("a lighthouse on a rocky island", 49406, 320, 13717, 525, 320, 9648, 2619, 49407);
    tokens("", 49406, 49407);
    String longPrompt = String.join(" ", java.util.Collections.nCopies(150, "robot"));
    long[] ids = tokenizer().encode(longPrompt);
    assertEquals(77, ids.length);
    assertEquals(49407, ids[76]);
  }

  @Test
  public void schedulerMatchesPinnedManifest() {
    assertEquals(1.6128870, DiffusionMath.sigma(499), .00001);
    assertEquals(3.3210845, DiffusionMath.sigma(699), .00002);
    float[] latent = {1, 2, 3};
    assertArrayEquals(
        DiffusionMath.addNoise(latent, 2, 42), DiffusionMath.addNoise(latent, 2, 42), 0);
    assertFalse(
        Arrays.equals(
            DiffusionMath.addNoise(latent, 2, 42), DiffusionMath.addNoise(latent, 2, 43)));
    float[] step = {3, 4};
    DiffusionMath.eulerStep(step, new float[] {1, 2}, -2);
    assertArrayEquals(new float[] {1, 0}, step, 0);
  }

  @Test
  public void realModelEndToEnd() throws Exception {
    String modelDir = System.getProperty("modelDir", "");
    Assume.assumeFalse("Pass -DmodelDir for actual inference", modelDir.isEmpty());
    int size = 384, n = size * size;
    BufferedImage sketch = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = sketch.createGraphics();
    g.setColor(new Color(20, 43, 37));
    g.fillRect(0, 0, size, size);
    g.setColor(new Color(54, 91, 50));
    g.fillOval(-30, 310, 450, 150);
    g.setColor(new Color(217, 189, 136));
    g.fillRoundRect(133, 165, 120, 145, 45, 45);
    g.setColor(new Color(181, 45, 53));
    g.fillArc(68, 65, 248, 205, 0, 180);
    g.fillRoundRect(68, 154, 248, 40, 30, 30);
    g.setColor(new Color(84, 43, 40));
    g.fillRoundRect(176, 245, 35, 65, 30, 30);
    g.setColor(new Color(255, 237, 181));
    g.fillOval(130, 125, 18, 18);
    g.fillOval(190, 92, 18, 18);
    g.fillOval(246, 132, 18, 18);
    g.dispose();
    float[] input = new float[3 * n];
    for (int i = 0; i < n; i++) {
      int c = sketch.getRGB(i % size, i / size);
      input[i] = ((c >> 16) & 255) / 127.5f - 1;
      input[i + n] = ((c >> 8) & 255) / 127.5f - 1;
      input[i + 2 * n] = (c & 255) / 127.5f - 1;
    }
    File reports = new File("build/reports");
    reports.mkdirs();
    ImageIO.write(sketch, "png", new File(reports, "sketch.png"));
    try (DiffusionEngine engine = new DiffusionEngine(new File(modelDir), tokenizer(), 4)) {
      float[] output =
          engine.generate(
              input,
              "a magical red mushroom cottage, glowing windows, mossy forest, cinematic fantasy"
                  + " art",
              .9f,
              42,
              false,
              System.out::println);
      validate(output, input);
      save(output, new File(reports, "inference.png"));
      float[] enhanced =
          engine.generate(
              input,
              "a magical red mushroom cottage, glowing windows, mossy forest, cinematic fantasy"
                  + " art",
              .9f,
              42,
              true,
              System.out::println);
      validate(enhanced, input);
      save(enhanced, new File(reports, "enhanced.png"));
      engine.cancel();
      try {
        engine.generate(input, "stopped", .9f, 42, false, s -> {});
        fail("Cancellation ignored");
      } catch (java.util.concurrent.CancellationException expected) {
      }
      engine.resetCancellation();
    }
  }

  private void validate(float[] output, float[] input) {
    assertEquals(input.length, output.length);
    double difference = 0, mean = 0, square = 0;
    for (int i = 0; i < output.length; i++) {
      assertTrue(Float.isFinite(output[i]));
      difference += Math.abs(output[i] - input[i]);
      mean += output[i];
      square += output[i] * output[i];
    }
    assertTrue("Must transform the sketch", difference / output.length > .1);
    assertTrue(
        "Output must contain visual detail",
        square / output.length - Math.pow(mean / output.length, 2) > .02);
  }

  private void save(float[] rgb, File path) throws IOException {
    int n = 384 * 384;
    BufferedImage image = new BufferedImage(384, 384, BufferedImage.TYPE_INT_RGB);
    for (int i = 0; i < n; i++)
      image.setRGB(
          i % 384,
          i / 384,
          (channel(rgb[i]) << 16) | (channel(rgb[i + n]) << 8) | channel(rgb[i + 2 * n]));
    ImageIO.write(image, "png", path);
  }

  private int channel(float v) {
    return Math.min(255, Math.max(0, Math.round((v + 1) * 127.5f)));
  }
}
