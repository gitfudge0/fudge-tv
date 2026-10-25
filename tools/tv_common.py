"""Explicit-device ADB helpers. No discovery, connection, root, or shell interpolation."""
import argparse
import os
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]

def parser(description):
    p = argparse.ArgumentParser(description=description)
    p.add_argument('--serial', required=True, help='Exact serial from adb devices; no automatic selection')
    p.add_argument('--model', required=True, help='Exact ro.product.model value')
    p.add_argument('--sdk', required=True, type=int, help='Expected numeric ro.build.version.sdk')
    p.add_argument('--user', required=True, type=int, help='Explicit Android user ID')
    return p

class TV:
    def __init__(self, args):
        if not args.serial or args.serial.startswith('-') or re.search(r'\s', args.serial):
            raise ValueError('Invalid device serial')
        if args.sdk < 1 or args.user < 0 or not args.model.strip():
            raise ValueError('Invalid expected device identity')
        self.args = args

    def adb(self, *arguments):
        result = subprocess.run(['adb', '-s', self.args.serial, *arguments],
                                capture_output=True, text=True, timeout=30, check=False)
        if result.returncode:
            raise RuntimeError('ADB operation failed; verify connection and TV authorization')
        return result.stdout.strip()

    def check(self):
        if self.adb('get-state') != 'device':
            raise RuntimeError('Device is not authorized and ready')
        if self.adb('shell', 'getprop', 'ro.product.model') != self.args.model:
            raise RuntimeError('TV model mismatch; stopped')
        if self.adb('shell', 'getprop', 'ro.build.version.sdk') != str(self.args.sdk):
            raise RuntimeError('Android SDK mismatch; stopped')
        users = self.adb('shell', 'pm', 'list', 'users')
        if not re.search(r'UserInfo\{' + str(self.args.user) + r':', users):
            raise RuntimeError('Requested Android user does not exist')

    def home(self):
        value = self.adb('shell', 'cmd', 'package', 'resolve-activity', '--brief',
                         '--user', str(self.args.user), '-a', 'android.intent.action.MAIN',
                         '-c', 'android.intent.category.HOME')
        return canonical(value.splitlines()[-1]) if value else ''

def canonical(component):
    if not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_.]*/(?:[A-Za-z_][A-Za-z0-9_.]*|\.[A-Za-z_][A-Za-z0-9_.]*)', component):
        raise ValueError('Invalid explicit package/activity component')
    package, activity = component.split('/')
    return package + '/' + (package + activity if activity.startswith('.') else activity)

def private_dir(prefix):
    import tempfile
    root = ROOT / '.local'
    if root.is_symlink():
        raise RuntimeError('Private output directory must not be a symlink')
    root.mkdir(mode=0o700, exist_ok=True)
    os.chmod(root, 0o700)
    return Path(tempfile.mkdtemp(prefix=prefix, dir=root))

def write_private(path, text):
    with open(path, 'x', encoding='utf-8') as stream:
        os.chmod(path, 0o600)
        stream.write(text + '\n')
