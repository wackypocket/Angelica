#!/usr/bin/env python3
"""Reads what cts_run.py left in <work directory>/run/<backend>. Results files may be gzipped.

usage:
  cts_read.py causes <folder>                      failing cases by group, the device's refusals counted apart
  cts_read.py compare <before.tsv> <after.tsv>     cases whose status changed, by group, worst first
  cts_read.py messages <folder> <regex> [top]      the log lines of failing cases that match, most common first
  cts_read.py case <folder> <regex> [limit]        the end of the log of each case that matches
  cts_read.py forget <folder> <regex>              drops results so that cts_run.py <work> <backend> <regex> runs them again
"""
import collections
import glob
import gzip
import html
import os
import re
import sys

FAILING = ('Fail', 'InternalError', 'Crash', 'Hang')
CASE_LOG = re.compile(r'#beginTestCaseResult (\S+)\n(.*?)(?=#beginTestCaseResult|\Z)', re.S)
# Only what the backend itself says about the device: a case's name proves nothing, as most tessellation cases can run under MoltenVK.
DEVICE_REFUSALS = ('geometry or tessellation shaders are not supported by this device', 'geometry and tessellation shaders are not supported on Metal')
NOISE = re.compile(r'^(gl[A-Z]\w+\(|Ok$|Pass$|Fail$|Number of|Test case result)')


def read_results(path):
    opener = gzip.open if path.endswith('.gz') else open
    results = {}
    with opener(path, 'rt', encoding='utf-8', errors='replace') as lines:
        for line in lines:
            fields = line.rstrip('\r\n').split('\t')
            if len(fields) >= 2:
                results[fields[0]] = fields[1]
    return results


def logs(folder, wanted):
    """The latest log of each wanted case: a rerun lands in a later attempt."""
    found = {}
    attempts = sorted(glob.glob(os.path.join(folder, 'attempt*.qpa')), key=lambda p: int(re.search(r'attempt(\d+)', p).group(1)))
    for attempt in attempts:
        with open(attempt, encoding='utf-8', errors='replace') as log:
            for match in CASE_LOG.finditer(log.read()):
                if wanted(match.group(1)):
                    found[match.group(1)] = match.group(2)
    return found


def group(case):
    return case.split('.')[1]


def texts(body):
    return [html.unescape(text).strip() for text in re.findall(r'<Text>(.*?)</Text>', body, re.S)]


def failing_logs(folder, pattern=None):
    results = read_results(os.path.join(folder, 'results.tsv'))
    failing = {case for case, status in results.items() if status in FAILING and (pattern is None or pattern.search(case))}
    found = logs(folder, failing.__contains__)
    return {case: found.get(case, '') for case in failing}


def causes(folder):
    refused, others = collections.Counter(), collections.defaultdict(list)
    failing = failing_logs(folder)
    for case, body in failing.items():
        if any(refusal in body for refusal in DEVICE_REFUSALS):
            refused[group(case)] += 1
        else:
            others[group(case)].append(case)
    print(f'{len(failing)} failing; refused by the device: {sum(refused.values())}')
    print(' ', dict(refused.most_common()))
    print(f'others: {sum(len(cases) for cases in others.values())}')
    for name, cases in sorted(others.items(), key=lambda item: -len(item[1])):
        last = collections.Counter()
        for case in cases:
            lines = texts(failing[case])
            last[re.sub(r'\d+', '#', lines[-1])[:90] if lines else 'no log'] += 1
        print(len(cases), name, last.most_common(2), 'e.g.', cases[0].split('.', 2)[2])


def compare(before_path, after_path):
    before, after = read_results(before_path), read_results(after_path)
    changes = collections.Counter()
    example = {}
    for case, status in after.items():
        if case in before and before[case] != status:
            key = (before[case], status, group(case))
            changes[key] += 1
            example.setdefault(key, case)
    worse = [key for key in changes if key[0] == 'Pass']
    for key in worse + [key for key, _ in changes.most_common() if key not in worse]:
        print(changes[key], f'{key[0]} -> {key[1]}', key[2], 'e.g.', example[key].split('.', 2)[2])
    print(f'{len(after)} cases, {sum(changes.values())} changed, {sum(changes[key] for key in worse)} of them no longer passing')


def messages(folder, pattern, top=25):
    count = collections.Counter()
    example = {}
    for case, body in failing_logs(folder, pattern).items():
        for key in {re.sub(r'-?\d+(\.\d+)?', '#', text)[:170] for text in texts(body) if not NOISE.match(text)}:
            count[key] += 1
            example.setdefault(key, case)
    for key, times in count.most_common(top):
        print(times, key.replace('\n', ' | '), ' <<', example[key].split('.', 1)[1])


def show_case(folder, pattern, limit=3):
    shown = logs(folder, pattern.search)
    for case in sorted(shown)[:limit]:
        print('==', case)
        print('\n'.join(texts(shown[case])[-40:]))


def forget(folder, pattern):
    path = os.path.join(folder, 'results.tsv')
    with open(path, encoding='utf-8', errors='replace', newline='\n') as results:
        lines = results.readlines()
    kept = [line for line in lines if not pattern.search(line.split('\t', 1)[0])]
    with open(path, 'w', encoding='utf-8', newline='\n') as results:
        results.writelines(kept)
    print(f'forgot {len(lines) - len(kept)} of {len(lines)}')


def main():
    args = sys.argv[1:]
    command = args[0] if args else ''
    if command == 'causes' and len(args) == 2:
        causes(args[1])
    elif command == 'compare' and len(args) == 3:
        compare(args[1], args[2])
    elif command == 'messages' and len(args) >= 3:
        messages(args[1], re.compile(args[2]), *map(int, args[3:4]))
    elif command == 'case' and len(args) >= 3:
        show_case(args[1], re.compile(args[2]), *map(int, args[3:4]))
    elif command == 'forget' and len(args) == 3:
        forget(args[1], re.compile(args[2]))
    else:
        print(__doc__)
        return 2
    return 0


if __name__ == '__main__':
    sys.exit(main())
