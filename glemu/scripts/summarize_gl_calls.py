#!/usr/bin/env python3
"""Says which differences from a real driver a running game actually reaches.

usage: summarize_gl_calls.py <file named by -Dangelica.glCallLog> [probe-differences.txt from gen_conformance_checklist.py]

Prints every refused call, most frequent first, then each function the game called that still answers differently from a driver.
"""
import collections
import sys


def backend_name(gl_name):
    """The call log names the backend method a call reached: glBindBuffer is bindBuffer there."""
    return gl_name[2].lower() + gl_name[3:] if gl_name.startswith('gl') and len(gl_name) > 2 else gl_name


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    calls = collections.Counter()
    refusals = []
    for line in open(sys.argv[1]):
        parts = line.rstrip('\n').split('\t')
        if parts[0] == 'call':
            calls[parts[1]] += int(parts[2])
        elif parts[0] == 'refusal':
            refusals.append((int(parts[4]), parts[1], parts[2], parts[3]))
    print(f'{len(calls)} functions called {sum(calls.values())} times, {len(refusals)} kinds of refusal')
    for count, function, why, caller in sorted(refusals, reverse=True):
        print(f'{count:10} {function} {why} from {caller}')
    if len(sys.argv) > 2:
        differing = collections.Counter(line.split('\t')[0] for line in open(sys.argv[2]))
        reached = sorted(((calls[backend_name(f)], f, n) for f, n in differing.items() if calls[backend_name(f)] > 0), reverse=True)
        print(f'{len(reached)} functions the game called still answer differently')
        for count, function, n in reached:
            print(f'{count:10} {function} ({n} differences)')


if __name__ == '__main__':
    sys.exit(main())
