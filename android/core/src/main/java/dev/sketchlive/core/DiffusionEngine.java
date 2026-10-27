package dev.sketchlive.core;

import ai.onnxruntime.*;
import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

/** Real, local image-to-image inference. Single worker owns sessions; cancel is thread-safe. */
public final class DiffusionEngine implements AutoCloseable {
  public static final int SIZE = 384;
  private final OrtEnvironment env = OrtEnvironment.getEnvironment();
  private final File directory;
  private final ClipTokenizer tokenizer;
  private OrtSession unet, encoder, decoder;
  private String cachedPrompt;
  private float[] embedding;
  private volatile boolean cancelled;
  private volatile OrtSession.RunOptions running;
  private final int threads;

  public DiffusionEngine(File directory, ClipTokenizer tokenizer, int threads) {
    this.directory = directory;
    this.tokenizer = tokenizer;
    this.threads = Math.max(1, threads);
  }

  private OrtSession open(String name) throws OrtException {
    try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
      options.setIntraOpNumThreads(threads);
      options.setInterOpNumThreads(1);
      options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT);
      options.setMemoryPatternOptimization(false);
      options.setCPUArenaAllocator(false);
      return env.createSession(new File(directory, name).getAbsolutePath(), options);
    }
  }

  public void cancel() {
    cancelled = true;
    OrtSession.RunOptions r = running;
    if (r != null)
      try {
        r.setTerminate(true);
      } catch (OrtException ignored) {
      }
  }

  public void resetCancellation() {
    cancelled = false;
  }

  private void check() {
    if (cancelled) throw new CancellationException("Generation stopped");
  }

  private float[] run(OrtSession session, Map<String, OnnxTensor> inputs) throws OrtException {
    check();
    try (OrtSession.RunOptions options = new OrtSession.RunOptions()) {
      running = options;
      check();
      try (OrtSession.Result result = session.run(inputs, options)) {
        FloatBuffer buffer = ((OnnxTensor) result.get(0)).getFloatBuffer();
        float[] out = new float[buffer.remaining()];
        buffer.get(out);
        return out;
      } finally {
        running = null;
      }
    }
  }

  private OnnxTensor tensor(float[] data, long... shape) throws OrtException {
    return OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape);
  }

  /** Input/output planar RGB in [-1,1]. Enhanced mode uses two Euler steps. */
  public float[] generate(
      float[] image,
      String prompt,
      float creativity,
      long seed,
      boolean enhanced,
      Consumer<String> progress)
      throws OrtException {
    check();
    if (image.length != 3 * SIZE * SIZE)
      throw new IllegalArgumentException("Expected 384px planar RGB");
    if (!prompt.equals(cachedPrompt)) {
      progress.accept("Reading your prompt…");
      // Free the largest session before opening CLIP: lowers peak memory on phones.
      if (unet != null) {
        unet.close();
        unet = null;
      }
      try (OrtSession text = open("text_encoder.onnx");
          OnnxTensor ids =
              OnnxTensor.createTensor(
                  env, LongBuffer.wrap(tokenizer.encode(prompt)), new long[] {1, 77})) {
        embedding = run(text, Collections.singletonMap("input_ids", ids));
        cachedPrompt = prompt;
      }
    }
    check();
    progress.accept("Encoding your sketch…");
    if (encoder == null) encoder = open("encoder_384.onnx");
    float[] latent;
    try (OnnxTensor input = tensor(image, 1, 3, SIZE, SIZE)) {
      latent = run(encoder, Collections.singletonMap("image", input));
    }
    int t = Math.min(949, Math.max(199, Math.round(creativity * 1000) - 1));
    float initialSigma = DiffusionMath.sigma(t);
    float[] noisy = DiffusionMath.addNoise(latent, initialSigma, seed);
    check();
    progress.accept("Loading the drawing model…");
    if (unet == null) unet = open("unet_384.onnx");
    int steps = enhanced ? 2 : 1;
    try (OnnxTensor context = tensor(embedding, 1, 77, 1024)) {
      for (int step = 0; step < steps; step++) {
        check();
        progress.accept("Painting on this device · step " + (step + 1) + "/" + steps);
        int current = step == 0 ? t : Math.max(1, Math.round(t * .5f));
        float sigma = DiffusionMath.sigma(current);
        float next = step + 1 < steps ? DiffusionMath.sigma(Math.max(1, Math.round(t * .5f))) : 0;
        try (OnnxTensor sample = tensor(DiffusionMath.scale(noisy, sigma), 1, 4, 48, 48);
            OnnxTensor timestep = tensor(new float[] {current}, 1)) {
          Map<String, OnnxTensor> inputs = new HashMap<>();
          inputs.put("sample", sample);
          inputs.put("timestep", timestep);
          inputs.put("encoder_hidden_states", context);
          float[] noise = run(unet, inputs);
          DiffusionMath.eulerStep(noisy, noise, next - sigma);
        }
      }
    }
    check();
    progress.accept("Finishing your image…");
    if (decoder == null) decoder = open("decoder_384.onnx");
    try (OnnxTensor input = tensor(noisy, 1, 4, 48, 48)) {
      float[] out = run(decoder, Collections.singletonMap("latent", input));
      check();
      return out;
    }
  }

  @Override
  public void close() throws OrtException {
    if (unet != null) {
      unet.close();
      unet = null;
    }
    if (encoder != null) {
      encoder.close();
      encoder = null;
    }
    if (decoder != null) {
      decoder.close();
      decoder = null;
    }
    cachedPrompt = null;
    embedding = null;
  }
}
