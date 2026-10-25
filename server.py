"""SketchLive: real-time sketch-to-image server.

The browser streams canvas frames over a WebSocket; the server runs a
few-step img2img diffusion model on the newest frame and streams the result
back. Frames that arrive while the GPU is busy are dropped, keeping latency
bounded no matter how fast the user draws.

    python server.py                 # real model (needs a CUDA GPU)
    python server.py --mock          # no model, for UI development
"""

import argparse
import asyncio
import base64
import io
import json
import os
import time
from pathlib import Path

import uvicorn
from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from PIL import Image, ImageFilter, ImageOps

STATIC = Path(__file__).parent / "static"
SIZE = 512


class MockGenerator:
    """Stand-in that stylizes the sketch so the UI works without a GPU."""

    default_model = "sd-turbo"
    pipes = property(lambda self: PRESETS)  # every preset counts as loaded

    def __call__(self, image, prompt, strength, seed, model=None, enhance=False):
        time.sleep(0.5 if enhance else 0.05)
        img = image.filter(ImageFilter.GaussianBlur(6 * strength))
        return ImageOps.posterize(ImageOps.autocontrast(img), 3)


# Each preset says how to build the pipeline and how to turn the Creativity
# slider into denoising timesteps. Licenses are the weights' own; check the
# model cards before commercial use.
PRESETS = {
    "sd-turbo": dict(label="SD-Turbo", repo="stabilityai/sd-turbo", kind="turbo",
                     license="Stability AI Community"),
    "sdxs": dict(label="SDXS-512 (fastest; needs high Creativity)", repo="IDKiro/sdxs-512-0.9", kind="sdxs",
                 license="OpenRAIL++"),
    "dreamshaper": dict(label="DreamShaper 8 + LCM (painterly)", repo="Lykon/dreamshaper-8", kind="lcm",
                        license="CreativeML OpenRAIL-M"),
    "realistic": dict(label="Realistic Vision 5.1 + LCM (photo)", repo="SG161222/Realistic_Vision_V5.1_noVAE",
                      kind="lcm", license="CreativeML OpenRAIL-M"),
    "anime": dict(label="AnyLoRA + LCM (anime)", repo="Lykon/AnyLoRA", kind="lcm",
                  license="CreativeML OpenRAIL-M"),
}
LCM_LORA = "latent-consistency/lcm-lora-sdv1-5"
LCM_STEPS = 2
ENHANCE_STEPS = {"lcm": 4, "turbo": 2, "sdxs": 1}
MAX_LOADED = 2  # pipelines kept on the GPU; older ones are evicted


class DiffusionGenerator:
    def __init__(self, default_model, device, compile_unet, tiny_vae):
        import torch

        self.torch = torch
        self.device = device
        self.dtype = torch.float16 if device == "cuda" else torch.float32
        self.compile_unet = compile_unet
        self.tiny_vae = tiny_vae
        self.default_model = default_model
        self.pipes = {}  # model id -> pipeline, least recently used first
        self.load(default_model)

    def load(self, model):
        if model in self.pipes:
            self.pipes[model] = self.pipes.pop(model)  # mark as most recently used
            return self.pipes[model]
        while len(self.pipes) >= MAX_LOADED:
            del self.pipes[next(iter(self.pipes))]
            self.torch.cuda.empty_cache()

        from diffusers import AutoencoderTiny, AutoPipelineForImage2Image, EulerDiscreteScheduler, LCMScheduler

        preset = PRESETS[model]
        kwargs = dict(torch_dtype=self.dtype, safety_checker=None, requires_safety_checker=False)
        try:
            pipe = AutoPipelineForImage2Image.from_pretrained(preset["repo"], variant="fp16", **kwargs)
        except (OSError, ValueError):
            pipe = AutoPipelineForImage2Image.from_pretrained(preset["repo"], **kwargs)
        if preset["kind"] == "lcm":
            pipe.scheduler = LCMScheduler.from_config(pipe.scheduler.config)
            pipe.load_lora_weights(LCM_LORA)
            pipe.fuse_lora()
            pipe.unload_lora_weights()
        elif preset["kind"] == "sdxs":
            # SDXS ships PNDM, which can't take the custom timesteps we pass.
            pipe.scheduler = EulerDiscreteScheduler.from_config(pipe.scheduler.config)
        # SDXS ships its own tiny VAE; the SD 1.5 family can share TAESD, which is
        # much faster at encode/decode for a small cost in fine detail.
        if self.tiny_vae and preset["kind"] != "sdxs":
            pipe.vae = AutoencoderTiny.from_pretrained("madebyollin/taesd", torch_dtype=self.dtype)
        pipe.to(self.device)
        pipe.set_progress_bar_config(disable=True)
        pipe.fast_vae, pipe.full_vae = pipe.vae, None
        if self.compile_unet:
            pipe.unet = self.torch.compile(pipe.unet, mode="max-autotune", fullgraph=True)
        self.pipes[model] = pipe
        try:
            # Warm up so the first stroke isn't slow.
            self(Image.new("RGB", (SIZE, SIZE), "white"), "warmup", 0.5, 0, model)
        except Exception:
            del self.pipes[model]
            raise
        return pipe

    def full_vae(self, model, pipe):
        """The model's own full-size VAE, loaded on first use by Enhance."""
        if pipe.full_vae is None:
            from diffusers import AutoencoderKL

            subfolder = "vae_large" if PRESETS[model]["kind"] == "sdxs" else "vae"
            pipe.full_vae = AutoencoderKL.from_pretrained(
                PRESETS[model]["repo"], subfolder=subfolder, torch_dtype=self.dtype
            ).to(self.device)
        return pipe.full_vae

    def __call__(self, image, prompt, strength, seed, model=None, enhance=False):
        model = model if model in PRESETS else self.default_model
        pipe = self.load(model)
        kind = PRESETS[model]["kind"]
        # diffusers maps `strength` onto a whole number of scheduler steps, which for
        # 1-step models collapses most of the range onto t=499. Instead, pass explicit
        # timesteps so the noise level tracks the slider continuously.
        t = min(999, max(1, round(1000 * strength) - 1))
        steps = ENHANCE_STEPS[kind] if enhance else (LCM_STEPS if kind == "lcm" else 1)
        timesteps = sorted({max(1, round(t * (steps - i) / steps)) for i in range(steps)}, reverse=True)
        if kind == "lcm":
            # LCM only accepts timesteps from its 50-step training grid (19, 39, ... 999).
            timesteps = sorted({min(999, max(19, 20 * round((x + 1) / 20) - 1)) for x in timesteps}, reverse=True)
        # Enhance swaps in the full VAE for sharper detail; live frames use the fast one.
        pipe.vae = self.full_vae(model, pipe) if enhance else pipe.fast_vae
        generator = self.torch.Generator(self.device).manual_seed(seed)
        try:
            with self.torch.inference_mode():
                return pipe(
                    prompt=prompt,
                    image=image,
                    strength=1.0,
                    timesteps=timesteps,
                    guidance_scale=1.0 if kind == "lcm" else 0.0,
                    generator=generator,
                ).images[0]
        finally:
            pipe.vae = pipe.fast_vae


