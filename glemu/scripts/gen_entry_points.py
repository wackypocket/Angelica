#!/usr/bin/env python3
"""Generates GlEntryPoints: the native GL functions that reach the emulated backends through LWJGL's function pointers.

A function appears here when no GLSM method has its exact C signature, usually because it takes a pointer. A body may name a
GLSM method as $glFoo, resolved to its owning class from GLSMOwners, and these helpers: rb() the backend, gl() the native GL
class, ints/floats/doubles/bytes(p, n) views of a pointer, pix(p) a pixel pointer, str(p) a C string, cstr(s) one to return.
Run from the repository root after changing the tables: python3 glemu/scripts/gen_entry_points.py
"""
import os
import re
import sys

import gen_entry_points_ext

OWNERS = 'glsm/src/main/java/com/gtnewhorizons/angelica/glsm/redirect/GLSMOwners.java'
OUT = os.environ.get('ANGELICA_GEN_DIR', 'glemu/src/main/java/com/gtnewhorizons/angelica/glemu/compat') + '/GlEntryPoints.java'
J = {'i': 'int', 'p': 'long', 'f': 'float', 'd': 'double', 's': 'short', 'y': 'byte', 'z': 'boolean', 'v': 'void'}

entries = {}


def fn(name, sig, body):
    """sig is the return type then the arguments, named a, b, c... in order."""
    entries[name] = (sig, body)


# Scalar readers for the suffixes of the fixed-function families: reading a pointer, and scaling integers as GL does for colors.
READ = {'b': ('memGetByte', 1, 'y'), 's': ('memGetShort', 2, 's'), 'i': ('memGetInt', 4, 'i'), 'f': ('memGetFloat', 4, 'f'),
        'd': ('memGetDouble', 8, 'd'), 'ub': ('memGetByte', 1, 'y'), 'us': ('memGetShort', 2, 's'), 'ui': ('memGetInt', 4, 'i')}
# A signed value maps to max(c / (2^(b-1) - 1), -1), so the most negative one is -1 too.
NORM = {'b': 'Math.max(%s / 127f, -1f)', 's': 'Math.max(%s / 32767f, -1f)', 'i': 'Math.max(%s / 2147483647f, -1f)', 'f': '%s', 'd': '(float) %s',
        'ub': '(%s & 0xFF) / 255f', 'us': '(%s & 0xFFFF) / 65535f', 'ui': '(%s & 0xFFFFFFFFL) / 4294967295f'}
PLAIN = {'s': '(float) %s', 'i': '(float) %s', 'f': '%s', 'd': '(float) %s'}
ARGS = 'abcdefgh'


def family(prefix, comps, suffixes, table, emit, lead=''):
    """Adds the scalar and pointer forms of glPrefixNt for each suffix."""
    for t in suffixes:
        read, size, sigc = READ[t]
        names = ARGS[len(lead):len(lead) + comps]
        fn(f'gl{prefix}{comps}{t}', 'v' + lead + sigc * comps, emit(comps, [table[t] % n for n in names]))
        p = ARGS[len(lead)]
        fn(f'gl{prefix}{comps}{t}v', 'v' + lead + 'p', emit(comps, [table[t] % f'{read}({p} + {i * size}L)' for i in range(comps)]))


def color(n, v):
    return f'$glColor{n}f({", ".join(v)});'


for n in (3, 4):
    family('Color', n, ['b', 's', 'i', 'f', 'd', 'ub', 'us', 'ui'], NORM, color)
family('Normal', 3, ['b', 's', 'i', 'f', 'd'], NORM, lambda n, v: f'$glNormal3f({", ".join(v)});')
family('SecondaryColor', 3, ['b', 's', 'i', 'f', 'd', 'ub', 'us', 'ui'], NORM, lambda n, v: f'$glSecondaryColor3f({", ".join(v)});')
for n in (1, 2, 3, 4):
    family('TexCoord', n, ['s', 'i', 'f', 'd'], PLAIN, lambda n, v: f'$glTexCoord{n}f({", ".join(v)});')
    family('MultiTexCoord', n, ['s', 'i', 'f', 'd'], PLAIN,
           lambda n, v: f'$glMultiTexCoord2f(a, {v[0]}, {v[1] if n > 1 else "0f"});', lead='i')
for n in (2, 3):
    family('Vertex', n, ['s', 'i', 'f', 'd'], PLAIN, lambda n, v: f'$glVertex{n}f({", ".join(v)});')
    family('RasterPos', n, ['s', 'i', 'f', 'd'], PLAIN, lambda n, v: f'$glRasterPos{n}f({", ".join(v)});')
family('Vertex', 4, ['s', 'i', 'f', 'd'], PLAIN, lambda n, v: f'final float w = {v[3]}; $glVertex3f(({v[0]}) / w, ({v[1]}) / w, ({v[2]}) / w);')
family('RasterPos', 4, ['s', 'i', 'f', 'd'], PLAIN, lambda n, v: f'$glRasterPos4f({", ".join(v)});')
for n in (1, 2, 3, 4):
    def attrib(n, v):
        full = list(v) + ['0f'] * (3 - len(v)) + ['1f'] * (1 if len(v) < 4 else 0)
        return f'$glVertexAttrib4f(a, {", ".join(full)});'
    family('VertexAttrib', n, ['s', 'f', 'd'], PLAIN, attrib, lead='i')
for t in ('b', 's', 'i', 'ub', 'us', 'ui'):
    read, size, _ = READ[t]
    fn(f'glVertexAttrib4N{t}v', 'vip', f'$glVertexAttrib4f(a, {", ".join(NORM[t] % f"{read}(b + {i * size}L)" for i in range(4))});')
fn('glVertexAttrib4Nub', 'viyyyy', '$glVertexAttrib4f(a, (b & 0xFF) / 255f, (c & 0xFF) / 255f, (d & 0xFF) / 255f, (e & 0xFF) / 255f);')
for t in ('b', 'i', 'ub', 'us', 'ui'):
    read, size, _ = READ[t]
    fn(f'glVertexAttrib4{t}v', 'vip', f'$glVertexAttrib4f(a, {", ".join(f"(float) {read}(b + {i * size}L)" for i in range(4))});')
for t, read, size, sigc in (('s', 'memGetShort', 2, 's'), ('i', 'memGetInt', 4, 'i'), ('f', 'memGetFloat', 4, 'f'), ('d', 'memGetDouble', 8, 'd')):
    fn(f'glRect{t}', 'v' + sigc * 4, 'rect((float) a, (float) b, (float) c, (float) d);')
    fn(f'glRect{t}v', 'vpp', f'rect((float) {read}(a), (float) {read}(a + {size}L), (float) {read}(b), (float) {read}(b + {size}L));')
fn('glEdgeFlagv', 'vp', '$glEdgeFlag(memGetByte(a) != 0);')

# Object names.
for name, gen, delete in (('Textures', '$glGenTextures()', '$glDeleteTextures(id)'), ('Buffers', '$glGenBuffers()', '$glDeleteBuffers(id)'),
                          ('Renderbuffers', '$glGenRenderbuffers()', '$glDeleteRenderbuffers(id)'), ('Framebuffers', '$glGenFramebuffers()', '$glDeleteFramebuffers(id)'),
                          ('VertexArrays', '$glGenVertexArrays()', '$glDeleteVertexArrays(id)'), ('Samplers', '$glGenSamplers()', '$glDeleteSamplers(id)'),
                          ('Queries', 'rb().genQueries()', 'rb().deleteQueries(id)')):
    fn(f'glGen{name}', 'vip', f'for (int i = 0; i < a; i++) memPutInt(b + i * 4L, {gen});')
    fn(f'glDelete{name}', 'vip', f'for (int i = 0; i < a; i++) {{ final int id = memGetInt(b + i * 4L); {delete}; }}')
fn('glCreateBuffers', 'vip', 'for (int i = 0; i < a; i++) memPutInt(b + i * 4L, rb().createBuffers());')
fn('glCreateFramebuffers', 'vip', 'for (int i = 0; i < a; i++) memPutInt(b + i * 4L, rb().createFramebuffers());')
fn('glCreateTextures', 'viip', 'for (int i = 0; i < b; i++) memPutInt(c + i * 4L, rb().createTextures(a));')

