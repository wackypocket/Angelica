#!/usr/bin/env python3
"""Generates GlOps and GpuRenderBackend in glemu: a forwarder for every RenderBackend method the GL classes implement.

GlBase and Vulkan's GL class together are the reference for the method set; the Metal GL class must implement GlOps too, which the compiler checks.
Run from the repository root after adding GL entry points: python3 glemu/scripts/gen_backend.py
"""
import os
import re
import sys

RB = 'glsm/src/main/java/com/gtnewhorizons/angelica/glsm/backend/RenderBackend.java'
GL = ['glemu/src/main/java/com/gtnewhorizons/angelica/glemu/gl/GlBase.java', 'vulkan/src/main/java/com/gtnewhorizons/angelica/vulkan/gl/VulkanGL.java']
BASE = 'glemu/src/main/java/com/gtnewhorizons/angelica/glemu/NativeBackendBase.java'
VALIDATION = 'glemu/src/main/java/com/gtnewhorizons/angelica/glemu/gl/GlValidation.java'
OUT_DIR = os.environ.get('ANGELICA_GEN_DIR', 'glemu/src/main/java/com/gtnewhorizons/angelica/glemu') + '/'

DECL = re.compile(r'^\s*(?:@\w+\s+)*(public|protected)\s+((?:abstract|final|static)\s+)*([\w<>\[\]\.]+)\s+(\w+)\(([^)]*)\)\s*(\{|;|throws)', re.M)


def norm_params(params):
    params = params.strip()
    if not params:
        return []
    out = []
    for p in params.split(','):
        p = p.strip().replace('final ', '')
        parts = p.rsplit(None, 1)
        out.append((parts[0].strip(), parts[1].strip()))
    return out


def methods(path):
    src = open(path).read()
    src = re.sub(r',\s*\n\s*', ', ', src)
    res = []
    for m in DECL.finditer(src):
        mods = m.group(2) or ''
        if 'static' in mods:
            continue
        res.append({'vis': m.group(1), 'final': 'final' in mods, 'abstract': 'abstract' in mods,
                    'ret': m.group(3), 'name': m.group(4), 'params': norm_params(m.group(5))})
    return res


def key(m):
    return m['name'] + '(' + ','.join(t for t, _ in m['params']) + ')'


checks = {}
for m in methods(VALIDATION):
    if m['vis'] == 'public' and m['ret'] == 'boolean':
        checks[m['name']] = m['params']


def guarded(m, statement):
    """The call, behind its check in GlValidation when there is one that this overload has the arguments for."""
    params = checks.get(m['name'])
    names = {n for _, n in m['params']}
    if params is None or not all(n in names for _, n in params):
        return statement
    args = ', '.join(n for _, n in params)
    return f'if (gl.validation().{m["name"]}({args})) {statement}'


def refused(ret):
    return {'void': '', 'boolean': 'false', 'int': '0', 'long': '0L', 'float': '0f', 'double': '0d'}.get(ret, 'null')


rb = methods(RB)
gl = {key(m): m for path in GL for m in methods(path) if m['vis'] == 'public'}
base = {key(m) for m in methods(BASE)}

lines = []
ops = []
missing = []
for m in rb:
    k = key(m)
    if m['final'] or k in base:
        continue
    if k not in gl:
        if m['abstract']:
            missing.append(k)
        continue
    params = ', '.join(f'{t} {n}' for t, n in m['params'])
    args = ', '.join(n for _, n in m['params'])
    call = f'gl.{m["name"]}({args})'
    ret = m['ret']
    call_void = guarded(m, call + ';').removesuffix(';')
    check = guarded(m, 'return ' + call + ';')
    returned = check if check.startswith('return') else check + f' else return {refused(ret)};'
    if ret == 'void':
        call = call_void
        body = (f'        if (enterUnlocked()) {{\n            try {{\n                {call};\n            }} finally {{\n                exitUnlocked();\n            }}\n            return;\n        }}\n'
                f'        lock.lock();\n        try {{\n            {call};\n        }} finally {{\n            lock.unlock();\n        }}\n')
    else:
        body = (f'        if (enterUnlocked()) {{\n            try {{\n                {returned}\n            }} finally {{\n                exitUnlocked();\n            }}\n        }}\n'
                f'        lock.lock();\n        try {{\n            {returned}\n        }} finally {{\n            lock.unlock();\n        }}\n')
    lines.append(f'    @Override {m["vis"]} {ret} {m["name"]}({params}) {{\n        if (GlCallLog.ENABLED) GlCallLog.call("{m["name"]}");\n{body}    }}\n')
    ops.append(f'    {ret} {m["name"]}({params});\n')