def decode_image(data_url):
    raw = base64.b64decode(data_url.split(",", 1)[1])
    img = Image.open(io.BytesIO(raw)).convert("RGBA")
    # Composite onto white so transparent canvas areas aren't black.
    bg = Image.new("RGBA", img.size, "white")
    return Image.alpha_composite(bg, img).convert("RGB").resize((SIZE, SIZE))


def encode_image(img, quality):
    buf = io.BytesIO()
    img.save(buf, format="JPEG", quality=quality)
    return "data:image/jpeg;base64," + base64.b64encode(buf.getvalue()).decode()


def create_app(generator):
    app = FastAPI()
    gpu_lock = asyncio.Lock()

    @app.get("/")
    def index():
        return FileResponse(STATIC / "index.html")

    @app.get("/models")
    def models():
        return {
            "default": generator.default_model,
            "models": [{"id": k, "label": v["label"], "license": v["license"]} for k, v in PRESETS.items()],
        }

    @app.websocket("/ws")
    async def ws(socket: WebSocket):
        await socket.accept()
        latest = None
        closed = False
        frame_ready = asyncio.Event()

        async def receive():
            # Keep only the newest frame; older ones are stale by the time the GPU frees up.
            nonlocal latest, closed
            try:
                while True:
                    latest = json.loads(await socket.receive_text())
                    frame_ready.set()
            except WebSocketDisconnect:
                closed = True
                frame_ready.set()

        receiver = asyncio.create_task(receive())
        try:
            while True:
                await frame_ready.wait()
                frame_ready.clear()
                if closed:
                    break
                msg, latest = latest, None
                if msg is None:
                    continue
                image = decode_image(msg["image"])
                model = msg.get("model") if msg.get("model") in PRESETS else generator.default_model
                if model not in generator.pipes:
                    await socket.send_text(json.dumps({"loading": PRESETS[model]["label"]}))
                started = time.perf_counter()
                try:
                    async with gpu_lock:
                        result = await asyncio.to_thread(
                            generator,
                            image,
                            msg.get("prompt", ""),
                            float(msg.get("strength", 0.75)),
                            int(msg.get("seed", 0)),
                            model,
                            bool(msg.get("enhance")),
                        )
                except Exception as e:
                    await socket.send_text(json.dumps({"id": msg.get("id"), "error": f"{type(e).__name__}: {e}"}))
                    continue
                await socket.send_text(json.dumps({
                    "id": msg.get("id"),
                    "image": encode_image(result, quality=95 if msg.get("enhance") else 85),
                    "enhanced": bool(msg.get("enhance")),
                    "ms": round((time.perf_counter() - started) * 1000),
                }))
        except (WebSocketDisconnect, RuntimeError):
            pass  # client went away mid-send
        finally:
            receiver.cancel()

    app.mount("/static", StaticFiles(directory=STATIC), name="static")
    return app


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--mock", action="store_true", help="run without a model")
    parser.add_argument("--model", choices=list(PRESETS), default=os.environ.get("SKETCHLIVE_MODEL", "sd-turbo"),
                        help="model preset loaded at startup; others load on demand from the UI")
    parser.add_argument("--device", default="cuda")
    parser.add_argument("--compile", action="store_true", help="torch.compile the UNet (slow startup, faster frames)")
    parser.add_argument("--full-vae", action="store_true", help="use the model's full VAE instead of TAESD (slower, sharper)")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=7860)
    args = parser.parse_args()

    generator = MockGenerator() if args.mock else DiffusionGenerator(args.model, args.device, args.compile, tiny_vae=not args.full_vae)
    uvicorn.run(create_app(generator), host=args.host, port=args.port)


if __name__ == "__main__":
    main()