# Queries answered from the backend.
fn('glGetBooleanv', 'vip', 'rb().getBoolean(a, bytes(b, 16));')
fn('glGetIntegerv', 'vip', '$glGetInteger(a, ints(b, 16));')
fn('glGetFloatv', 'vip', '$glGetFloat(a, floats(b, 16));')
fn('glGetDoublev', 'vip', 'doubleValues(a, b);')
fn('glGetInteger64v', 'vip', 'integers64(a, b);')
fn('glGetIntegeri_v', 'viip', 'indexedIntegers(a, b, c);')
fn('glGetInteger64i_v', 'viip', 'memPutLong(c, rb().getIntegerIndexed(a, b));')
fn('glGetBooleani_v', 'viip', 'indexedBooleans(a, b, c);')
fn('glGetFloati_v', 'viip', 'indexedFloats(a, b, c);')
fn('glGetDoublei_v', 'viip', 'indexedDoubles(a, b, c);')
fn('glGetString', 'pi', 'return cstr($glGetString(a));')
fn('glGetStringi', 'pii', 'return cstr($glGetStringi(a, b));')
fn('glGetTexParameteriv', 'viip', 'if (b == 0x1004) gl().boundBorderColorOut(a, c, 0); else if (b == 0x8E46) { for (int n = 0; n < 4; n++) memPutInt(c + n * 4L, $glGetTexParameteri(a, 0x8E42 + n)); } else memPutInt(c, $glGetTexParameteri(a, b));')
fn('glGetTexParameterfv', 'viip', 'if (b == 0x1004) gl().boundBorderColorOut(a, c, 1); else memPutFloat(c, $glGetTexParameterf(a, b));')
fn('glGetTexLevelParameteriv', 'viiip', 'memPutInt(d, $glGetTexLevelParameteri(a, b, c));')
fn('glGetTexLevelParameterfv', 'viiip', 'memPutFloat(d, $glGetTexLevelParameteri(a, b, c));')
fn('glGetBufferParameteriv', 'viip', 'memPutInt(c, $glGetBufferParameteri(a, b));')
fn('glGetBufferParameteri64v', 'viip', 'memPutLong(c, $glGetBufferParameteri(a, b));')
fn('glGetShaderiv', 'viip', 'memPutInt(c, $glGetShaderi(a, b));')
fn('glGetProgramiv', 'viip', 'if (b == GL43.GL_COMPUTE_WORK_GROUP_SIZE) rb().getProgramiv(a, b, ints(c, 3)); else memPutInt(c, $glGetProgrami(a, b));')
fn('glGetRenderbufferParameteriv', 'viip', 'memPutInt(c, gl().renderbufferParameter(gl().st().renderbuffer, b));')
fn('glGetFramebufferAttachmentParameteriv', 'viiip', 'memPutInt(d, $glGetFramebufferAttachmentParameteri(a, b, c));')
fn('glGetLightfv', 'viip', '$glGetLight(a, b, floats(c, 4));')
fn('glGetLightiv', 'viip', '$glGetLight(a, b, ints(c, 4));')
fn('glGetMaterialfv', 'viip', '$glGetMaterial(a, b, floats(c, 4));')
fn('glGetMaterialiv', 'viip', '$glGetMaterial(a, b, ints(c, 4));')
fn('glGetTexEnvfv', 'viip', '$glGetTexEnv(a, b, floats(c, 4));')
fn('glGetTexEnviv', 'viip', '$glGetTexEnv(a, b, ints(c, 4));')
fn('glGetTexGenfv', 'viip', '$glGetTexGen(a, b, floats(c, 4));')
fn('glGetTexGeniv', 'viip', '$glGetTexGen(a, b, ints(c, 4));')
fn('glGetTexGendv', 'viip', '$glGetTexGen(a, b, doubles(c, 4));')
fn('glGetClipPlane', 'vip', '$glGetClipPlane(a, doubles(b, 4));')
fn('glGetQueryObjectiv', 'viip', 'queryObject(a, b, c, false);')
fn('glGetQueryObjectuiv', 'viip', 'queryObjectUnsigned(a, b, c);')
fn('glGetQueryObjecti64v', 'viip', 'queryObject(a, b, c, true);')
fn('glGetQueryObjectui64v', 'viip', 'queryObject(a, b, c, true);')
fn('glGetUniformfv', 'viip', 'rb().getUniformfv(a, b, floats(c, 16));')
fn('glGetUniformiv', 'viip', 'rb().getUniformiv(a, b, ints(c, 16));')
fn('glGetUniformuiv', 'viip', 'uniformUnsigned(a, b, c);')
fn('glGetUniformLocation', 'iip', 'return rb().getUniformLocation(a, str(b));')
fn('glGetAttribLocation', 'iip', 'return rb().getAttribLocation(a, str(b));')
fn('glGetUniformBlockIndex', 'iip', 'return rb().getUniformBlockIndex(a, str(b));')
fn('glBindAttribLocation', 'viip', 'rb().bindAttribLocation(a, b, str(c));')
fn('glBindFragDataLocation', 'viip', 'rb().bindFragDataLocation(a, b, str(c));')
fn('glGetShaderInfoLog', 'viipp', 'rb().getShaderInfoLog(a, c == 0L ? null : ints(c, 1), bytes(d, b));')
fn('glGetProgramInfoLog', 'viipp', 'rb().getProgramInfoLog(a, c == 0L ? null : ints(c, 1), bytes(d, b));')
fn('glGetShaderSource', 'viipp', 'rb().getShaderSource(a, c == 0L ? null : ints(c, 1), bytes(d, b));')
fn('glGetAttachedShaders', 'viipp', 'rb().getAttachedShaders(a, c == 0L ? null : ints(c, 1), ints(d, b));')
fn('glGetActiveUniform', 'viiipppp', 'rb().getActiveUniform(a, b, d == 0L ? null : ints(d, 1), ints(e, 1), ints(f, 1), bytes(g, c));')
fn('glGetActiveAttrib', 'viiipppp', 'rb().getActiveAttrib(a, b, d == 0L ? null : ints(d, 1), ints(e, 1), ints(f, 1), bytes(g, c));')
fn('glIsSync', 'zp', 'return gl().isSync(a);')
fn('glFenceSync', 'pii', 'return rb().fenceSync(a, b);')
fn('glDeleteSync', 'vp', 'rb().deleteSync(a);')
fn('glClientWaitSync', 'ipip', 'return rb().clientWaitSync(a, b, c);')
fn('glWaitSync', 'vpip', 'rb().waitSync(a, b, c);')
fn('glGetSynciv', 'vpiipp', 'final int v = rb().getSynci(a, b, ints(d, 1)); if (c > 0 && e != 0L) memPutInt(e, v); if (d != 0L) memPutInt(d, 1);')

# Fixed-function state through pointers.
fn('glFogfv', 'vip', '$glFog(a, floats(b, 4));')
fn('glFogiv', 'vip', 'if (a == 0xB66) $glFog(a, widen(b, 4)); else $glFogi(a, memGetInt(b));')
fn('glLightfv', 'viip', '$glLight(a, b, floats(c, 4));')
fn('glLightiv', 'viip', '$glLight(a, b, ints(c, 4));')
fn('glLightModelfv', 'vip', '$glLightModel(a, floats(b, 4));')
fn('glLightModeliv', 'vip', '$glLightModel(a, ints(b, 4));')
fn('glMaterialfv', 'viip', '$glMaterial(a, b, floats(c, 4));')
fn('glMaterialiv', 'viip', '$glMaterial(a, b, ints(c, 4));')
fn('glTexEnvfv', 'viip', '$glTexEnv(a, b, floats(c, 4));')
fn('glTexEnviv', 'viip', '$glTexEnv(a, b, ints(c, 4));')
fn('glTexGenfv', 'viip', '$glTexGen(a, b, floats(c, 4));')
fn('glTexGeniv', 'viip', '$glTexGen(a, b, ints(c, 4));')
fn('glTexGendv', 'viip', '$glTexGen(a, b, doubles(c, 4));')
fn('glClipPlane', 'vip', '$glClipPlane(a, doubles(b, 4));')
fn('glLoadMatrixf', 'vp', '$glLoadMatrix(floats(a, 16));')
fn('glLoadMatrixd', 'vp', '$glLoadMatrix(doubles(a, 16));')
fn('glMultMatrixf', 'vp', '$glMultMatrix(floats(a, 16));')
fn('glMultMatrixd', 'vp', '$glMultMatrix(doubles(a, 16));')
fn('glLoadTransposeMatrixf', 'vp', '$glLoadMatrix(transposed(a));')
fn('glLoadTransposeMatrixd', 'vp', '$glLoadMatrix(transposedD(a));')
fn('glMultTransposeMatrixf', 'vp', '$glMultMatrix(transposed(a));')
fn('glMultTransposeMatrixd', 'vp', '$glMultMatrix(transposedD(a));')
fn('glCallLists', 'viip', 'callLists(a, b, c);')
fn('glPointParameterfv', 'vip', 'rb().pointParameterf(a, memGetFloat(b));')
fn('glPointParameteriv', 'vip', 'rb().pointParameteri(a, memGetInt(b));')

# Textures.
fn('glTexImage1D', 'viiiiiiip', 'if (unpackBufferBound()) gl().texImageFromBuffer(a, b, c, d, 1, 1, f, g, h); else rb().texImage1D(a, b, c, d, e, f, g, pix(h));')
fn('glTexSubImage1D', 'viiiiiip', 'if (unpackBufferBound()) gl().texSubImageFromBuffer(a, b, c, 0, 0, d, 1, 1, e, f, g); else rb().texSubImage1D(a, b, c, d, e, f, pix(g));')
fn('glTexImage3D', 'viiiiiiiiip', 'if (unpackBufferBound()) gl().texImageFromBuffer(a, b, c, d, e, f, h, i, j); else rb().texImage3D(a, b, c, d, e, f, g, h, i, pix(j));')
fn('glTexSubImage3D', 'viiiiiiiiiip', 'if (unpackBufferBound()) gl().texSubImageFromBuffer(a, b, c, d, e, f, g, h, i, j, k); else rb().texSubImage3D(a, b, c, d, e, f, g, h, i, j, pix(k));')
fn('glTexParameterIiv', 'viip', 'GLSMTextures.glTexParameter(a, b, ints(c, 4));')
fn('glPatchParameterfv', 'vip', 'gl().patchDefaultLevels(a, b);')
fn('glTexParameteriv', 'viip', '$glTexParameter(a, b, ints(c, 4));')
fn('glTexParameterfv', 'viip', '$glTexParameter(a, b, floats(c, 4));')
fn('glTexParameterIiv', 'viip', 'texParameterIntegers(a, b, c);')
fn('glTexParameterIuiv', 'viip', 'texParameterIntegers(a, b, c);')
fn('glGetTexParameterIiv', 'viip', 'if (b == 0x1004) gl().boundBorderColorOut(a, c, 2); else if (b == 0x8E46) { for (int n = 0; n < 4; n++) memPutInt(c + n * 4L, $glGetTexParameteri(a, 0x8E42 + n)); } else memPutInt(c, $glGetTexParameteri(a, b));')
fn('glGetTexParameterIuiv', 'viip', 'if (b == 0x1004) gl().boundBorderColorOut(a, c, 2); else if (b == 0x8E46) { for (int n = 0; n < 4; n++) memPutInt(c + n * 4L, $glGetTexParameteri(a, 0x8E42 + n)); } else memPutInt(c, $glGetTexParameteri(a, b));')
fn('glCompressedTexImage1D', 'viiiiiip', 'gl().compressedTexImage(a, b, c, d, 1, 1, g);')
fn('glCompressedTexImage2D', 'viiiiiiip', 'gl().compressedTexImage(a, b, c, d, e, 1, h);')
fn('glCompressedTexImage3D', 'viiiiiiiip', 'gl().compressedTexImage(a, b, c, d, e, f, i);')
fn('glCompressedTexSubImage1D', 'viiiiiip', 'gl().compressedTexSubImage(a, b, c, 0, 0, d, 1, 1, e, g);')
fn('glCompressedTexSubImage2D', 'viiiiiiiip', 'gl().compressedTexSubImage(a, b, c, d, 0, e, f, 1, g, i);')
fn('glCompressedTexSubImage3D', 'viiiiiiiiiip', 'gl().compressedTexSubImage(a, b, c, d, e, f, g, h, i, k);')
fn('glGetCompressedTexImage', 'viip', 'getTexImageAt(a, b, 0, 0, c);')
fn('glGetnCompressedTexImage', 'viiip', 'getTexImageAt(a, b, 0, 0, d);')
fn('glGetCompressedTextureImage', 'viiip', 'gl().getCompressedTextureImage(a, b, d);')
fn('glGetCompressedTextureSubImage', 'viiiiiiiiip', 'gl().getCompressedTextureSubImage(a, b, c, d, e, f, g, h, j);')
fn('glTexStorage1D', 'viiii', 'gl().texStorage(a, b, c, d, 1, 1);')
fn('glTexStorage3D', 'viiiiii', 'gl().texStorage(a, b, c, d, e, f);')
for name, cols, rows in (('2', 2, 2), ('3', 3, 3), ('4', 4, 4), ('2x3', 2, 3), ('3x2', 3, 2), ('2x4', 2, 4), ('4x2', 4, 2), ('3x4', 3, 4), ('4x3', 4, 3)):
    fn(f'glUniformMatrix{name}fv', 'viizp', f'gl().uniformMatrixAt(a, b, {cols}, {rows}, c, d);')
    fn(f'glUniformMatrix{name}dv', 'viizp', f'gl().uniformMatrixDoubles(a, b, {cols}, {rows}, c, d);')
    fn(f'glProgramUniformMatrix{name}fv', 'viiizp', f'final int was = gl().useForUniform(a); gl().uniformMatrixAt(b, c, {cols}, {rows}, d, e); gl().useForUniform(was);')

