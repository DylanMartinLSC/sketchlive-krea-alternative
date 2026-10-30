# Portable Windows launcher

Extract all files together and run the EXE. Setup checks `nvidia-smi`, downloads a managed Python 3.11 using bundled uv, and installs CUDA 12.4 PyTorch packages and the pinned application dependencies. The NVIDIA driver must support CUDA 12.4; a CUDA toolkit installation is not required. This is an unsigned, experimental distribution.

The release ZIP does not contain CUDA PyTorch or model weights. First-run downloads are several GB; downloading complete model snapshots and caching package wheels can require substantially more space than the inference model sizes listed in the README. Allow at least 30 GB of free disk space. Setup steps have an indeterminate progress bar; model-file downloads show actual byte progress. Logs, cancellation and Retry are available. Interrupted downloads reuse their caches.

The environment and ready marker live under `%LOCALAPPDATA%` in the application's named folder. Model weights use the normal Hugging Face cache. Keep the launcher open: closing it stops its child processes and the local server. The source and static UI are bundled with the launcher. Doodle2Part additionally carries MinGit with its notices and checks out a fixed TRELLIS commit in the environment; generated meshes are stored in its user data folder.

CI smoke-tests the frozen setup window, bundled environment manager and source parsing. This does not certify model downloads or full inference on every GPU. Model licenses still apply; see the existing README credits. No mock output is used for promotional media.

To validate real first-run setup, use a clean Windows account with a compatible NVIDIA GPU, run through installation, generate a real result, restart, and verify cached offline operation. Do not describe this full GPU path as tested until that check passes.
