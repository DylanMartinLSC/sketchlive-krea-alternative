"""Emit real per-file byte progress from Hugging Face's downloader."""
import json
import sys
import time
import importlib
from huggingface_hub.utils import tqdm
from huggingface_hub import snapshot_download

class DownloadProgress(tqdm):
    def __init__(self, *args, **kwargs):
        self.last_emit = 0
        super().__init__(*args, **kwargs)

    def update(self, amount=1):
        result = super().update(amount)
        if self.unit == 'B' and self.total and time.monotonic() - self.last_emit > .1:
            print('@progress ' + json.dumps({'name': self.desc, 'done': self.n, 'total': self.total}), flush=True)
            self.last_emit = time.monotonic()
        return result

# snapshot_download's tqdm_class covers file counts, not HTTP byte transfers.
# Both launchers pin huggingface_hub 0.36.2; bridge its real HTTP progress too.
importlib.import_module('huggingface_hub.utils.tqdm').tqdm = DownloadProgress

for repo in sys.argv[1:]:
    print('Downloading ' + repo, flush=True)
    # No CUDA runtimes or weights are embedded in the release asset.
    snapshot_download(repo, tqdm_class=DownloadProgress, max_workers=1,
                      ignore_patterns=['*.onnx', '*.msgpack', '*.h5', '*.tflite', '*.xml'])