# Uniforms: unsigned values are the same bits as signed ones, and doubles are narrowed since shaders here hold floats.
for n in (1, 2, 3, 4):
    i_args = 'bcde'[:n]
    args = ', '.join(i_args)
    pargs = ', '.join('cdef'[:n])
    fn(f'glUniform{n}ui', 'vi' + 'i' * n, f'rb().uniform{n}i(a, {args});')
    fn(f'glUniform{n}d', 'vi' + 'd' * n, f'uniformDoubleValues(a, {", ".join(i_args)});')
    buf = {1: 'uniform1fv', 2: 'uniform2', 3: 'uniform3', 4: 'uniform4'}[n]
    fn(f'glUniform{n}fv', 'viip', f'rb().{buf}(a, floats(c, b * {n}));')
    fn(f'glUniform{n}dv', 'viip', f'gl().uniformDoubles(a, b, {n}, c);')
    fn(f'glUniform{n}iv', 'viip', f'rb().uniform{n}iv(a, ints(c, b * {n}));')
    fn(f'glUniform{n}uiv', 'viip', f'rb().uniform{n}iv(a, ints(c, b * {n}));')
    fn(f'glProgramUniform{n}i', 'vii' + 'i' * n, f'final int was = gl().useForUniform(a); rb().uniform{n}i(b, {pargs}); gl().useForUniform(was);')
    fn(f'glProgramUniform{n}ui', 'vii' + 'i' * n, f'final int was = gl().useForUniform(a); rb().uniform{n}i(b, {pargs}); gl().useForUniform(was);')
    fn(f'glProgramUniform{n}f', 'vii' + 'f' * n, f'final int was = gl().useForUniform(a); rb().uniform{n}f(b, {pargs}); gl().useForUniform(was);')
    fn(f'glProgramUniform{n}d', 'vii' + 'd' * n, f'final int was = gl().useForUniform(a); uniformDoubleValues(b, {", ".join("cdef"[:n])}); gl().useForUniform(was);')
    fn(f'glProgramUniform{n}iv', 'viiip', f'final int was = gl().useForUniform(a); rb().uniform{n}iv(b, ints(d, c * {n})); gl().useForUniform(was);')
    fn(f'glProgramUniform{n}uiv', 'viiip', f'final int was = gl().useForUniform(a); rb().uniform{n}iv(b, ints(d, c * {n})); gl().useForUniform(was);')
    fn(f'glProgramUniform{n}fv', 'viiip', f'final int was = gl().useForUniform(a); rb().{buf}(b, floats(d, c * {n})); gl().useForUniform(was);')
    fn(f'glProgramUniform{n}dv', 'viiip', f'final int was = gl().useForUniform(a); gl().uniformDoubles(b, c, {n}, d); gl().useForUniform(was);')
fn('glShaderSource', 'viipp', 'rb().shaderSource(a, joined(b, c, d));')

# Per-buffer state, and draws with a different shape from what exists.
fn('glColorMaski', 'vizzzz', 'gl().colorMaski(a, b, c, d, e);')
fn('glBlendFunci', 'viii', 'rb().blendFuncSeparatei(a, b, c, b, c);')
fn('glBlendFuncSeparatei', 'viiiii', 'rb().blendFuncSeparatei(a, b, c, d, e);')
fn('glIsEnabledi', 'zii', 'return rb().isEnabledi(a, b);')
fn('glDepthRangef', 'vff', '$glDepthRange(a, b);')
fn('glClearDepthf', 'vf', '$glClearDepth(a);')
fn('glViewportIndexedf', 'viffff', 'setViewport(a, b, c, d, e);')
fn('glViewportIndexedfv', 'vip', 'setViewport(a, memGetFloat(b), memGetFloat(b + 4L), memGetFloat(b + 8L), memGetFloat(b + 12L));')
fn('glScissorIndexed', 'viiiii', 'setScissor(a, b, c, d, e);')
fn('glScissorIndexedv', 'vip', 'setScissor(a, memGetInt(b), memGetInt(b + 4L), memGetInt(b + 8L), memGetInt(b + 12L));')
fn('glDepthRangeIndexed', 'vidd', 'setDepthRange(a, b, c);')
fn('glDrawBuffers', 'vip', '$glDrawBuffers(ints(b, a));')
fn('glMultiDrawArrays', 'vippi', 'for (int i = 0; i < d; i++) { gl().st().drawId = i; $glDrawArrays(a, memGetInt(b + i * 4L), memGetInt(c + i * 4L)); } gl().st().drawId = 0;')
fn('glMultiDrawElements', 'vipipi', 'for (int i = 0; i < e; i++) { gl().st().drawId = i; GLSMDraws.glDrawElements(a, memGetInt(b + i * 4L), c, memGetAddress(d + i * (long) Pointer.POINTER_SIZE)); } gl().st().drawId = 0;')
fn('glMultiDrawElementsBaseVertex', 'vipipip', '$nglMultiDrawElementsBaseVertex(a, b, c, d, e, f);')
fn('glDrawElementsInstancedBaseVertex', 'viiipii', 'gl().drawElementsInstancedBaseVertex(a, b, c, d, e, f);')
fn('glDrawArraysInstancedBaseInstance', 'viiiii', 'setBaseInstance(e); GLSMDraws.glDrawArraysInstanced(a, b, c, d); setBaseInstance(0);')
fn('glDrawElementsInstancedBaseInstance', 'viiipii', 'setBaseInstance(f); GLSMDraws.glDrawElementsInstanced(a, b, c, d, e); setBaseInstance(0);')
fn('glDrawElementsInstancedBaseVertexBaseInstance', 'viiipiii', 'setBaseInstance(g); gl().drawElementsInstancedBaseVertex(a, b, c, d, e, f); setBaseInstance(0);')
fn('glDrawElementsIndirect', 'viip', 'GLSMDraws.glMultiDrawElementsIndirect(a, b, c, 1, 0);')
fn('glBufferData', 'vippi', '$nglBufferData(a, b, c, d);')
fn('glBufferSubData', 'vippp', '$nglBufferSubData(a, b, c, d);')
fn('glGetBufferSubData', 'vippp', '$nglGetBufferSubData(a, b, c, d);')
fn('glMapBuffer', 'pii', 'return $nglMapBuffer(a, b);')
fn('glMapBufferRange', 'pippi', 'return $glMapBufferRangeAddress(a, b, c, d);')
fn('glBufferStorage', 'vippi', 'if (c == 0L) rb().bufferStorage(a, b, d); else rb().bufferStorage(a, bytes(c, (int) b), d);')
fn('glClearBufferData', 'viiiip', 'rb().clearBufferData(a, b, c, d, clearTexel(e, c, d));')
fn('glClearBufferSubData', 'viippiip', 'rb().clearBufferSubData(a, b, c, d, e, f, clearTexel(g, e, f));')
fn('glNamedBufferData', 'vippi', 'if (c == 0L) rb().namedBufferData(a, b, d); else rb().namedBufferData(a, bytes(c, (int) b), d);')
fn('glNamedBufferSubData', 'vippp', 'rb().namedBufferSubData(a, b, bytes(d, (int) c));')
fn('glBindBuffersBase', 'viiip', 'bindBuffersBase(a, b, c, d);')
fn('glBindTextures', 'viip', 'for (int i = 0; i < b; i++) { final int texture = c == 0L ? 0 : memGetInt(c + i * 4L); if (gl().validation().bindTextureUnit(a + i, texture)) $glBindTextureUnit(a + i, texture); }')
fn('glBindSamplers', 'viip', 'for (int i = 0; i < b; i++) $glBindSampler(a + i, c == 0L ? 0 : memGetInt(c + i * 4L));')

# Clears of one attachment, with the rest masked off for the length of the clear.
fn('glClearBufferfv', 'viip', 'clearBuffer(a, b, memGetFloat(c), a == 0x1800 ? memGetFloat(c + 4L) : 0f, a == 0x1800 ? memGetFloat(c + 8L) : 0f, a == 0x1800 ? memGetFloat(c + 12L) : 0f);')
fn('glClearBufferiv', 'viip', 'clearBuffer(a, b, memGetInt(c), a == 0x1800 ? memGetInt(c + 4L) : 0, a == 0x1800 ? memGetInt(c + 8L) : 0, a == 0x1800 ? memGetInt(c + 12L) : 0);')
fn('glClearBufferuiv', 'viip', 'clearBuffer(a, b, memGetInt(c), a == 0x1800 ? memGetInt(c + 4L) : 0, a == 0x1800 ? memGetInt(c + 8L) : 0, a == 0x1800 ? memGetInt(c + 12L) : 0);')
fn('glClearBufferfi', 'viifi', 'clearBuffer(0x1801, b, c, 0f, 0f, 0f); clearBuffer(0x1802, b, d, 0f, 0f, 0f);')