# Every other check must guard a RenderBackend method: GLSM calls the listed ones itself, and the gl* ones are for gen_entry_points.py.
unused = [n for n in checks if n not in {m['name'] for m in rb} and not n.startswith('gl') and n not in ('namedFramebufferRenderbuffer', 'namedFramebufferTextureLayer', 'isEnabled', 'uniformProgramBound', 'drawRange', 'drawPrimitive')]
if unused:
    print('GlValidation checks no RenderBackend method has: ' + ', '.join(sorted(unused)), file=sys.stderr)
    sys.exit(1)

if missing:
    print('GlBase and VulkanGL lack abstract methods:', file=sys.stderr)
    for k in missing:
        print('  ' + k, file=sys.stderr)
    sys.exit(1)

PKG = 'package com.gtnewhorizons.angelica.glemu;\n\n'
DEBUG_IMPORTS = 'import com.gtnewhorizons.angelica.glsm.backend.DebugMessageHandler;\nimport com.gtnewhorizons.angelica.glsm.backend.GLDebugMessageListener;\n'
NIO = ('import java.nio.ByteBuffer;\nimport java.nio.DoubleBuffer;\nimport java.nio.FloatBuffer;\n'
       'import java.nio.IntBuffer;\nimport java.nio.ShortBuffer;\n')
GEN = '// Generated by glemu/scripts/gen_backend.py from RenderBackend and GlBase/VulkanGL; do not edit by hand.\n'

OPS_DOC = ('/** The GL entry points a native backend\'s GL class exposes to its render backend, which forwards each one through its lock or main-thread fast path. */\n')
open(OUT_DIR + 'GlOps.java', 'w').write(PKG + DEBUG_IMPORTS + NIO + '\n' + GEN + OPS_DOC + 'public interface GlOps {\n    com.gtnewhorizons.angelica.glemu.gl.GlValidation validation();\n\n' + '\n'.join(ops) + '}\n')

CLASS_DOC = '''/**
 * Holds the lock and main-thread fast path every native backend forwards its GL calls through. A call from the main thread skips
 * the lock until another thread shares the context, which {@link #shared} records.
 */
'''
CLASS_HEAD = '''public abstract class GpuRenderBackend<G extends GlOps> extends WindowTrackingRenderBackend {
    protected G gl;
    protected final ReentrantLock lock = new ReentrantLock();
    protected volatile boolean shared;
    protected volatile boolean mainUnlocked;
    /** The thread the fast path belongs to; any other thread that calls in shares the context from then on and takes the lock. */
    protected volatile Thread mainThread;

    private static final VarHandle MAIN_UNLOCKED;

    static {
        try {
            MAIN_UNLOCKED = MethodHandles.lookup().findVarHandle(GpuRenderBackend.class, "mainUnlocked", boolean.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** How many unlocked calls the main thread is inside, as a debug callback can make GL calls from inside one. Main thread only. */
    private int unlockedDepth;

    protected final void exitUnlocked() {
        if (--unlockedDepth == 0) MAIN_UNLOCKED.setRelease(this, false);
    }

    /** Whether this is the main thread inside an unlocked call, where a nested call must not take the lock. */
    protected final boolean insideUnlockedCall() {
        final Thread main = mainThread;
        return unlockedDepth > 0 && (main == null || Thread.currentThread() == main);
    }

    protected final boolean enterUnlocked() {
        final Thread main = mainThread;
        final boolean onMain = main == null || Thread.currentThread() == main;
        // A call nested in an unlocked one stays unlocked: a thread sharing the context waits for the outermost to leave.
        if (onMain && unlockedDepth > 0) {
            unlockedDepth++;
            return true;
        }
        if (shared) return false;
        if (!onMain) {
            shared = true;
            while (mainUnlocked) Thread.onSpinWait();
            return false;
        }
        mainUnlocked = true;
        if (!shared) {
            unlockedDepth = 1;
            return true;
        }
        MAIN_UNLOCKED.setRelease(this, false);
        return false;
    }

'''
open(OUT_DIR + 'GpuRenderBackend.java', 'w').write(
    PKG + DEBUG_IMPORTS + 'import com.gtnewhorizons.angelica.glsm.backend.GlCallLog;\nimport com.gtnewhorizons.angelica.lwjgl3.WindowTrackingRenderBackend;\n\n' + 'import java.lang.invoke.MethodHandles;\nimport java.lang.invoke.VarHandle;\n' + NIO + 'import java.util.concurrent.locks.ReentrantLock;\n\n'
    + GEN + CLASS_DOC + CLASS_HEAD + '\n'.join(lines) + '}\n')
print(f'wrote {len(lines)} forwarding methods')
