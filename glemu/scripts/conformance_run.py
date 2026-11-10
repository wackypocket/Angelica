#!/usr/bin/env python3
"""Runs the conformance probes and test suites on any platform.

usage, from the repository root:
  conformance_run.py driver                    the machine's real OpenGL, skipping any call that crashes it
  conformance_run.py backend vulkan|metal      a backend, with every probe
  conformance_run.py only vulkan <regex>       a backend, with only the functions matching; answers go to *.partial
  conformance_run.py suites                    every test suite
  conformance_run.py report <refpages>         compares the answers and regenerates the checklist

Answers land in vulkan/build/conformance (the driver's too) and metal/build/conformance.
"""
import os
import pathlib
import subprocess
import sys

GRADLE = str(pathlib.Path('gradlew.bat').resolve()) if os.name == 'nt' else './gradlew'
BUILD = pathlib.Path('vulkan/build/conformance')
SKIP = pathlib.Path('glemu/scripts/conformance-probe-skip.txt')
TASK = {'vulkan': 'glsmVulkanTest', 'metal': 'glsmMetalTest'}
SUITES = (':glemu:test', ':glsm:test', ':test', ':vulkan:test', ':metal:test', ':sdl-gpu:test', ':metal:metalDeviceTest', ':metal:glsmMetalTest',
          ':vulkan:vulkanDeviceTest', ':vulkan:glsmVulkanTest', ':sdl-gpu:sdlGpuDeviceTest', ':sdl-gpu:glsmSdlTest')


def gradle(*args, env=None, timeout=None):
    full = dict(os.environ, **(env or {}))
    try:
        return subprocess.run([GRADLE, *args], env=full, capture_output=True, text=True, timeout=timeout)
    except subprocess.TimeoutExpired as e:
        return subprocess.CompletedProcess(e.cmd, 124, (e.stdout or b'').decode() if isinstance(e.stdout, bytes) else (e.stdout or ''), '')


def summary(text):
    return [line[:150] for line in text.splitlines() if ' FAILED' in line or 'BUILD ' in line or 'error:' in line or 'tests completed' in line or line.startswith('GlsmConformanceTest >')]


def driver():
    """A crash in the driver says nothing about how a call should answer, so the call goes on the skip list and the run starts again."""
    for attempt in range(1, 41):
        run = gradle(':vulkan:glOracleTest', '--rerun', env={'ANGELICA_CONFORMANCE_PROBE': '1'})
        if run.returncode == 0:
            print(f'finished on attempt {attempt}')
            return 0
        if 'finished with non-zero exit value' not in run.stdout + run.stderr:
            print('\n'.join(summary(run.stdout)))
            return 1
        progress = max((BUILD / 'guided-oracle.txt.progress', BUILD / 'modpath-oracle.txt.progress'), key=lambda p: p.stat().st_mtime if p.exists() else 0)
        calls = progress.read_text().splitlines() if progress.exists() else []
        if not calls:
            print('the driver run failed before any probe ran:')
            print('\n'.join(summary(run.stdout)))
            return 1
        last = calls[-1]
        if last in SKIP.read_text().splitlines():
            print('crashed again on a call already skipped: ' + last)
            return 1
        with SKIP.open('a', newline='\n') as f:
            f.write(last + '\n')
        print(f'attempt {attempt} crashed the driver on: {last}')
    return 1


def backend(name, only=None):
    env = {'ANGELICA_CONFORMANCE_PROBE': '1'}
    if only:
        env['ANGELICA_CONFORMANCE_ONLY'] = only
    run = gradle(f':{name}:{TASK[name]}', '--tests', '*GlsmConformanceTest*', '--rerun', env=env)
    print('\n'.join(summary(run.stdout)))
    return run.returncode


def suites():
    failed = 0
    for task in SUITES:
        run = gradle('--console=plain', task, '--continue')
        print(task, 'ok' if run.returncode == 0 else 'FAILED')
        print('\n'.join('  ' + line for line in summary(run.stdout) if ' FAILED' in line and '> Task' not in line))
        failed += run.returncode != 0
    return 1 if failed else 0


def report(refpages):
    """Non-zero when any step failed or a backend differs from the driver beyond the known differences."""
    known = 'glemu/scripts/conformance-known-differences.txt'
    failed = 0
    for backend_dir in ('vulkan', 'metal'):
        answers = pathlib.Path(backend_dir) / 'build/conformance/backend.txt'
        if answers.exists():
            print(backend_dir)
            failed += subprocess.run([sys.executable, 'glemu/scripts/compare_conformance.py', str(BUILD / 'oracle.txt'), str(answers), known]).returncode != 0
    failed += subprocess.run([sys.executable, '-I', 'glemu/scripts/gen_conformance_known.py']).returncode != 0
    failed += subprocess.run([sys.executable, '-I', 'glemu/scripts/gen_conformance_checklist.py', refpages, str(BUILD)]).returncode != 0
    return 1 if failed else 0


def main():
    args = sys.argv[1:]
    if args == ['driver']:
        return driver()
    if len(args) == 2 and args[0] == 'backend' and args[1] in TASK:
        return backend(args[1])
    if len(args) == 3 and args[0] == 'only' and args[1] in TASK:
        return backend(args[1], args[2])
    if args == ['suites']:
        return suites()
    if len(args) == 2 and args[0] == 'report':
        return report(args[1])
    print(__doc__)
    return 2


if __name__ == '__main__':
    sys.exit(main())