# Vertex attribute and sampler state.
fn('glGetVertexAttribiv', 'viip', 'memPutInt(c, vertexAttrib(a, b));')
fn('glGetVertexAttribIiv', 'viip', 'memPutInt(c, vertexAttrib(a, b));')
fn('glGetVertexAttribIuiv', 'viip', 'memPutInt(c, vertexAttrib(a, b));')
fn('glGetVertexAttribfv', 'viip', 'memPutFloat(c, vertexAttrib(a, b));')
fn('glGetVertexAttribdv', 'viip', 'memPutDouble(c, vertexAttrib(a, b));')
fn('glGetVertexAttribPointerv', 'viip', 'memPutAddress(c, vertexAttribOffset(a));')
fn('glVertexBindingDivisor', 'vii', 'rb().vertexBindingDivisor(a, b);')
fn('glBindVertexBuffers', 'viippp', 'for (int i = 0; i < b; i++) rb().bindVertexBuffer(a + i, c == 0L ? 0 : memGetInt(c + i * 4L), d == 0L ? 0L : memGetAddress(d + i * (long) Pointer.POINTER_SIZE), e == 0L ? 0 : memGetInt(e + i * 4L));')
fn('glBindBuffersRange', 'viiippp', 'bindBuffersRange(a, b, c, d, e, f);')
fn('glBindImageTextures', 'viip', 'bindImageTextures(a, b, c);')
fn('glSamplerParameteriv', 'viip', 'samplerParameterv(a, b, c, false);')
fn('glSamplerParameterIiv', 'viip', 'samplerParameterI(a, b, c);')
fn('glSamplerParameterIuiv', 'viip', 'samplerParameterI(a, b, c);')
fn('glSamplerParameterfv', 'viip', 'samplerParameterv(a, b, c, true);')
fn('glGetSamplerParameteriv', 'viip', 'getSamplerParameterv(a, b, c, false);')
fn('glGetSamplerParameterIiv', 'viip', 'getSamplerParameterI(a, b, c);')
fn('glGetSamplerParameterIuiv', 'viip', 'getSamplerParameterI(a, b, c);')
fn('glGetSamplerParameterfv', 'viip', 'getSamplerParameterv(a, b, c, true);')

# Draws and state that exist under another shape.
fn('glDrawArraysIndirect', 'vip', 'drawArraysIndirect(a, b);')
fn('glMultiDrawArraysIndirect', 'vipii', 'for (int i = 0; i < c; i++) { gl().st().drawId = i; drawArraysIndirect(a, b + i * (long) (d == 0 ? 16 : d)); } gl().st().drawId = 0;')
fn('glDispatchComputeIndirect', 'vp', 'rb().dispatchComputeIndirect(a);')
fn('glBlendEquationi', 'vii', 'gl().blendEquationSeparatei(a, b, b);')
fn('glBlendEquationSeparatei', 'viii', 'gl().blendEquationSeparatei(a, b, c);')
fn('glProvokingVertex', 'vi', 'rb().provokingVertex(a);')
fn('glClearTexImage', 'viiiip', 'if (e == 0L) rb().clearTexImage(a, b, c, d); else gl().clearTexImageWith(a, b, c, d, e);')
fn('glClearTexSubImage', 'viiiiiiiiiip', 'gl().clearTexSubImage(a, b, c, d, e, f, g, h, i, j, k);')
fn('glProgramParameteri', 'viii', 'rb().programParameteri(a, b, c);')
fn('glProgramBinary', 'viipi', 'rb().programBinary(a, b, bytes(c, d));')
fn('glGetProgramBinary', 'viippp', 'rb().getProgramBinary(a, c == 0L ? null : ints(c, 1), ints(d, 1), bytes(e, b));')
fn('glSpecializeShader', 'vipipp', 'rb().specializeShader(a, str(b), ints(d, c), ints(e, c));')
fn('glGetQueryiv', 'viip', 'memPutInt(c, b == 0x8864 ? 64 : gl().currentQuery(a));')
fn('glGetFragDataLocation', 'iip', 'return ProgramInfo.fragDataLocation(a, str(b));')
fn('glGetUniformdv', 'viip', 'uniformDoubles(a, b, c);')
fn('glViewportArrayv', 'viip', 'for (int i = 0; i < b; i++) setViewport(a + i, memGetFloat(c + i * 16L), memGetFloat(c + i * 16L + 4L), memGetFloat(c + i * 16L + 8L), memGetFloat(c + i * 16L + 12L));')
fn('glScissorArrayv', 'viip', 'for (int i = 0; i < b; i++) setScissor(a + i, memGetInt(c + i * 16L), memGetInt(c + i * 16L + 4L), memGetInt(c + i * 16L + 8L), memGetInt(c + i * 16L + 12L));')
fn('glDepthRangeArrayv', 'viip', 'for (int i = 0; i < b; i++) setDepthRange(a + i, memGetDouble(c + i * 16L), memGetDouble(c + i * 16L + 8L));')
fn('glReadnPixels', 'viiiiiiip', 'rb().readPixels(a, b, c, d, e, f, h);')
fn('glGetnTexImage', 'viiiiip', 'getTexImageAt(a, b, c, d, f);')
fn('glGetTexImage', 'viiiip', 'getTexImageAt(a, b, c, d, e);')
fn('glGetnUniformfv', 'viiip', 'rb().getUniformfv(a, b, floats(d, c / 4));')
fn('glGetnUniformiv', 'viiip', 'rb().getUniformiv(a, b, ints(d, c / 4));')
fn('glGetnUniformuiv', 'viiip', 'rb().getUniformiv(a, b, ints(d, c / 4));')
fn('glPushDebugGroup', 'viiip', 'rb().pushDebugGroup(a, b, text(d, c));')
fn('glPopDebugGroup', 'v', 'rb().popDebugGroup();')
fn('glObjectLabel', 'viiip', 'rb().objectLabel(a, b, text(d, c));')
fn('glDebugMessageInsert', 'viiiiip', 'rb().debugMessageInsert(a, b, c, d, text(f, e));')
fn('glDebugMessageControl', 'viiiipz', 'rb().debugMessageControl(a, b, c, e == 0L ? null : ints(e, d), f);')
for name, cols, rows in (('2', 2, 2), ('3', 3, 3), ('4', 4, 4), ('2x3', 2, 3), ('3x2', 3, 2), ('2x4', 2, 4), ('4x2', 4, 2), ('3x4', 3, 4), ('4x3', 4, 3)):
    fn(f'glProgramUniformMatrix{name}dv', 'viiizp', f'final int was = gl().useForUniform(a); gl().uniformMatrixDoubles(b, c, {cols}, {rows}, d, e); gl().useForUniform(was);')

# Multisampled textures: the sample count rounds up to a power of two the device supports, and is at least 2.
fn('glTexImage3DMultisample', 'viiiiiiz', 'gl().multisampleImage(a, b, c, d, e, f, false);')
fn('glTexImage2DMultisample', 'viiiiiz', 'gl().multisampleImage(a, b, c, d, e, 1, false);')
fn('glTexStorage2DMultisample', 'viiiiiz', 'gl().multisampleImage(a, b, c, d, e, 1, true);')
fn('glTexStorage3DMultisample', 'viiiiiiz', 'gl().multisampleImage(a, b, c, d, e, f, true);')

# Direct state access: buffers, framebuffers and vertex arrays are bound for the length of the call; textures are looked up.
def dsa(kind, name, sig, body):
    fn(name, sig, f'final int was = bind{kind}(a); {body} bind{kind}(was);')


