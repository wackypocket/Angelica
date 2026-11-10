#!/usr/bin/env python3
"""Builds the checklist of every error the OpenGL reference pages document, and how far each has been held to a real driver.

usage: gen_conformance_checklist.py <OpenGL-Refpages checkout> <build/conformance directory of the backend>

Reads oracle.txt, backend.txt and the probe-, guided- and modpath- answers from that directory, writes probe-differences.txt
there, and writes glemu/scripts/conformance-checklist.tsv. A row holds facts about a page, not its text: the function, the error, the parameters
the condition names, a key for telling conditions apart, and the page to read it on.

A condition that names parameters only counts calls that changed one of them, so an error from another argument does not satisfy it.
A row's status is one of:
  matches      the driver and the backend both gave this error for this condition
  differs      the driver gave this error and the backend gave another answer to the same call
  backend-only the driver cannot run this function, or never gave this error, and the backend did
  not-reached  no call so far has produced this error
"""
import collections
import hashlib
import os
import re
import sys

ERROR = re.compile(r'<constant>GL_(INVALID_ENUM|INVALID_VALUE|INVALID_OPERATION|INVALID_FRAMEBUFFER_OPERATION|OUT_OF_MEMORY|STACK_OVERFLOW|STACK_UNDERFLOW)</constant>')
SECTION = re.compile(r'<refsect1[^>]*xml:id="errors"[^>]*>(.*?)</refsect1>', re.S)
PARA = re.compile(r'<para>(.*?)</para>', re.S)
FUNCTION = re.compile(r'<funcdef>.*?<function>(gl\w+)</function>\s*</funcdef>', re.S)
PARAMETER = re.compile(r'<parameter>(\w+)</parameter>')
MENTION = re.compile(r'<function>(gl\w+)</function>')
NAME = {'INVALID_ENUM': 'invalid_enum', 'INVALID_VALUE': 'invalid_value', 'INVALID_OPERATION': 'invalid_operation',
        'INVALID_FRAMEBUFFER_OPERATION': 'invalid_framebuffer_operation', 'OUT_OF_MEMORY': 'out_of_memory',
        'STACK_OVERFLOW': 'stack_overflow', 'STACK_UNDERFLOW': 'stack_underflow'}
OUT = 'glemu/scripts/conformance-checklist.tsv'
SIGNATURES = 'glemu/scripts/conformance-signatures.tsv'
KNOWN = 'glemu/scripts/conformance-known-differences.txt'
POSITIONAL = re.compile(r'-?\d+(,-?\d+)*$')


def pages(root):
    """Prefers GL 4's page for a function, since a page in gl2.1 describes one with the rules of its day."""
    rows = {}
    for folder in ('gl4', 'gl2.1'):
        directory = os.path.join(root, folder)
        for name in sorted(os.listdir(directory)):
            if not name.startswith('gl') or not name.endswith('.xml'):
                continue
            text = open(os.path.join(directory, name), encoding='utf-8').read()
            section = SECTION.search(text)
            functions = sorted(set(FUNCTION.findall(text)))
            if not functions or section is None:
                continue
            for para in PARA.findall(section.group(1)):
                codes = ERROR.findall(para)
                if not codes:
                    continue
                params = sorted(set(PARAMETER.findall(para)))
                mentioned = [f for f in MENTION.findall(para) if f in functions]
                key = hashlib.sha1(re.sub(r'\s+', ' ', para).encode()).hexdigest()[:8]
                for function in mentioned or functions:
                    if any(r[0] == function and r[4] != folder for r in rows):
                        continue
                    for code in sorted(set(codes)):
                        rows[(function, NAME[code], ','.join(params), key, folder)] = name
    return rows


def answers(path):
    out = collections.defaultdict(dict)
    if os.path.exists(path):
        for line in open(path):
            if line.startswith('#'):
                continue
            function, args, answer = line.rstrip('\n').split('\t', 2)
            out[function][args] = answer
    return out


def signatures():
    out = collections.defaultdict(list)
    if os.path.exists(SIGNATURES):
        for line in open(SIGNATURES):
            if not line.startswith('#'):
                parts = line.rstrip('\n').split('\t')
                out[parts[0]].append(parts[1])
    return out


def changed(function, key, order):
    """Which parameter a call changed: a name, None for no change, or * for a scenario, which says only that it reached the error."""
    if key == 'valid':
        return None
    if POSITIONAL.match(key):
        values = key.split(',')
        names = order.get(function, [])
        moved = [i for i, v in enumerate(values) if v != '0']
        if not moved:
            return None
        return names[moved[0]] if len(names) == len(values) else '*'
    name, separator, _ = key.partition('=')
    return name if separator and name in order.get(function, []) else '*'


def relevant(function, params, key, order):
    named = set(params.split(',')) & set(order.get(function, [])) if params else set()
    if not named:
        return True
    which = changed(function, key, order)
    return which == '*' or which in named


