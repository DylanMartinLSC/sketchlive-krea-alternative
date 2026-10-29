"""Small Windows setup GUI; CUDA and weights live in a user-writable environment."""
import hashlib
import json
import os
from pathlib import Path
import queue
import subprocess
import sys
import threading
import time
import tkinter as tk
from tkinter import ttk
import urllib.request
import webbrowser

ROOT = Path(getattr(sys, '_MEIPASS', Path(__file__).parent))
CONFIG = json.loads((ROOT / 'launcher.json').read_text(encoding='utf-8'))
APP = ROOT / 'app'
HOME = Path(os.environ.get('LOCALAPPDATA', Path.home())) / CONFIG['id']
# A changed dependency/source payload gets a new environment, never a stale ready flag.
STAMP = hashlib.sha256((ROOT / 'launcher.json').read_bytes() +
                       (APP / 'requirements.txt').read_bytes() +
                       (ROOT / 'setup_worker.py').read_bytes()).hexdigest()[:16]
ENV = HOME / ('env-' + STAMP)
PYTHON = ENV / 'Scripts' / 'python.exe'
EVENTS = queue.Queue()
CHILDREN = set()
STOP = threading.Event()
CREATE = getattr(subprocess, 'CREATE_NO_WINDOW', 0)

def command(args, cwd=None, env=None):
    if STOP.is_set():
        raise RuntimeError('Setup cancelled')
    process = subprocess.Popen([str(x) for x in args], cwd=cwd, env=env,
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding='utf-8',
        errors='replace', creationflags=CREATE)
    CHILDREN.add(process)
    try:
        for line in process.stdout:
            EVENTS.put(line.rstrip())
        if process.wait():
            raise RuntimeError(f'Command exited {process.returncode}; see log above')
    finally:
        CHILDREN.discard(process)

def work(smoke=False):
    try:
        HOME.mkdir(parents=True, exist_ok=True)
        env = os.environ.copy()
        env['PYTHONUNBUFFERED'] = '1'
        env['HF_HUB_DISABLE_XET'] = '1'
        env['PATH'] = str(ROOT / 'git' / 'cmd') + os.pathsep + env.get('PATH', '')
        uv = ROOT / 'uv.exe'
        if not uv.is_file():
            raise FileNotFoundError('Bundled uv.exe is missing; extract the whole ZIP')
        if smoke:
            command([uv, '--version'], env=env)
            import ast
            for path in APP.glob('*.py'):
                ast.parse(path.read_text(encoding='utf-8'))
            EVENTS.put('@done')
            return
        EVENTS.put('Checking NVIDIA driver...')
        command(['nvidia-smi'], env=env)
        ready = ENV / 'launcher-ready'
        if not ready.exists():
            EVENTS.put('Setting up Python 3.11. Internet and substantial free disk space are required.')
            command([uv, 'venv', '--python', '3.11', '--python-preference', 'only-managed', ENV], env=env)
            EVENTS.put('Installing CUDA PyTorch (this can take several minutes)...')
            command([uv, 'pip', 'install', '--python', PYTHON, *CONFIG['torch'],
                     '--index-url', CONFIG['torch_index']], env=env)
            EVENTS.put('Installing application dependencies...')
            command([uv, 'pip', 'install', '--python', PYTHON, '-r', APP / 'requirements.txt'], env=env)
            if CONFIG['trellis']:
                git = ROOT / 'git' / 'cmd' / 'git.exe'
                trellis = ENV / 'TRELLIS'
                if not (trellis / '.git').exists():
                    command([git, 'clone', 'https://github.com/microsoft/TRELLIS.git', trellis], env=env)
                command([git, '-C', trellis, 'checkout', CONFIG['trellis']], env=env)
                command([git, '-C', trellis, 'submodule', 'update', '--init', '--recursive'], env=env)
            EVENTS.put('Downloading model weights. The bar shows bytes for the current model file.')
            command([PYTHON, ROOT / 'setup_worker.py', *CONFIG['models']], env=env)
            command([PYTHON, '-c', 'import torch; assert torch.cuda.is_available(), "CUDA unavailable"'], env=env)
            ready.write_text(STAMP, encoding='ascii')
        env['DOODLE2PART_TRELLIS'] = str(ENV / 'TRELLIS')
        # Outputs live outside Program Files/the extracted read-only payload.
        env['DOODLE2PART_OUTPUTS'] = str(HOME / 'outputs')
        EVENTS.put('Loading the models. Keep this window open while using the app.')
        process = subprocess.Popen([str(PYTHON), str(APP / 'server.py')], cwd=APP, env=env,
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding='utf-8',
            errors='replace', creationflags=CREATE)
        CHILDREN.add(process)
        def logs():
            for line in process.stdout:
                EVENTS.put(line.rstrip())
        threading.Thread(target=logs, daemon=True).start()
        url = f"http://127.0.0.1:{CONFIG['port']}/"
        deadline = time.monotonic() + 900
        while time.monotonic() < deadline and not STOP.is_set():
            if process.poll() is not None:
                raise RuntimeError('App exited during startup; see log and Retry')
            try:
                with urllib.request.urlopen(url, timeout=2) as response:
                    if response.status == 200:
                        webbrowser.open(url)
                        EVENTS.put('@ready')
                        process.wait()
                        if not STOP.is_set():
                            EVENTS.put('@error App stopped. Click Retry to restart.')
                        return
            except OSError:
                time.sleep(.5)
        raise RuntimeError('Startup timed out or was cancelled')
    except Exception as error:
        EVENTS.put('@error ' + str(error))