W = '0x8F37'
dsa('Buffer', 'glNamedBufferStorage', 'vippi', f'if (c == 0L) rb().bufferStorage({W}, b, d); else rb().bufferStorage({W}, bytes(c, (int) b), d);')
dsa('Buffer', 'glCopyNamedBufferSubData', 'viippp', 'final int read = rb().getInteger(0x8F36); rb().bindBuffer(0x8F36, a); rb().bindBuffer(0x8F37, b); rb().copyBufferSubData(0x8F36, 0x8F37, c, d, e); rb().bindBuffer(0x8F36, read);')
dsa('Buffer', 'glClearNamedBufferData', 'viiiip', f'rb().clearBufferData({W}, b, c, d, clearTexel(e, c, d));')
dsa('Buffer', 'glClearNamedBufferSubData', 'viippiip', f'rb().clearBufferSubData({W}, b, c, d, e, f, clearTexel(g, e, f));')
fn('glUnmapNamedBuffer', 'zi', f'final int was = bindBuffer(a); final boolean ok = rb().unmapBuffer({W}); bindBuffer(was); return ok;')
fn('glMapNamedBuffer', 'pii', f'final int was = bindBuffer(a); final long at = $nglMapBuffer({W}, b); bindBuffer(was); return at;')
fn('glMapNamedBufferRange', 'pippi', f'final int was = bindBuffer(a); final long at = $glMapBufferRangeAddress({W}, b, c, d); bindBuffer(was); return at;')
dsa('Buffer', 'glFlushMappedNamedBufferRange', 'vipp', f'rb().flushMappedBufferRange({W}, b, c);')
dsa('Buffer', 'glGetNamedBufferSubData', 'vippp', f'$nglGetBufferSubData({W}, b, c, d);')
dsa('Buffer', 'glGetNamedBufferParameteriv', 'viip', f'memPutInt(c, rb().getBufferParameteri({W}, b));')
dsa('Buffer', 'glGetNamedBufferParameteri64v', 'viip', f'memPutLong(c, rb().getBufferParameteri({W}, b));')
D = '0x8CA9'
dsa('Framebuffer', 'glNamedFramebufferDrawBuffer', 'vii', 'rb().drawBuffer(b);')
dsa('Framebuffer', 'glClearNamedFramebufferfv', 'viiip', 'clearBuffer(b, c, memGetFloat(d), b == 0x1800 ? memGetFloat(d + 4L) : 0f, b == 0x1800 ? memGetFloat(d + 8L) : 0f, b == 0x1800 ? memGetFloat(d + 12L) : 0f);')
dsa('Framebuffer', 'glClearNamedFramebufferiv', 'viiip', 'clearBuffer(b, c, memGetInt(d), b == 0x1800 ? memGetInt(d + 4L) : 0, b == 0x1800 ? memGetInt(d + 8L) : 0, b == 0x1800 ? memGetInt(d + 12L) : 0);')
dsa('Framebuffer', 'glClearNamedFramebufferuiv', 'viiip', 'clearBuffer(b, c, memGetInt(d), b == 0x1800 ? memGetInt(d + 4L) : 0, b == 0x1800 ? memGetInt(d + 8L) : 0, b == 0x1800 ? memGetInt(d + 12L) : 0);')
dsa('Framebuffer', 'glClearNamedFramebufferfi', 'viiifi', 'clearBuffer(0x1801, c, d, 0f, 0f, 0f); clearBuffer(0x1802, c, e, 0f, 0f, 0f);')
fn('glCheckNamedFramebufferStatus', 'iii', f'final int was = bindFramebuffer(a); final int status = rb().checkFramebufferStatus({D}); bindFramebuffer(was); return status;')
dsa('Framebuffer', 'glGetNamedFramebufferAttachmentParameteriv', 'viiip', f'memPutInt(d, rb().getFramebufferAttachmentParameteri({D}, b, c));')
fn('glNamedFramebufferReadBuffer', 'vii', 'rb().namedFramebufferReadBuffer(a, b);')
fn('glNamedFramebufferDrawBuffers', 'viip', 'rb().namedFramebufferDrawBuffers(a, ints(c, b));')
fn('glBlitNamedFramebuffer', 'viiiiiiiiiiii', 'rb().blitNamedFramebuffer(a, b, c, d, e, f, g, h, i, j, k, l);')
RB = '0x8D41'
dsa('Renderbuffer', 'glNamedRenderbufferStorage', 'viiii', f'rb().renderbufferStorage({RB}, b, c, d);')
dsa('Renderbuffer', 'glNamedRenderbufferStorageMultisample', 'viiiii', f'rb().renderbufferStorageMultisample({RB}, b, c, d, e);')
dsa('VertexArray', 'glEnableVertexArrayAttrib', 'vii', 'rb().enableVertexAttribArray(b);')
dsa('VertexArray', 'glDisableVertexArrayAttrib', 'vii', 'rb().disableVertexAttribArray(b);')
dsa('VertexArray', 'glVertexArrayAttribFormat', 'viiiizi', 'rb().vertexAttribFormat(b, c, d, e, f);')
dsa('VertexArray', 'glVertexArrayAttribIFormat', 'viiiii', 'rb().vertexAttribIFormat(b, c, d, e);')
dsa('VertexArray', 'glVertexArrayAttribBinding', 'viii', 'rb().vertexAttribBinding(b, c);')
dsa('VertexArray', 'glVertexArrayBindingDivisor', 'viii', 'rb().vertexBindingDivisor(b, c);')
dsa('VertexArray', 'glVertexArrayElementBuffer', 'vii', 'rb().bindBuffer(0x8893, b);')
dsa('VertexArray', 'glVertexArrayVertexBuffer', 'viiipi', 'rb().bindVertexBuffer(b, c, d, e);')
fn('glCreateVertexArrays', 'vip', 'for (int i = 0; i < a; i++) memPutInt(b + i * 4L, gl().createVertexArray());')
fn('glCreateRenderbuffers', 'vip', 'for (int i = 0; i < a; i++) memPutInt(b + i * 4L, gl().createRenderbuffer());')
fn('glCreateSamplers', 'vip', 'for (int i = 0; i < a; i++) memPutInt(b + i * 4L, rb().genSamplers());')
fn('glCreateQueries', 'viip', 'for (int i = 0; i < b; i++) memPutInt(c + i * 4L, gl().createQuery(a));')
fn('glGenerateTextureMipmap', 'vi', 'rb().generateTextureMipmap(a);')
fn('glTextureParameteri', 'viii', 'rb().textureParameteri(a, textureTarget(a), b, c);')
fn('glTextureParameterf', 'viif', 'rb().textureParameterf(a, textureTarget(a), b, c);')
fn('glTextureParameteriv', 'viip', 'rb().textureParameteriv(a, textureTarget(a), b, ints(c, 4));')
fn('glTextureParameterIiv', 'viip', 'textureParameterIntegers(a, b, c);')
fn('glTextureParameterIuiv', 'viip', 'textureParameterIntegers(a, b, c);')
fn('glTextureParameterfv', 'viip', 'textureParameterfv(a, b, c);')
fn('glGetTextureParameteriv', 'viip', 'gl().textureParameterOut(a, b, c, 0);')
fn('glGetTextureParameterIiv', 'viip', 'gl().textureParameterOut(a, b, c, 2);')
fn('glGetTextureParameterIuiv', 'viip', 'gl().textureParameterOut(a, b, c, 2);')
fn('glGetTextureParameterfv', 'viip', 'gl().textureParameterOut(a, b, c, 1);')
fn('glGetTextureLevelParameteriv', 'viiip', 'memPutInt(d, rb().getTextureLevelParameteri(a, b, c));')
fn('glGetTextureLevelParameterfv', 'viiip', 'memPutFloat(d, rb().getTextureLevelParameteri(a, b, c));')
fn('glTextureStorage1D', 'viiii', 'gl().textureStorage(a, b, c, d, 1, 1);')
fn('glTextureStorage2D', 'viiiii', 'gl().textureStorage(a, b, c, d, e, 1);')
fn('glTextureView', 'viiiiiiii', 'gl().textureView(a, b, c, d, e, f, g, h);')
fn('glTextureStorage3D', 'viiiiii', 'gl().textureStorage(a, b, c, d, e, f);')
fn('glTextureStorage2DMultisample', 'viiiiiz', 'gl().textureMultisample(a, b, c, d, e, 1);')
fn('glTextureStorage3DMultisample', 'viiiiiiz', 'gl().textureMultisample(a, b, c, d, e, f);')
fn('glTextureSubImage1D', 'viiiiiip', 'gl().textureSubImage(a, b, c, 0, 0, d, 1, 1, e, f, g);')
fn('glTextureSubImage2D', 'viiiiiiiip', 'gl().textureSubImage(a, b, c, d, 0, e, f, 1, g, h, i);')
fn('glTextureSubImage3D', 'viiiiiiiiiip', 'gl().textureSubImage(a, b, c, d, e, f, g, h, i, j, k);')
fn('glCompressedTextureSubImage1D', 'viiiiiip', 'gl().compressedTextureSubImage(a, b, c, 0, 0, d, 1, 1, e, g);')
fn('glCompressedTextureSubImage2D', 'viiiiiiiip', 'gl().compressedTextureSubImage(a, b, c, d, 0, e, f, 1, g, i);')
fn('glCompressedTextureSubImage3D', 'viiiiiiiiiip', 'gl().compressedTextureSubImage(a, b, c, d, e, f, g, h, i, k);')
fn('glCopyTextureSubImage1D', 'viiiiii', 'rb().copyTextureSubImage2D(a, textureTarget(a), b, c, 0, d, e, f, 1);')
fn('glCopyTextureSubImage3D', 'viiiiiiiii', 'gl().copyTextureSubImage3D(a, b, c, d, e, f, g, h, i);')
fn('glCopyTextureSubImage2D', 'viiiiiiii', 'rb().copyTextureSubImage2D(a, textureTarget(a), b, c, d, e, f, g, h);')

# Packed vertex data: each component is pulled out of a 2_10_10_10 word.
def packed(n, norm):
    return ', '.join(f'packed(TYPE, VALUE, {i}, {norm})' for i in range(n))


def packed_family(name, comps, emit, norm, lead='i'):
    for n in comps:
        v = packed(n, norm).replace('TYPE', 'a').replace('VALUE', 'b')
        fn(f'gl{name}P{n}ui', 'v' + lead + 'i', emit(n, v))
        fn(f'gl{name}P{n}uiv', 'v' + lead + 'p', emit(n, v.replace('b, ', 'memGetInt(b), ')))


packed_family('Vertex', (2, 3, 4), lambda n, v: f'$glVertex{n if n < 4 else 3}f({v});' if n < 4 else f'final float[] p = {{{v}}}; $glVertex3f(p[0] / p[3], p[1] / p[3], p[2] / p[3]);', 'false')
packed_family('TexCoord', (1, 2, 3, 4), lambda n, v: f'$glTexCoord{n}f({v});', 'false')
packed_family('Normal', (3,), lambda n, v: f'$glNormal3f({v});', 'true')
packed_family('Color', (3, 4), lambda n, v: f'$glColor{n}f({v});', 'true')
packed_family('SecondaryColor', (3,), lambda n, v: f'$glSecondaryColor3f({v});', 'true')
for n in (1, 2, 3, 4):
    first_two = ', '.join(f'packed(b, c, {i}, false)' for i in range(2)) if n > 1 else 'packed(b, c, 0, false), 0f'
    fn(f'glMultiTexCoordP{n}ui', 'viii', f'$glMultiTexCoord2f(a, {first_two});')
    fn(f'glMultiTexCoordP{n}uiv', 'viip', f'final int c0 = memGetInt(c); $glMultiTexCoord2f(a, {first_two.replace("b, c,", "b, c0,")});')
    pad = ', '.join([f'packed(b, VALUE, {i}, c)' for i in range(n)] + ['0f'] * (3 - n) + (['1f'] if n < 4 else []))
    fn(f'glVertexAttribP{n}ui', 'viizi', f'$glVertexAttrib4f(a, {pad.replace("VALUE", "d")});')
    fn(f'glVertexAttribP{n}uiv', 'viizp', f'final int d0 = memGetInt(d); $glVertexAttrib4f(a, {pad.replace("VALUE", "d0")});')

