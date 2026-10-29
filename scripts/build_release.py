from release_common import *
import json
config = json.loads((ROOT / 'launcher.json').read_text())
# uv is pinned by the workflow; its license is shipped with the launcher.
uv = Path(shutil.which('uv'))
if not uv.is_file():
    raise RuntimeError('Install uv==0.9.5 for the bundled environment manager')
extra = ['--add-binary', f'{uv};.', '--add-data', 'launcher.json;.', '--add-data', 'setup_worker.py;.',
         '--add-data', 'LICENSE;.', '--collect-all', 'tkinter']
folder = freeze('launcher.py', config['name'], '--windowed', *extra)
payload = folder / '_internal' / 'app'
payload.mkdir(exist_ok=True)
for name in ('server.py', 'requirements.txt', 'preview.py', 'mesh.py'):
    if (ROOT / name).exists():
        shutil.copy2(ROOT / name, payload)
shutil.copy2(ROOT / 'requirements-launcher.txt', payload / 'requirements.txt')
shutil.copytree(ROOT / 'static', payload / 'static', dirs_exist_ok=True)
# Carry a fixed MinGit distribution for TRELLIS and pip's git-based dependency.
if config['trellis']:
    download = ROOT / 'build' / 'mingit.zip'
    urllib.request.urlretrieve('https://github.com/git-for-windows/git/releases/download/v2.56.0.windows.4/MinGit-2.56.0.4-64-bit.zip', download)
    with zipfile.ZipFile(download) as z:
        z.extractall(folder / '_internal' / 'git')
import importlib.metadata
uv_dist = importlib.metadata.distribution('uv')
licenses = folder / '_internal' / 'licenses' / 'uv'
licenses.mkdir(parents=True, exist_ok=True)
for path in uv_dist.files or []:
    if 'license' in str(path).lower() and Path(uv_dist.locate_file(path)).is_file():
        shutil.copy2(uv_dist.locate_file(path), licenses / Path(path).name)
launch_check(folder / (config['name'] + '.exe'), '--smoke-test')
archive(folder, 'sketchlive-windows.zip')
