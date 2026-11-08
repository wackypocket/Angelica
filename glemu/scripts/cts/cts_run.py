#!/usr/bin/env python3
"""Runs the Khronos OpenGL conformance suite against a backend, surviving crashes and hangs.

usage, from the repository root, after build_cts.py:
  cts_run.py <work directory> <vulkan|metal> [case regex]    resumes unless the results are deleted
  cts_run.py <work directory> <vulkan|metal> --summary       prints the results by group

A case that brings the process down is recorded as Crash or Hang and the run carries on from the next one. Results land in
<work directory>/run/<backend>/results.tsv.
"""
import collections
import os
import pathlib
import re
import subprocess
import sys

GRADLE = str(pathlib.Path('gradlew.bat').resolve()) if os.name == 'nt' else './gradlew'
TASK = {'vulkan': ':vulkan:glsmVulkanTest', 'metal': ':metal:glsmMetalTest'}
SUITE = 'KHR-GL46'
CASE_LOG = re.compile(r'#beginTestCaseResult (\S+)\n(.*?)(?=#beginTestCaseResult|\Z)', re.S)
STATUS = re.compile(r'<Result StatusCode="([^"]+)"[^>]*>([^<]*)')
HANG_MARK = 'cts: hung'


def library(work):
    modules = work / 'build/external/openglcts/modules'
    for name in ('libglcts.dylib', 'libglcts.so', 'glcts.dll'):
        if (modules / name).exists():
            return modules / name
    sys.exit('no built library under ' + str(modules) + ': run build_cts.py')


def common_args(work, log):
    return ['--deqp-archive-dir=' + str(work / 'build/external/openglcts/modules'), '--deqp-log-filename=' + str(log),
            '--deqp-log-images=disable', '--deqp-log-shader-sources=disable', '--deqp-surface-width=256', '--deqp-surface-height=256',
            '--deqp-gl-config-name=rgba8888d24s8ms0', '--deqp-surface-type=window', '--deqp-watchdog=disable', '--deqp-crashhandler=disable']


def launch(work, backend, args, folder, name):
    argfile = folder / (name + '.args')
    argfile.write_text('\n'.join(args) + '\n')
    env = dict(os.environ, ANGELICA_CTS_LIBRARY=str(library(work)), ANGELICA_CTS_ARGS=str(argfile), ANGELICA_CTS_HANG_SECONDS='180')
    with (folder / (name + '.out')).open('w') as out:
        return subprocess.call([GRADLE, TASK[backend], '--tests', '*GlsmKhronosCtsTest*', '--rerun', '-i'], env=env, stdout=out, stderr=subprocess.STDOUT)


def all_cases(work, backend, folder):
    listing = folder / 'cases.txt'
    if listing.exists():
        return listing.read_text().split()
    export = folder / (SUITE + '-cases.txt')
    launch(work, backend, common_args(work, folder / 'list.qpa') + ['--deqp-runmode=txt-caselist', '--deqp-caselist-export-file=' + str(folder) + '/${packageName}-cases.${typeExtension}'], folder, 'list')
    if not export.exists():
        sys.exit('the suite did not list its cases: see ' + str(folder / 'list.out'))
    cases = [line[6:] for line in export.read_text().splitlines() if line.startswith('TEST: ')]
    listing.write_text('\n'.join(cases) + '\n')
    return cases


def read_results(path):
    results = {}
    if path.exists():
        for line in path.read_text().splitlines():
            case, status, detail = line.split('\t', 2)
            results[case] = (status, detail)
    return results


def summary(results):
    groups = collections.defaultdict(collections.Counter)
    totals = collections.Counter()
    for case, (status, _) in results.items():
        groups[case.split('.')[1]][status] += 1
        totals[status] += 1
    for group in sorted(groups):
        print(group, dict(groups[group]))
    print('total', dict(totals))


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 2
    work, backend = pathlib.Path(sys.argv[1]).resolve(), sys.argv[2]
    if backend not in TASK:
        print(__doc__)
        return 2
    folder = work / 'run' / backend
    folder.mkdir(parents=True, exist_ok=True)
    path = folder / 'results.tsv'
    results = read_results(path)
    if sys.argv[3:] == ['--summary']:
        summary(results)
        return 0
    pattern = re.compile(sys.argv[3]) if len(sys.argv) > 3 else None
    cases = [c for c in all_cases(work, backend, folder) if pattern is None or pattern.search(c)]
    attempt = len(list(folder.glob('attempt*.qpa')))
    while True:
        remaining = [c for c in cases if c not in results]
        if not remaining:
            break
        attempt += 1
        log = folder / f'attempt{attempt}.qpa'
        log.unlink(missing_ok=True)
        (folder / 'remaining.txt').write_text('\n'.join(remaining) + '\n')
        launch(work, backend, common_args(work, log) + ['--deqp-caselist-file=' + str(folder / 'remaining.txt')], folder, f'attempt{attempt}')
        if ':compileJava FAILED' in (folder / f'attempt{attempt}.out').read_text(errors='replace'):
            sys.exit('the build failed: see ' + str(folder / f'attempt{attempt}.out'))
        text = log.read_text(errors='replace') if log.exists() else ''
        done = 0
        with path.open('a', newline='\n') as out:
            for match in CASE_LOG.finditer(text):
                case, body = match.group(1), match.group(2)
                found = STATUS.search(body)
                if found and '#endTestCaseResult' in body:
                    status, detail = found.group(1), found.group(2).strip().replace('\t', ' ').replace('\n', ' ')[:200]
                else:
                    hung = HANG_MARK in (folder / f'attempt{attempt}.out').read_text(errors='replace')
                    status, detail = ('Hang' if hung else 'Crash'), ''
                results[case] = (status, detail)
                out.write(f'{case}\t{status}\t{detail}\n')
                done += 1
        print(f'attempt {attempt}: {done} cases, {len(remaining) - done} left', flush=True)
        if done == 0:
            # Nothing even started: the first remaining case takes the blame so the run cannot loop forever.
            case = remaining[0]
            results[case] = ('Crash', 'nothing logged')
            with path.open('a', newline='\n') as out:
                out.write(f'{case}\tCrash\tnothing logged\n')
    summary({c: r for c, r in results.items() if c in set(cases)})
    return 0


if __name__ == '__main__':
    sys.exit(main())