# Integer attributes keep their bits in the float constants, which reach the shader through an integer vertex format.
for n in (1, 2, 3, 4):
    names = 'bcde'[:n]
    for t, sigc in (('i', 'i'), ('ui', 'i')):
        fn(f'glVertexAttribI{n}{t}', 'vi' + sigc * n, 'attribI(a, ' + ', '.join(list(names) + ['0'] * (3 - n) + (['1'] if n < 4 else [])) + ');')
        ptr = [f'memGetInt(b + {i * 4}L)' for i in range(n)]
        fn(f'glVertexAttribI{n}{t}v', 'vip', 'attribI(a, ' + ', '.join(ptr + ['0'] * (3 - n) + (['1'] if n < 4 else [])) + ');')
    ptr = [f'memGetDouble(b + {i * 8}L)' for i in range(n)]
    pad = ['(float) ' + p for p in ptr] + ['0f'] * (3 - n) + (['1f'] if n < 4 else [])
    fn(f'glVertexAttribL{n}dv', 'vip', f'attribL(a, {", ".join(ptr + ["0d"] * (3 - n) + (["1d"] if n < 4 else []))});')
    fn(f'glVertexAttribL{n}d', 'vi' + 'd' * n, f'attribL(a, {", ".join(list(names) + ["0d"] * (3 - n) + (["1d"] if n < 4 else []))});')
for t, read in (('b', 'memGetByte(b + %dL)'), ('s', 'memGetShort(b + %dL)'), ('ub', 'memGetByte(b + %dL) & 0xFF'), ('us', 'memGetShort(b + %dL) & 0xFFFF')):
    size = 1 if t in ('b', 'ub') else 2
    fn(f'glVertexAttribI4{t}v', 'vip', 'attribI(a, ' + ', '.join(read % (i * size) for i in range(4)) + ');')
fn('glVertexAttribLPointer', 'viiiip', 'rb().vertexAttribPointer(a, b, c, false, d, e); gl().st().vao.isLong[a] = true;')
fn('glVertexAttribLFormat', 'viiii', 'rb().vertexAttribFormat(a, b, c, false, d); gl().st().vao.isLong[a] = true;')
dsa('VertexArray', 'glVertexArrayAttribLFormat', 'viiiii', 'rb().vertexAttribFormat(b, c, d, false, e); gl().st().vao.isLong[b] = true;')
fn('glGetVertexAttribLdv', 'viip', 'vertexAttribDoubles(a, b, c);')
fn('glMaxShaderCompilerThreadsKHR', 'vi', 'gl().maxShaderCompilerThreads(a);')
fn('glMaxShaderCompilerThreadsARB', 'vi', 'gl().maxShaderCompilerThreads(a);')
fn('glVertexArrayVertexBuffers', 'viiippp', 'final int was = bindVertexArray(a); for (int i = 0; i < c; i++) rb().bindVertexBuffer(b + i, d == 0L ? 0 : memGetInt(d + i * 4L), e == 0L ? 0L : memGetAddress(e + i * (long) Pointer.POINTER_SIZE), f == 0L ? 0 : memGetInt(f + i * 4L)); bindVertexArray(was);')

# Color index values have no effect in the RGBA mode these contexts are always in.
for t, sigc in (('d', 'd'), ('f', 'f'), ('i', 'i'), ('s', 's'), ('ub', 'y')):
    fn(f'glIndex{t}', 'v' + sigc, '')
    fn(f'glIndex{t}v', 'vp', '')
fn('glIndexPointer', 'viip', '')
# Fog comes from depth only, but the coordinate is kept to be queried.
for t, sigc, read in (('d', 'd', 'memGetDouble'), ('f', 'f', 'memGetFloat')):
    fn(f'glFogCoord{t}', 'v' + sigc, 'GlLegacy.fogCoord((float) a);')
    fn(f'glFogCoord{t}v', 'vp', f'GlLegacy.fogCoord((float) {read}(a));')

# Program introspection, from what linking reflected.
fn('glGetActiveUniformName', 'viiipp', 'putName(ProgramInfo.uniformName(a, b), c, d, e);')
fn('glGetActiveUniformsiv', 'viipip', 'for (int i = 0; i < b; i++) memPutInt(e + i * 4L, ProgramInfo.uniform(a, memGetInt(c + i * 4L), d));')
fn('glGetUniformIndices', 'viipp', 'for (int i = 0; i < b; i++) memPutInt(d + i * 4L, ProgramInfo.uniformIndex(a, str(memGetAddress(c + i * (long) Pointer.POINTER_SIZE))));')
fn('glGetActiveUniformBlockName', 'viiipp', 'putName(ProgramInfo.blockName(a, b), c, d, e);')
fn('glGetActiveUniformBlockiv', 'viiip', 'ProgramInfo.blockQuery(a, b, c, d);')
fn('glGetFragDataIndex', 'iip', 'return 0;')
fn('glGetProgramInterfaceiv', 'viiip', 'ProgramResources.interfaceQuery(a, b, c, d);')
fn('glGetProgramResourceIndex', 'iiip', 'return ProgramResources.index(a, b, str(c));')
fn('glGetProgramResourceName', 'viiiipp', 'ProgramResources.name(a, b, c, d, e, f);')
fn('glGetProgramResourceLocation', 'iiip', 'return ProgramResources.location(a, b, str(c));')
fn('glGetProgramResourceLocationIndex', 'iiip', 'return ProgramResources.locationIndex(a, b, str(c));')
fn('glGetProgramResourceiv', 'viiiipipp', 'ProgramResources.query(a, b, c, d, e, f, g, h);')
fn('glGetActiveAtomicCounterBufferiv', 'viiip', 'ProgramResources.atomicBuffer(a, b, c, d);')
fn('glGetProgramStageiv', 'viiip', 'memPutInt(d, ProgramInfo.programStage(a, b, c));')
fn('glGetInternalformativ', 'viiiip', 'com.gtnewhorizons.angelica.glemu.gl.InternalformatQuery.write(com.gtnewhorizons.angelica.glemu.gl.InternalformatQuery.of(gl(), a, b, c), d, e, false);')
fn('glGetInternalformati64v', 'viiiip', 'com.gtnewhorizons.angelica.glemu.gl.InternalformatQuery.write(com.gtnewhorizons.angelica.glemu.gl.InternalformatQuery.of(gl(), a, b, c), d, e, true);')
fn('glGetMultisamplefv', 'viip', 'final int samples = gl().framebufferSamples(gl().framebuffers.get(gl().st().drawFbo)); memPutFloat(c, gl().samplePosition(samples, b, false)); memPutFloat(c + 4L, gl().samplePosition(samples, b, true));')
fn('glFramebufferParameteri', 'viii', 'gl().framebufferParameter(gl().fboFor(a), b, c);')
fn('glNamedFramebufferParameteri', 'viii', 'gl().framebufferParameter(gl().framebuffers.getOrCreate(a), b, c);')
fn('glGetFramebufferParameteriv', 'viip', 'memPutInt(c, gl().framebufferParameter(gl().fboFor(a), b));')
fn('glGetNamedFramebufferParameteriv', 'viip', 'memPutInt(c, gl().framebufferParameter(gl().framebuffers.getOrCreate(a), b));')
fn('glGetNamedRenderbufferParameteriv', 'viip', 'memPutInt(c, gl().renderbufferParameter(a, b));')
fn('glGetQueryIndexediv', 'viiip', 'memPutInt(d, c == 0x8864 ? 64 : gl().currentQuery(a, b));')
fn('glBeginQueryIndexed', 'viii', 'if (b == 0) rb().beginQuery(a, c); else gl().beginQueryIndexed(a, b, c);')
fn('glEndQueryIndexed', 'vii', 'if (b == 0) rb().endQuery(a); else gl().endQueryIndexed(a, b);')
fn('glGetQueryBufferObjectiv', 'viiip', 'queryToBuffer(a, b, c, d, false);')
fn('glGetQueryBufferObjectuiv', 'viiip', 'queryToBufferUnsigned(a, b, c, d);')
fn('glGetQueryBufferObjecti64v', 'viiip', 'queryToBuffer(a, b, c, d, true);')
fn('glGetQueryBufferObjectui64v', 'viiip', 'queryToBuffer(a, b, c, d, true);')
fn('glGetObjectLabel', 'viiipp', 'putName(rb().getObjectLabel(a, b), c, d, e);')
fn('glObjectPtrLabel', 'vpip', '')
fn('glGetObjectPtrLabel', 'vpipp', 'putName("", b, c, d);')
fn('glDebugMessageCallback', 'vpp', 'debugMessageCallback(a, b);')
fn('glGetDebugMessageLog', 'iiipppppp', 'return rb().getDebugMessageLog(a, ints(c, a), ints(d, a), ints(e, a), ints(f, a), ints(g, a), bytes(h, b));')
fn('glGetPointerv', 'vip', 'final java.nio.ByteBuffer p = $glGetPointer(a, 0L); memPutAddress(b, p == null ? 0L : memAddress(p));')
fn('glGetBufferPointerv', 'viip', 'memPutAddress(c, gl().mappedPointer(a));')
dsa('Buffer', 'glGetNamedBufferPointerv', 'viip', f'memPutAddress(c, gl().mappedPointer({W}));')
fn('glGetUniformSubroutineuiv', 'viip', 'memPutInt(c, ProgramInfo.subroutineAt(a, b));')
fn('glGetnUniformdv', 'viiip', 'uniformDoubles(a, b, d);')
fn('glGetVertexArrayiv', 'viip', 'final int was = bindVertexArray(a); memPutInt(c, b == 0x8895 ? rb().getInteger(0x8895) : 0); bindVertexArray(was);')
fn('glGetVertexArrayIndexediv', 'viiip', 'final int was = bindVertexArray(a); memPutInt(d, vertexAttrib(b, c)); bindVertexArray(was);')
fn('glGetVertexArrayIndexed64iv', 'viiip', 'final int was = bindVertexArray(a); memPutLong(d, gl().st().vao.bindingOffset[b]); bindVertexArray(was);')
fn('glGetTextureImage', 'viiiiip', 'gl().getTextureImage(a, b, 0, 0, 0, -1, -1, -1, c, d, f);')
fn('glGetTextureSubImage', 'viiiiiiiiiiip', 'gl().getTextureImage(a, b, c, d, e, f, g, h, i, j, l);')

# Window position and raster state.
family('WindowPos', 2, ['s', 'i', 'f', 'd'], PLAIN, lambda n, v: f'GlLegacy.windowPos({v[0]}, {v[1]}, 0f);')
family('WindowPos', 3, ['s', 'i', 'f', 'd'], PLAIN, lambda n, v: f'GlLegacy.windowPos({v[0]}, {v[1]}, {v[2]});')