def main():
    smoke = '--smoke-test' in sys.argv
    ui = tk.Tk()
    ui.title(CONFIG['name'] + ' setup and launcher')
    ui.geometry('760x440')
    label = ttk.Label(ui, text='Setup downloads Python, CUDA packages and model weights on first run.', wraplength=720)
    label.pack(padx=16, pady=12, anchor='w')
    bar = ttk.Progressbar(ui, mode='indeterminate')
    bar.pack(fill='x', padx=16)
    log = tk.Text(ui, height=16, wrap='word')
    log.pack(fill='both', expand=True, padx=16, pady=12)
    result = [0]
    def start():
        retry.config(state='disabled')
        bar.config(mode='indeterminate')
        bar.start()
        threading.Thread(target=work, args=(smoke,), daemon=True).start()
    retry = ttk.Button(ui, text='Retry', command=start)
    retry.pack(side='left', padx=16, pady=8)
    def close():
        STOP.set()
        for child in list(CHILDREN):
            if child.poll() is None:
                subprocess.run(['taskkill', '/PID', str(child.pid), '/T', '/F'],
                               capture_output=True, creationflags=CREATE)
        ui.destroy()
    ttk.Button(ui, text='Stop and close', command=close).pack(side='right', padx=16)
    ui.protocol('WM_DELETE_WINDOW', close)
    def poll():
        try:
            while True:
                line = EVENTS.get_nowait()
                if line.startswith('@progress '):
                    data = json.loads(line[len('@progress '):])
                    bar.stop()
                    bar.config(mode='determinate', maximum=max(data['total'], 1), value=data['done'])
                    label.config(text=data['name'])
                elif line in ('@ready', '@done'):
                    bar.stop()
                    label.config(text='Running — close this window to stop the local server.')
                    if smoke:
                        close()
                        return
                elif line.startswith('@error '):
                    bar.stop()
                    label.config(text=line[7:])
                    retry.config(state='normal')
                    if smoke:
                        result[0] = 1
                        close()
                        return
                else:
                    log.insert('end', line + '\n')
                    log.see('end')
                    # Setup steps have no truthful overall percentage; use a spinner.
                    if line.startswith(('Installing', 'Setting up', 'Loading', 'Downloading')):
                        bar.stop()
                        bar.config(mode='indeterminate')
                        bar.start()
                        label.config(text=line)
        except queue.Empty:
            pass
        ui.after(100, poll)
    start()
    ui.after(100, poll)
    ui.mainloop()
    return result[0]

if __name__ == '__main__':
    from multiprocessing import freeze_support
    freeze_support()
    sys.exit(main())
