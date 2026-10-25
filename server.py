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

    def __call__(self, image, prompt, strength, seed):
        time.sleep(0.05)
        img = image.filter(ImageFilter.GaussianBlur(6 * strength))
        return ImageOps.posterize(ImageOps.autocontrast(img), 3)


class DiffusionGenerator:
    def __init__(self, model_id, device, compile_unet, tiny_vae):
        import torch
        from diffusers import AutoencoderTiny, AutoPipelineForImage2Image

        dtype = torch.float16 if device == "cuda" else torch.float32
        self.torch = torch
        self.device = device
        self.pipe = AutoPipelineForImage2Image.from_pretrained(
            model_id, torch_dtype=dtype, variant="fp16" if dtype == torch.float16 else None
        )
        if tiny_vae:
            # TAESD: much faster encode/decode at a small cost in fine detail.
            self.pipe.vae = AutoencoderTiny.from_pretrained("madebyollin/taesd", torch_dtype=dtype)
        self.pipe.to(device)
        self.pipe.set_progress_bar_config(disable=True)
        if compile_unet:
            self.pipe.unet = torch.compile(self.pipe.unet, mode="max-autotune", fullgraph=True)
        # Warm up so the first stroke isn't slow.
        self(Image.new("RGB", (SIZE, SIZE), "white"), "warmup", 0.5, 0)

    def __call__(self, image, prompt, strength, seed):
        # diffusers maps `strength` onto a whole number of scheduler steps, which for
        # 1-step models collapses most of the range onto t=499. Instead, take a single
        # denoising step from a custom timestep so the noise level tracks the slider.
        timestep = min(999, max(1, round(1000 * strength) - 1))
        generator = self.torch.Generator(self.device).manual_seed(seed)
        with self.torch.inference_mode():
            return self.pipe(
                prompt=prompt,
                image=image,
                strength=1.0,
                timesteps=[timestep],
                guidance_scale=0.0,
                generator=generator,
            ).images[0]


def decode_image(data_url):
    raw = base64.b64decode(data_url.split(",", 1)[1])
    img = Image.open(io.BytesIO(raw)).convert("RGBA")
    # Composite onto white so transparent canvas areas aren't black.
    bg = Image.new("RGBA", img.size, "white")
    return Image.alpha_composite(bg, img).convert("RGB").resize((SIZE, SIZE))


def encode_image(img):
    buf = io.BytesIO()
    img.save(buf, format="JPEG", quality=90)
    return "data:image/jpeg;base64," + base64.b64encode(buf.getvalue()).decode()


def create_app(generator):
    app = FastAPI()
    gpu_lock = asyncio.Lock()

    @app.get("/")
    def index():
        return FileResponse(STATIC / "index.html")

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
                started = time.perf_counter()
                async with gpu_lock:
                    result = await asyncio.to_thread(
                        generator,
                        image,
                        msg.get("prompt", ""),
                        float(msg.get("strength", 0.75)),
                        int(msg.get("seed", 0)),
                    )
                await socket.send_text(json.dumps({
                    "id": msg.get("id"),
                    "image": encode_image(result),
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
    parser.add_argument("--model", default=os.environ.get("SKETCHLIVE_MODEL", "stabilityai/sd-turbo"))
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