# Images drawn at the raster position.
fn('glPixelZoom', 'vff', 'GlLegacy.pixelZoom(a, b);')
fn('glDrawPixels', 'viiiip', 'GlLegacy.drawPixels(a, b, c, d, e);')
fn('glBitmap', 'viiffffp', 'GlLegacy.bitmap(a, b, c, d, e, f, g);')
fn('glCopyPixels', 'viiiii', 'GlLegacy.copyPixels(a, b, c, d, e);')
fn('glClearAccum', 'vffff', 'GlLegacy.clearAccum(a, b, c, d);')
fn('glPolygonStipple', 'vp', 'GlLegacy.polygonStipple(a);')
fn('glGetPolygonStipple', 'vp', 'GlLegacy.getPolygonStipple(a);')
fn('glGetnPolygonStipple', 'vip', 'GlLegacy.getPolygonStipple(b);')

# Evaluators.
fn('glMap1f', 'viffiip', 'GlEvaluators.map1(a, b, c, d, e, f, false);')
fn('glMap1d', 'viddiip', 'GlEvaluators.map1(a, (float) b, (float) c, d, e, f, true);')
fn('glMap2f', 'viffiiffiip', 'GlEvaluators.map2(a, b, c, d, e, f, g, h, i, j, false);')
fn('glMap2d', 'viddiiddiip', 'GlEvaluators.map2(a, (float) b, (float) c, d, e, (float) f, (float) g, h, i, j, true);')
fn('glMapGrid1f', 'viff', 'GlEvaluators.mapGrid1(a, b, c);')
fn('glMapGrid1d', 'vidd', 'GlEvaluators.mapGrid1(a, (float) b, (float) c);')
fn('glMapGrid2f', 'viffiff', 'GlEvaluators.mapGrid2(a, b, c, d, e, f);')
fn('glMapGrid2d', 'viddidd', 'GlEvaluators.mapGrid2(a, (float) b, (float) c, d, (float) e, (float) f);')
fn('glEvalCoord1f', 'vf', 'GlEvaluators.evalCoord1(a);')
fn('glEvalCoord1d', 'vd', 'GlEvaluators.evalCoord1((float) a);')
fn('glEvalCoord1fv', 'vp', 'GlEvaluators.evalCoord1(memGetFloat(a));')
fn('glEvalCoord1dv', 'vp', 'GlEvaluators.evalCoord1((float) memGetDouble(a));')
fn('glEvalCoord2f', 'vff', 'GlEvaluators.evalCoord2(a, b);')
fn('glEvalCoord2d', 'vdd', 'GlEvaluators.evalCoord2((float) a, (float) b);')
fn('glEvalCoord2fv', 'vp', 'GlEvaluators.evalCoord2(memGetFloat(a), memGetFloat(a + 4L));')
fn('glEvalCoord2dv', 'vp', 'GlEvaluators.evalCoord2((float) memGetDouble(a), (float) memGetDouble(a + 8L));')
fn('glEvalPoint1', 'vi', 'GlEvaluators.evalPoint1(a);')
fn('glEvalPoint2', 'vii', 'GlEvaluators.evalPoint2(a, b);')
fn('glEvalMesh1', 'viii', 'GlEvaluators.evalMesh1(a, b, c);')
fn('glEvalMesh2', 'viiiii', 'GlEvaluators.evalMesh2(a, b, c, d, e);')
fn('glGetMapfv', 'viip', 'GlEvaluators.getMap(a, b, c, 0);')
fn('glGetMapdv', 'viip', 'GlEvaluators.getMap(a, b, c, 1);')
fn('glGetMapiv', 'viip', 'GlEvaluators.getMap(a, b, c, 2);')
fn('glGetnMapfv', 'viiip', 'GlEvaluators.getMap(a, b, d, 0);')
fn('glGetnMapdv', 'viiip', 'GlEvaluators.getMap(a, b, d, 1);')
fn('glGetnMapiv', 'viiip', 'GlEvaluators.getMap(a, b, d, 2);')

# Pixel maps.
fn('glPixelMapfv', 'viip', 'GlLegacy.pixelMap(a, b, c, 0);')
fn('glPixelMapuiv', 'viip', 'GlLegacy.pixelMap(a, b, c, 1);')
fn('glPixelMapusv', 'viip', 'GlLegacy.pixelMap(a, b, c, 2);')
fn('glGetPixelMapfv', 'vip', 'GlLegacy.getPixelMap(a, b, 0);')
fn('glGetPixelMapuiv', 'vip', 'GlLegacy.getPixelMap(a, b, 1);')
fn('glGetPixelMapusv', 'vip', 'GlLegacy.getPixelMap(a, b, 2);')
fn('glGetnPixelMapfv', 'viip', 'GlLegacy.getPixelMap(a, c, 0);')
fn('glGetnPixelMapuiv', 'viip', 'GlLegacy.getPixelMap(a, c, 1);')
fn('glGetnPixelMapusv', 'viip', 'GlLegacy.getPixelMap(a, c, 2);')

# Selection and feedback, which take their buffers as sized pointers here.
fn('glFeedbackBuffer', 'viip', 'GLSMDraws.glFeedbackBuffer(b, memFloatBuffer(c, a));')
fn('glSelectBuffer', 'vip', 'GLSMDraws.glSelectBuffer(memIntBuffer(b, a));')
fn('glArrayElement', 'vi', 'ImmediateModeRecorder.arrayElement(a);')

# With no buffer bound these pointers are client memory, which GLSM takes as a buffer rather than an offset.
for name in ['glVertexPointer', 'glColorPointer', 'glTexCoordPointer']:
    fn(name, 'viiip', f'if (isClientMemory(d)) GLSMVertexInput.{name}(a, b, c, clientMemory(d)); else GLSMVertexInput.{name}(a, b, c, d);')
fn('glNormalPointer', 'viip', 'if (isClientMemory(c)) GLSMVertexInput.glNormalPointer(a, b, clientMemory(c)); else GLSMVertexInput.glNormalPointer(a, b, c);')
fn('glEdgeFlagPointer', 'vip', 'if (isClientMemory(b)) GLSMVertexInput.glEdgeFlagPointer(a, clientMemory(b)); else GLSMVertexInput.glEdgeFlagPointer(a, b);')
fn('glVertexAttribPointer', 'viiizip', 'if (isClientMemory(f)) GLSMVertexInput.glVertexAttribPointer(a, b, c, d, e, clientMemory(f)); else GLSMVertexInput.glVertexAttribPointer(a, b, c, d, e, f);')
fn('glVertexAttribIPointer', 'viiiip', 'if (isClientMemory(e)) GLSMVertexInput.glVertexAttribIPointer(a, b, c, d, clientMemory(e)); else GLSMVertexInput.glVertexAttribIPointer(a, b, c, d, e);')

# Buffer textures: the buffer is copied to the image the lowered shader reads, at the draws that sample it.
fn('glTexBuffer', 'viii', 'gl().texBuffer(b, c, 0L, -1L);')
fn('glTexBufferRange', 'viiipp', 'gl().texBuffer(b, c, d, e);')
fn('glTextureBuffer', 'viii', 'gl().textureBuffer(a, b, c, 0L, -1L);')
fn('glTextureBufferRange', 'viiipp', 'gl().textureBuffer(a, b, c, d, e);')

# Program pipelines.
fn('glGenProgramPipelines', 'vip', 'for (int i = 0; i < a; i++) memPutInt(b + i * 4L, GlPipelines.reserve());')
fn('glCreateProgramPipelines', 'vip', 'for (int i = 0; i < a; i++) memPutInt(b + i * 4L, GlPipelines.create());')
fn('glDeleteProgramPipelines', 'vip', 'for (int i = 0; i < a; i++) GlPipelines.delete(memGetInt(b + i * 4L));')
fn('glIsProgramPipeline', 'zi', 'return GlPipelines.exists(a);')
fn('glBindProgramPipeline', 'vi', 'GlPipelines.bind(a);')
fn('glUseProgramStages', 'viii', 'GlPipelines.useStages(a, b, c);')
fn('glActiveShaderProgram', 'vii', 'GlPipelines.activeProgram(a, b);')
fn('glCreateShaderProgramv', 'iiip', 'final StringBuilder source = new StringBuilder(); for (int i = 0; i < b; i++) source.append(str(memGetAddress(c + i * (long) Pointer.POINTER_SIZE))); return GlPipelines.createShaderProgram(a, source.toString());')
fn('glValidateProgramPipeline', 'vi', 'GlPipelines.validate(a);')
fn('glGetProgramPipelineiv', 'viip', 'memPutInt(c, GlPipelines.query(a, b));')
fn('glGetProgramPipelineInfoLog', 'viipp', 'putName(GlPipelines.infoLog(a), b, c, d);')

# Subroutines, which the shader compiler lowers to ints and switches.
fn('glGetSubroutineIndex', 'iiip', 'return ProgramInfo.subroutineIndex(a, b, str(c));')
fn('glGetSubroutineUniformLocation', 'iiip', 'return ProgramInfo.subroutineUniformLocation(a, b, str(c));')
fn('glGetActiveSubroutineName', 'viiiipp', 'putName(ProgramInfo.subroutineName(a, b, c), d, e, f);')
fn('glGetActiveSubroutineUniformName', 'viiiipp', 'putName(ProgramInfo.subroutineUniformName(a, b, c), d, e, f);')
fn('glGetActiveSubroutineUniformiv', 'viiiip', 'ProgramInfo.subroutineUniformQuery(a, b, c, d, e);')
fn('glUniformSubroutinesuiv', 'viip', 'ProgramInfo.setSubroutines(a, b, c);')

