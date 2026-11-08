#!/usr/bin/env python3
"""Compares what a backend answers with what a real OpenGL driver answers.

usage: compare_conformance.py <oracle.txt> <backend.txt> [known-differences.txt]

Each file holds tab separated lines of scenario, key and answer, from GlConformanceScenarios. A known difference is
a line of scenario, key and reason, where the key may be * for the whole scenario. Exits 1 if any other difference is found.
"""
import sys

PIXEL_TOLERANCE = 2


def read(path):
    answers = {}
    header = ''
    for line in open(path):
        line = line.rstrip('\n')
        if line.startswith('#'):
            header = line[2:]
            continue
        scenario, key, answer = line.split('\t', 2)
        answers[(scenario, key)] = answer
    return header, answers


def read_known(path):
    known = {}
    for line in open(path):
        line = line.strip()
        if not line or line.startswith('#'):
            continue
        scenario, key, reason = line.split('\t', 2)
        # The probe, guided and modpath lines are for gen_conformance_checklist.py.
        if scenario not in ('probe', 'guided', 'modpath'):
            known[(scenario, key)] = reason
    return known


def same(key, a, b):
    if a == b:
        return True
    # The driver here may lack what a compatibility profile has, so only what it has and we lack counts.
    if key.startswith('accept'):
        return a == 'false'
    if key.startswith('px:'):
        try:
            xs, ys = a.split(','), b.split(',')
            return len(xs) == len(ys) and all(abs(int(x) - int(y)) <= PIXEL_TOLERANCE for x, y in zip(xs, ys))
        except ValueError:
            return False
    try:
        return abs(float(a) - float(b)) < 1e-4
    except ValueError:
        return False


def describe(key, theirs, ours):
    """A whole image says little as two lists of numbers, so it is told as how many pixels differ, and the first."""
    a, b = theirs.split(','), ours.split(',')
    if not key.startswith('px:') or len(a) <= 16 or len(a) != len(b):
        return f'driver: {theirs} | ours: {ours}'
    side = int((len(a) / 4) ** 0.5)
    try:
        wrong = [i for i in range(0, len(a), 4) if any(abs(int(x) - int(y)) > PIXEL_TOLERANCE for x, y in zip(a[i:i + 4], b[i:i + 4]))]
    except ValueError:
        wrong = []
    if not wrong:
        return f'driver: {theirs} | ours: {ours}'
    first = wrong[0]
    return f'{len(wrong)} of {side * side} pixels differ, first at {first // 4 % side},{first // 4 // side}: driver {",".join(a[first:first + 4])} ours {",".join(b[first:first + 4])}'


def main():
    if len(sys.argv) not in (3, 4) or sys.argv[1].startswith('-'):
        print(__doc__)
        return 2
    oracle_name, oracle = read(sys.argv[1])
    backend_name, backend = read(sys.argv[2])
    known = read_known(sys.argv[3]) if len(sys.argv) > 3 else {}
    print(f'driver:  {oracle_name}\nbackend: {backend_name}')
    matched, explained, differences = 0, [], []
    scenarios = {s for s, _ in oracle} & {s for s, _ in backend}
    for scenario, key in oracle:
        if scenario not in scenarios:
            continue
        ours = backend.get((scenario, key), '(no answer)')
        if same(key, oracle[(scenario, key)], ours):
            matched += 1
        elif (scenario, key) in known or (scenario, '*') in known:
            explained.append((scenario, key, oracle[(scenario, key)], ours))
        else:
            differences.append((scenario, key, oracle[(scenario, key)], ours))
    print(f'{len(scenarios)} scenarios compared, {matched} answers match, {len(explained)} known differences, {len(differences)} differences')
    only = sorted({s for s, _ in backend} - scenarios)
    if only:
        print(f'{len(only)} scenarios the driver could not run: ' + ', '.join(only))
    for scenario, key, theirs, ours in differences:
        print(f'{scenario} | {key} | {describe(key, theirs, ours)}')
    return 1 if differences else 0


if __name__ == '__main__':
    sys.exit(main())
