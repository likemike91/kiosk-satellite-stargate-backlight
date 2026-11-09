#!/usr/bin/env python3
"""Compile against the published SDK and exercise backlight/lifecycle behavior."""
import os
from pathlib import Path
import subprocess
import tempfile
from android_sdk import android_platform

root = Path(__file__).resolve().parents[1]
sdk = Path(os.environ.get('ANDROID_HOME', os.environ.get('ANDROID_SDK_ROOT', str(Path.home() / 'android-sdk'))))
platform = str(android_platform(sdk))
java_home = os.environ.get('JAVA_HOME')
def tool(name):
    return str(Path(java_home) / 'bin' / name) if java_home else name

sources = [*sorted((root / 'sdk/src').rglob('*.java')),
           *sorted((root / 'src').rglob('*.java')),
           *sorted((root / 'tests').rglob('*.java'))]
with tempfile.TemporaryDirectory(prefix='stargate-test-') as directory:
    subprocess.run([tool('javac'), '--release', '8', '-cp', platform, '-d', directory,
                    *map(str, sources)], check=True)
    subprocess.run([tool('java'), '-ea', '-cp', os.pathsep.join([directory, platform]),
                    'dev.likemike.kiosk.stargate.BacklightTest'], check=True)
for name in ['test_android_sdk.py', 'test_plugin_manifest.py', 'test_plugin_assets.py']:
    subprocess.run(['python3', str(root / 'tools' / name)], check=True)