# Transform feedback, captured by storage buffer writes in the program's vertex shader.
fn('glGenTransformFeedbacks', 'vip', 'for (int i = 0; i < a; i++) memPutInt(b + i * 4L, GlTransformFeedback.reserve());')
fn('glCreateTransformFeedbacks', 'vip', 'for (int i = 0; i < a; i++) memPutInt(b + i * 4L, GlTransformFeedback.create());')
fn('glDeleteTransformFeedbacks', 'vip', 'for (int i = 0; i < a; i++) GlTransformFeedback.delete(memGetInt(b + i * 4L));')
fn('glIsTransformFeedback', 'zi', 'return GlTransformFeedback.exists(a);')
fn('glBindTransformFeedback', 'vii', 'GlTransformFeedback.bind(b);')
fn('glTransformFeedbackVaryings', 'viipi', 'final String[] names = new String[b]; for (int i = 0; i < b; i++) names[i] = str(memGetAddress(c + i * (long) Pointer.POINTER_SIZE)); GlTransformFeedback.varyings(a, names, d);')
fn('glGetTransformFeedbackVarying', 'viiipppp', 'if (e != 0L) memPutInt(e, 1); if (f != 0L) memPutInt(f, GlTransformFeedback.varyingType(a, b)); putName(GlTransformFeedback.varyingName(a, b), c, d, g);')
fn('glBeginTransformFeedback', 'vi', 'GlTransformFeedback.begin(a);')
fn('glEndTransformFeedback', 'v', 'GlTransformFeedback.end();')
fn('glPauseTransformFeedback', 'v', 'GlTransformFeedback.pause();')
fn('glResumeTransformFeedback', 'v', 'GlTransformFeedback.resume();')
fn('glTransformFeedbackBufferBase', 'viii', 'GlTransformFeedback.bindBuffer(a, b, c, 0L, 0L);')
fn('glTransformFeedbackBufferRange', 'viiipp', 'GlTransformFeedback.bindBuffer(a, b, c, d, e);')
fn('glDrawTransformFeedback', 'vii', 'GLSMDraws.glDrawArrays(a, 0, GlTransformFeedback.vertices(b));')
fn('glDrawTransformFeedbackInstanced', 'viii', 'GLSMDraws.glDrawArraysInstanced(a, 0, GlTransformFeedback.vertices(b), c);')
fn('glDrawTransformFeedbackStream', 'viii', 'GLSMDraws.glDrawArrays(a, 0, GlTransformFeedback.vertices(b, c));')
fn('glDrawTransformFeedbackStreamInstanced', 'viiii', 'GLSMDraws.glDrawArraysInstanced(a, 0, GlTransformFeedback.vertices(b, c), d);')
fn('glGetTransformFeedbackiv', 'viip', 'memPutInt(c, GlTransformFeedback.query(a, b));')
fn('glGetTransformFeedbacki_v', 'viiip', 'memPutInt(d, (int) GlTransformFeedback.bufferQuery(a, b, c));')
fn('glGetTransformFeedbacki64_v', 'viiip', 'memPutLong(d, GlTransformFeedback.bufferQuery(a, b, c));')

# ARB_shader_objects is advertised, and these are its names for the same objects.
fn('glCreateProgramObjectARB', 'i', 'return GLSMPrograms.glCreateProgram();')
fn('glCreateShaderObjectARB', 'ii', 'return GLSMPrograms.glCreateShader(a);')
fn('glAttachObjectARB', 'vii', 'GLSMPrograms.glAttachShader(a, b);')
fn('glDetachObjectARB', 'vii', 'GLSMPrograms.glDetachShader(a, b);')
fn('glUseProgramObjectARB', 'vi', 'GLSMPrograms.glUseProgram(a);')
fn('glDeleteObjectARB', 'vi', 'if (rb().isProgram(a)) GLSMPrograms.glDeleteProgram(a); else GLSMPrograms.glDeleteShader(a);')
fn('glGetHandleARB', 'ii', 'return a == 0x8B40 ? GLStateManager.ctx().activeProgram : 0;')
fn('glGetObjectParameterivARB', 'viip', 'memPutInt(c, rb().isProgram(a) ? rb().getProgrami(a, b) : rb().getShaderi(a, b));')
fn('glGetObjectParameterfvARB', 'viip', 'memPutFloat(c, rb().isProgram(a) ? rb().getProgrami(a, b) : rb().getShaderi(a, b));')
fn('glGetInfoLogARB', 'viipp', 'putName(rb().isProgram(a) ? rb().getProgramInfoLog(a, b) : rb().getShaderInfoLog(a, b), b, c, d);')
fn('glGetAttachedObjectsARB', 'viipp', 'glGetAttachedShaders(a, b, c, d);')

# Direct state access under EXT names, which the ARB classes that advertise it also carry.
fn('glVertexArrayVertexAttribDivisorEXT', 'viii', 'final int was = bindVertexArray(a); rb().vertexAttribDivisor(b, c); bindVertexArray(was);')
fn('glVertexArrayBindVertexBufferEXT', 'viiipi', 'glVertexArrayVertexBuffer(a, b, c, d, e);')
fn('glVertexArrayVertexAttribBindingEXT', 'viii', 'glVertexArrayAttribBinding(a, b, c);')
fn('glVertexArrayVertexAttribFormatEXT', 'viiiizi', 'glVertexArrayAttribFormat(a, b, c, d, e, f);')
fn('glVertexArrayVertexAttribIFormatEXT', 'viiiii', 'glVertexArrayAttribIFormat(a, b, c, d, e);')
fn('glVertexArrayVertexAttribLFormatEXT', 'viiiii', 'glVertexArrayAttribLFormat(a, b, c, d, e);')
fn('glVertexArrayVertexBindingDivisorEXT', 'viii', 'glVertexArrayBindingDivisor(a, b, c);')

# Hints, which GL allows to be ignored, and color index state, which RGBA contexts never read.
for name, sig in (('glReleaseShaderCompiler', 'v'), ('glInvalidateTexImage', 'vii'), ('glInvalidateTexSubImage', 'viiiiiiii'), ('glInvalidateBufferData', 'vi'),
                  ('glInvalidateBufferSubData', 'vipp'), ('glInvalidateFramebuffer', 'viip'), ('glInvalidateSubFramebuffer', 'viipiiii'),
                  ('glPrioritizeTextures', 'vipp'), ('glInvalidateNamedFramebufferData', 'viip'),
                  ('glInvalidateNamedFramebufferSubData', 'viipiiii'), ('glClearIndex', 'vf'), ('glIndexMask', 'vi')
                  ):
    fn(name, sig, '')
fn('glShaderBinary', 'vipipi', 'gl().shaderBinary(a, b, d, e);')
fn('glSampleMaski', 'vii', 'gl().sampleMaski(a, b);')
fn('glMinSampleShading', 'vf', 'gl().minSampleShading(a);')
fn('glClampColor', 'vii', 'gl().clampColor(a, b);')
fn('glTextureBarrier', 'v', 'gl().textureBarrier();')
fn('glClipControl', 'vii', 'gl().clipControl(a, b);')
fn('glBeginConditionalRender', 'vii', 'gl().beginConditionalRender(a, b);')
fn('glEndConditionalRender', 'v', 'gl().endConditionalRender();')
fn('glPatchParameteri', 'vii', 'if (a == GL40.GL_PATCH_VERTICES) gl().patchVertices(b); else GlFunctions.reportIgnoredCall();')
fn('glShaderStorageBlockBinding', 'viii', 'gl().shaderStorageBlockBinding(a, b, c);')
fn('glBindFragDataLocationIndexed', 'viiip', 'rb().bindFragDataLocation(a, b, str(d)); ProgramInfo.bindFragDataIndex(a, str(d), c);')
fn('glMemoryBarrierByRegion', 'vi', 'rb().memoryBarrier(a);')
fn('glGetGraphicsResetStatus', 'i', 'return 0;')
fn('glAreTexturesResident', 'zipp', 'for (int i = 0; i < a; i++) memPutByte(c + i, (byte) 1); return true;')
fn('glGetShaderPrecisionFormat', 'viipp', 'memPutInt(c, 127); memPutInt(c + 4L, 127); memPutInt(d, 23);')

owners = {}
for m in re.finditer(r'\{"([\w/]+)",\s*((?:"\w+",?\s*)+)\}', open(OWNERS).read()):
    for n in re.findall(r'"(\w+)"', m.group(2)):
        owners.setdefault(n, m.group(1).rsplit('/', 1)[1])


gen_entry_points_ext.define(fn, entries)


def resolve(body):
    return re.sub(r'\$(\w+)', lambda m: owners.get(m.group(1), 'GLStateManager') + '.' + m.group(1), body)


out = ['''package com.gtnewhorizons.angelica.glemu.compat;

// Generated by glemu/scripts/gen_entry_points.py; do not edit by hand.
import com.gtnewhorizons.angelica.glemu.shader.GlslIncludes;
''']
resolved = ''.join(resolve(body) for _, body in entries.values())
imports = sorted(c for c in set(owners.values()) | {'GLStateManager'} if re.search(rf'\b{c}\b', resolved))
for c in imports:
    out.append(f'import com.gtnewhorizons.angelica.glsm.{c};\n')
out.append('''
import com.gtnewhorizons.angelica.glsm.recording.ImmediateModeRecorder;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL43;
import org.lwjgl.system.Pointer;

import static com.gtnewhorizons.angelica.glemu.compat.GlEntryHelpers.*;
import static org.lwjgl.system.MemoryUtil.*;

/** Native GL functions no GLSM method matches exactly, each with the argument types of its C signature; pointers are longs. */
public final class GlEntryPoints {

    private GlEntryPoints() {}
''')
# A function reached only through here is checked here; the first arguments are passed to the method of its name in GlValidation.
checks = dict(re.findall(r'public boolean (gl\w+)\(([^)]*)\)', open('glemu/src/main/java/com/gtnewhorizons/angelica/glemu/gl/GlValidation.java').read()))
REFUSED = {'v': '', 'i': ' 0', 'p': ' 0L', 'f': ' 0f', 'd': ' 0d', 'z': ' false', 's': ' 0', 'y': ' 0'}
for name in sorted(entries):
    sig, body = entries[name]
    params = ', '.join(f'{J[c]} {ARGS_ALL}' for c, ARGS_ALL in zip(sig[1:], 'abcdefghijklmnopqrstuvwxyz'))
    out.append(f'\n    public static {J[sig[0]]} {name}({params}) {{\n')
    check = checks.pop(name, None)
    if check is not None:
        count = len([p for p in check.split(',') if p.strip()])
        args = ', '.join('abcdefghijklmnopqrstuvwxyz'[:count])
        out.append(f'        if (!gl().validation().{name}({args})) return{REFUSED[sig[0]]};\n')
    if body:
        out.append(f'        {resolve(body)}\n')
    out.append('    }\n')
out.append('}\n')
if checks:
    print('GlValidation checks no entry point has: ' + ', '.join(sorted(checks)), file=sys.stderr)
    sys.exit(1)
open(OUT, 'w').write(''.join(out))
print(f'wrote {len(entries)} entry points')
