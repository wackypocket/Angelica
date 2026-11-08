#!/usr/bin/env python3
"""Runs the Khronos suite against the machine's own OpenGL driver, for telling what that driver supports from what glemu does.

usage, after a native build
(cmake -S <work directory>/src -B <work directory>/build-native -DDEQP_TARGET=x11_glx -DCMAKE_BUILD_TYPE=Release
 && make -C <work directory>/build-native glcts):
  cts_run_native.py <work directory> [case regex]    resumes unless the results are deleted

It runs the same cases as the backend run (<work directory>/run/vulkan/cases.txt), and results land in
<work directory>/run/native/results.tsv, laid out as cts_run.py lays out its own, so the two compare by case. A crash costs the case it happened in, and the run carries on from the next one.
"""
import collections
import pathlib
import re
import subprocess
import sys

CASE_LOG = re.compile(r'#beginTestCaseResult (\S+)\n(.*?)(?=#beginTestCaseResult|\Z)', re.S)
STATUS = re.compile(r'<Result StatusCode="(\w+)">([^<]*)')


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    work = pathlib.Path(sys.argv[1]).resolve()
    modules = work / 'build-native' / 'external' / 'openglcts' / 'modules'
    if not (modules / 'glcts').exists():
        sys.exit('no native build under ' + str(modules) + ': build it as the usage says')
    folder = work / 'run' / 'native'
    folder.mkdir(parents=True, exist_ok=True)
    cases = [c for c in (work / 'run' / 'vulkan' / 'cases.txt').read_text().split() if c]
    if len(sys.argv) > 2:
        pattern = re.compile(sys.argv[2])
        cases = [c for c in cases if pattern.search(c)]
    path = folder / 'results.tsv'
    results = {}
    if path.exists():
        for line in path.read_text().splitlines():
            parts = line.split('\t')
            results[parts[0]] = parts[1]
    attempt = len(list(folder.glob('attempt*.qpa')))
    while True:
        remaining = [c for c in cases if c not in results]
        if not remaining:
            break
        attempt += 1
        (folder / 'remaining.txt').write_text('\n'.join(remaining) + '\n')
        log = folder / f'attempt{attempt}.qpa'
        log.unlink(missing_ok=True)
        with open(folder / f'attempt{attempt}.out', 'w') as out:
            subprocess.run([str(modules / 'glcts'), '--deqp-archive-dir=' + str(modules), '--deqp-log-filename=' + str(log),
                            '--deqp-log-images=disable', '--deqp-log-shader-sources=disable', '--deqp-surface-width=256',
                            '--deqp-surface-height=256', '--deqp-gl-config-name=rgba8888d24s8ms0', '--deqp-surface-type=window',
                            '--deqp-watchdog=disable', '--deqp-caselist-file=' + str(folder / 'remaining.txt')],
                           stdout=out, stderr=subprocess.STDOUT, cwd=str(modules))
        text = log.read_text(errors='replace') if log.exists() else ''
        done = 0
        with path.open('a', newline='\n') as f:
            for match in CASE_LOG.finditer(text):
                found = STATUS.search(match.group(2))
                status = found.group(1) if found and '#endTestCaseResult' in match.group(2) else 'Crash'
                detail = found.group(2).strip().replace('\t', ' ').replace('\n', ' ')[:200] if found else ''
                results[match.group(1)] = status
                f.write(f'{match.group(1)}\t{status}\t{detail}\n')
                done += 1
            if done == 0:
                results[remaining[0]] = 'Crash'
                f.write(f'{remaining[0]}\tCrash\tnothing logged\n')
        print(f'attempt {attempt}: {done} cases, {len(remaining) - done} left', flush=True)
    print(dict(collections.Counter(results[c] for c in cases)))
    return 0


if __name__ == '__main__':
    sys.exit(main())
