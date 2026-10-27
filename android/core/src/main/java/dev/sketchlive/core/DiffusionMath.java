package dev.sketchlive.core;

import java.util.Random;

public final class DiffusionMath {
  private DiffusionMath() {}

  /** SD-Turbo's scaled-linear beta schedule, EulerDiscreteScheduler. */
  public static float sigma(int timestep) {
    double alpha = 1, start = Math.sqrt(.00085), end = Math.sqrt(.012);
    for (int i = 0; i <= timestep; i++) {
      double b = start + (end - start) * i / 999.0;
      alpha *= 1 - b * b;
    }
    return (float) Math.sqrt((1 - alpha) / alpha);
  }

  public static float[] addNoise(float[] latent, float sigma, long seed) {
    Random rng = new Random(seed);
    float[] result = new float[latent.length];
    for (int i = 0; i < result.length; i++)
      result[i] = latent[i] + sigma * (float) rng.nextGaussian();
    return result;
  }

  public static float[] scale(float[] noisy, float sigma) {
    float[] result = noisy.clone();
    float div = (float) Math.sqrt(1 + sigma * sigma);
    for (int i = 0; i < result.length; i++) result[i] /= div;
    return result;
  }

  public static void eulerStep(float[] latent, float[] noise, float delta) {
    for (int i = 0; i < latent.length; i++) latent[i] += noise[i] * delta;
  }
}