def scenario_answers(path):
    """The hand written scenarios named after one call, whose error line is that call's answer."""
    out = collections.defaultdict(dict)
    if os.path.exists(path):
        for line in open(path):
            if line.startswith('#'):
                continue
            scenario, key, answer = line.rstrip('\n').split('\t', 2)
            match = re.search(r'\bgl[A-Z]\w+', scenario)
            if match and key == 'error':
                out[match.group(0)][scenario] = answer
    return out


def report_mod_path(build):
    """The calls a mod makes, through GLSM and its caches. An answer here also holds the state the call changed, as read back the same way."""
    theirs, mine = answers(os.path.join(build, 'modpath-oracle.txt')), answers(os.path.join(build, 'modpath-backend.txt'))
    known = set()
    if os.path.exists(KNOWN):
        for line in open(KNOWN):
            parts = line.rstrip('\n').split('\t')
            if parts[0] == 'modpath':
                known.add(parts[1])
    compared, explained, different = 0, 0, []
    for overload, calls in sorted(theirs.items()):
        for key, answer in sorted(calls.items()):
            if key in mine.get(overload, {}):
                compared += 1
                if mine[overload][key] == answer:
                    continue
                if f'{overload} {key}' in known:
                    explained += 1
                else:
                    different.append((overload, key, answer, mine[overload][key]))
    print(f'mod path: {compared} calls answered by both, {explained} known differences, {len(different)} answered differently')
    for row in different[:40]:
        print('  ' + ' | '.join(row))


def is_known_guided(function, key, known):
    """A guided line names a function and a key, where param=* is every change of that parameter and * every call."""
    return f'{function} {key}' in known or f'{function} {key.partition("=")[0]}=*' in known or f'{function} *' in known


def main():
    if len(sys.argv) != 3 or sys.argv[1].startswith('-'):
        print(__doc__)
        return 2
    root, build = sys.argv[1], sys.argv[2]
    rows = pages(root)
    order = signatures()
    driver = answers(os.path.join(build, 'probe-oracle.txt'))
    ours = answers(os.path.join(build, 'probe-backend.txt'))
    for name, target in (('guided-oracle.txt', driver), ('guided-backend.txt', ours)):
        for function, found in answers(os.path.join(build, name)).items():
            target[function].update(found)
    for name, target in (('oracle.txt', driver), ('backend.txt', ours)):
        for function, found in scenario_answers(os.path.join(build, name)).items():
            target[function].update(found)

    table = []
    counts = collections.Counter()
    for (function, error, params, key, folder), page in sorted(rows.items()):
        theirs = {a for a, e in driver.get(function, {}).items() if e == error and relevant(function, params, a, order)}
        mine = {a for a, e in ours.get(function, {}).items() if e == error and relevant(function, params, a, order)}
        if theirs:
            status = 'matches' if theirs <= mine else 'differs'
        elif mine:
            status = 'backend-only'
        else:
            status = 'not-reached'
        counts[status] += 1
        table.append('\t'.join((function, error, params, key, f'{folder}/{page}', status)))
    with open(OUT, 'w') as f:
        f.write('# Generated by glemu/scripts/gen_conformance_checklist.py. Function, error, parameters named, condition key, page, status.\n')
        f.write('\n'.join(table) + '\n')

    functions = {r[0] for r in rows}
    print(f'{len(rows)} documented errors across {len(functions)} functions')
    for status in ('matches', 'differs', 'backend-only', 'not-reached'):
        print(f'  {status}: {counts[status]}')

    report_mod_path(build)

    # Calls both sides answered, whatever the pages say, since a difference there needs no documentation to matter.
    known, known_guided = set(), set()
    known_path = 'glemu/scripts/conformance-known-differences.txt'
    if os.path.exists(known_path):
        for line in open(known_path):
            parts = line.rstrip('\n').split('\t')
            if parts[0] == 'probe':
                known.add(parts[1])
            elif parts[0] == 'guided':
                known_guided.add(parts[1])
    compared, explained, different = 0, 0, []
    for function, theirs in sorted(driver.items()):
        for args, answer in sorted(theirs.items()):
            if args in ours.get(function, {}):
                compared += 1
                if ours[function][args] == answer:
                    continue
                if '=' not in args and function in known or is_known_guided(function, args, known_guided):
                    explained += 1
                else:
                    different.append((function, args, answer, ours[function][args]))
    print(f'{compared} calls answered by both, {explained} known differences, {len(different)} answered differently')
    by_answer = collections.Counter((a, b) for _, _, a, b in different)
    for (a, b), n in by_answer.most_common():
        print(f'  driver {a}, ours {b}: {n}')
    with open(os.path.join(build, 'probe-differences.txt'), 'w') as f:
        for row in different:
            f.write('\t'.join(row) + '\n')


if __name__ == '__main__':
    sys.exit(main())
