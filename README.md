# SketchLive: an open-source Krea real-time alternative

An open-source, self-hosted take on real-time AI drawing, inspired by Krea's real-time canvas: sketch on the left, and a diffusion model repaints your sketch on the right as you draw, guided by a text prompt. Runs locally on your own GPU.

> **Not affiliated with Krea.** SketchLive is an independent community project. It is not made, endorsed or supported by Krea AI; "Krea" is used only to describe what kind of tool this is.

## How it works

```
browser canvas ──PNG frame──▶ WebSocket ──▶ img2img (SD-Turbo, 1 step, TAESD) ──JPEG──▶ browser
```

- **Few-step model.** Distilled models (SD-Turbo, SDXL-Turbo, LCM) produce a usable image in a single denoising step. With the TAESD tiny autoencoder, a frame takes ~54 ms on an RTX 3090 (~15 fps end to end in the browser).
- **img2img, not text-to-image.** Your sketch is the starting image; the *Creativity* slider sets how much noise is added before the one denoising step (low = stick to your lines, high = let the model invent). It's mapped straight to a custom timestep, because diffusers' `strength` rounds to whole steps and would collapse most of the slider onto one value.
- **Fixed seed.** The seed stays constant between frames so the picture evolves smoothly instead of flickering. *Reroll* picks a new one.
- **Backpressure.** The client keeps at most one frame in flight and the server only processes the newest frame, so latency never piles up while you draw fast.

## Run it

```bash
python -m venv .venv
.venv\Scripts\activate          # Windows; use `source .venv/bin/activate` elsewhere
pip install torch --index-url https://download.pytorch.org/whl/cu121
pip install -r requirements.txt
python server.py
```

Open http://127.0.0.1:7860. The first run downloads the model (~2.5 GB).

No GPU? `python server.py --mock` serves the full UI with a fake generator, handy for frontend work (only `fastapi`, `uvicorn` and `pillow` are needed).

### Options

| Flag | Default | |
|---|---|---|
| `--model` | `stabilityai/sd-turbo` | Any diffusers img2img checkpoint; also `SKETCHLIVE_MODEL` env var |
| `--full-vae` | off | Use the model's full VAE instead of TAESD: sharper, ~3x slower (~158 ms/frame on a 3090) |
| `--compile` | off | `torch.compile` the UNet: slow first start, noticeably faster frames |
| `--device` | `cuda` | `mps` works on Apple Silicon (slower) |
| `--host` / `--port` | `127.0.0.1` / `7860` | |

## Model licenses

The code is MIT. Model weights carry their own licenses: `stabilityai/sd-turbo` and `sdxl-turbo` are under Stability AI's community license (check its commercial-use terms). For a permissive stack, point `--model` at an LCM checkpoint such as `SimianLuo/LCM_Dreamshaper_v7` and verify its license on its model card.

## Roadmap ideas

- ControlNet scribble/lineart conditioning for tighter line adherence
- StreamDiffusion / TensorRT backend for 30+ fps
- Layers, image upload as background, webcam/screen input
- Style presets and prompt history
