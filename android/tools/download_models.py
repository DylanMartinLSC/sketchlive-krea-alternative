"""Download and verify the exact Android model pack for JVM inference tests (stdlib only)."""
import hashlib
import json
from pathlib import Path
import sys
import urllib.request

REVISION = "30015d1ff375a67d2ade66f9a43339adf5cd5639"
BASE = f"https://huggingface.co/xay2001/sd-turbo-android/resolve/{REVISION}/sd-turbo-v1/"


def main():
    if len(sys.argv) != 2:
        raise SystemExit("Usage: python tools/download_models.py MODEL_DIRECTORY")
    destination = Path(sys.argv[1])
    destination.mkdir(parents=True, exist_ok=True)
    manifest = json.loads((Path(__file__).parents[1] / "app/src/main/assets/model-manifest.json").read_text())
    for item in manifest["files"]:
        if "_512" in item["name"]:
            continue
        target = destination / item["name"]
        def verified(path):
            if not path.exists() or path.stat().st_size != item["size"]:
                return False
            with path.open("rb") as source:
                digest = hashlib.sha256()
                for block in iter(lambda: source.read(1024 * 1024), b""):
                    digest.update(block)
            return digest.hexdigest() == item["sha256"]
        if not verified(target):
            partial = target.with_suffix(target.suffix + ".part")
            print("Downloading", item["name"], flush=True)
            urllib.request.urlretrieve(BASE + item["name"], partial)
            if not verified(partial):
                raise RuntimeError(f"Checksum mismatch: {partial}")
            partial.replace(target)
        print("Verified", item["name"], flush=True)
    print("Models ready at", destination.resolve())


if __name__ == "__main__":
    main()
