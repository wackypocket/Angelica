#!/usr/bin/env python3
"""Writes the reasoned lines of conformance-known-differences.txt from the probes' latest answers, and lists what no reason covers.

usage: gen_conformance_known.py [write]     from the repository root, after glemu/scripts/conformance_run.py has run the driver and a backend

A difference only becomes known by matching a rule here, so a rule is a claim about why a driver and the specification disagree. Most rules
were written against Apple's 4.1 driver, and the Apple-only reasons apply only when it is the driver recorded: anything listed as open on
another driver is a finding to read, not a rule to add without reading it.
"""
import os, re,sys
if sys.argv[1:] not in ([], ['write']):
    print(__doc__)
    sys.exit(2)
def rd(p):
    d={}
    for l in open(p):
        if l.startswith('#'): continue
        f,k,a=l.rstrip('\n').split('\t',2); d[(f,k)]=a
    return d
o=rd('vulkan/build/conformance/guided-oracle.txt'); v=rd('vulkan/build/conformance/guided-backend.txt')
CORE='Core only: a compatibility profile allows this, and older mods rely on it'
LACK='Apple is GL 4.1 and lacks this, while we advertise 4.6'
PAGE='The reference page gives our answer; the driver here gives another'
LENIENT='Apple accepts this; the specification refuses it'
STRICT='Apple refuses this; the specification allows it'
VP='We report GL_MAX_VIEWPORTS as 1, under the 16 that GL 4.1 requires'
ST='Only the stream queries take an index above 0; the reference page refuses one for the others, which Apple accepts'
ORDER='Several things are wrong at once, and GL does not say which error comes first'
RULES=[
 (r'glFramebufferTexture$',r'texture=','The Khronos suite and the reference page say GL_INVALID_VALUE; Apple answers GL_INVALID_OPERATION'),
 (r'glProgramUniform',r'program=','The reference page and the Khronos suite say GL_INVALID_VALUE for a name that is no program; Apple answers GL_INVALID_OPERATION'),
 (r'glGetProgramPipelineInfoLog$',r'pipeline=','The Khronos suite says GL_INVALID_VALUE; Apple answers GL_INVALID_OPERATION'),
 (r'gl(Get)?UniformSubroutine',r'shadertype=GL_(TESS|GEOMETRY)','The specification says GL_INVALID_OPERATION with no program active for the stage; Apple answers GL_INVALID_VALUE'),
 (r'glReadPixels$',r'format=GL_DEPTH_STENCIL','The Khronos suite says GL_INVALID_ENUM for a type that is not packed depth and stencil; Apple answers GL_INVALID_OPERATION'),
 (r'gl(Sampler|Tex)Parameter[fi]$',r'pname=GL_TEXTURE_BORDER_COLOR','A border colour has no form taking one value, so the specification says GL_INVALID_ENUM; Apple accepts it'),
 (r'glShaderBinary$',r'count=-1|length=-1',LACK),
 (r'glBind(Buffer|BufferBase|BufferRange|Framebuffer|Renderbuffer|Texture)$',r'(buffer|framebuffer|renderbuffer|texture)=',CORE),
 (r'glBeginQuery(Indexed)?$|glQueryCounter$',r'id=',CORE),
 (r'glGetString$',r'name=GL_EXTENSIONS',CORE),
 (r'glLineWidth$',r'valid',CORE),
 (r'glGetPointerv$',r'',CORE),
 (r'gl(Copy)?TexImage[123]D$',r'border=1$',CORE),
 (r'glTexImage[123]D$',r'internalformat=1$',CORE),
 (r'glActiveShaderProgram$',r'program=',PAGE),
 (r'glBeginQueryIndexed$|glEndQueryIndexed$',r'index=',PAGE),
 (r'glBindTransformFeedback$',r'target=',PAGE),
 (r'glCompressedTexImage2D$',r'border=',PAGE),
 (r'glCompressedTexSubImage[23]D$',r'level=|target=GL_TEXTURE_1D_ARRAY|width=1$|height=1$',PAGE),
 (r'glSamplerParameter',r'sampler=',PAGE),
 (r'glTexImage[123]D$',r'internalformat=',PAGE),
 (r'glTexParameter[fi]$',r'pname=GL_TEXTURE_SWIZZLE_RGBA',PAGE),
 (r'glProgramParameteri$',r'value=',PAGE),
 (r'glGetActiveSubroutine',r'shadertype=',ORDER),
 (r'glCompressedTexImage2D$',r'level=-1',LENIENT),
 (r'glFramebufferTexture3D$',r'layer=-1',LENIENT),
 (r'glCreateShaderProgramv$|glShaderSource$',r'count=0$',STRICT),
 (r'glPixelStore[fi]$',r'param=-1',STRICT),
 (r'glGetActiveUniformsiv$',r'uniformCount=big',STRICT),
 (r'glMapBufferRange$',r'length=0$',LACK),
 (r'.*',r'format=GL_STENCIL_INDEX',LACK),
 (r'glTexParameter|glGetTexParameter',r'target=GL_TEXTURE_2D_MULTISAMPLE|pname=GL_TEXTURE_TARGET',LACK),
 (r'glGetVertexAttrib',r'pname=GL_VERTEX_ATTRIB_(ARRAY_LONG|BINDING|RELATIVE_OFFSET)',LACK),
 (r'glGetProgramiv$',r'pname=GL_(ACTIVE_ATOMIC|GEOMETRY_)',LACK),
 (r'glVertexAttrib(P3ui|Pointer)$',r'type=GL_UNSIGNED_INT_10F_11F_11F_REV',LACK),
 (r'gl(DepthRange|Scissor|Viewport)(Array|Indexed)',r'(first|index)=1$',VP),
 (r'glGet(Float|Double)i_v$',r'index=1$',VP),
 (r'glDrawTransformFeedbackStream$|glGetQueryIndexediv$',r'(stream|index)=1$',ST),
]
LWJGL='LWJGL refuses a negative size before the driver sees it, where GLSM goes on to GL'
RULES+=[
 (r'glGetProgrami$',r'pname=GL_(ACTIVE_ATOMIC|GEOMETRY_)',LACK),
 (r'gl(GetProgramInfoLog|GetShaderInfoLog|GetShaderSource)$',r'=-1',LWJGL),
 (r'glDrawBuffers$',r'valid|n=',STRICT),
 (r'glBindBuffer(Base|Range)$',r'target=GL_(ATOMIC_COUNTER|SHADER_STORAGE)',LACK),
 (r'glTexParameter|glGetTexParameter',r'pname=GL_DEPTH_STENCIL_TEXTURE_MODE',LACK),
 (r'glSpecializeShader$',r'',LACK),
 # Read on NVIDIA 580 and Mesa 26.1 llvmpipe, Linux.
 (r'glCompressedTexImage3D$',r'target=GL_PROXY_TEXTURE_3D','GL 4.6 refuses a format with no 3D form as an operation, as we do; NVIDIA accepts it for the proxy and Mesa calls it an enum'),
 (r'glGetProgramBinary$',r'bufSize=','No binary format is offered here, so there is no binary to hand back; a driver with one has room for it'),
 (r'glInvalidateTexSubImage$',r'depth=-1','GL refuses a negative depth; both drivers pass over the depth of a 2D texture'),
 (r'glStencilFunc(Separate)?$',r'ref=','GL clamps the reference to the stencil range, which we report; NVIDIA reports what was given'),
 (r'glViewport$',r'[xy]=','GL clamps the origin to GL_VIEWPORT_BOUNDS_RANGE, which we report; NVIDIA reports what was given'),
 (r'glTexSubImage[123]D$',r'level=1$','GL 4.6 refuses a level with no image as an operation, as we and Mesa do; NVIDIA calls it a value'),
 (r'glTexSubImage[123]D$',r'format=GL_DEPTH_COMPONENT','GL refuses depth pixels for a colour image, as we and Mesa do; NVIDIA accepts them'),
 (r'glTex(ture)?SubImage[123]D$',r'format=GL_STENCIL_INDEX','GL refuses stencil pixels for a colour image, as we and Mesa do; NVIDIA accepts them'),
 (r'glGetProgrami(v)?$',r'pname=GL_GEOMETRY_','GL 4.6 refuses these for a program with no geometry shader as an operation, as we do; NVIDIA answers and Mesa calls them an enum'),
 (r'gl(BlendColor|ClearColor)$',r'=(-1|0)$','A compatibility context clamps these to 0 and 1; a core one, as we report, keeps them, so a later 0 is a change for us and none for NVIDIA'),
 (r'glClearStencil$',r's=','GL masks the value to the stencil bits, which we report; NVIDIA reports what was given'),
 (r'glBegin$',r'valid','The probe reads state between glBegin and glEnd, where GL refuses queries, so the two sides read different nothing'),
 (r'glEnablei$',r'index=1$','NVIDIA reports GL_BLEND enabled once any buffer is; GL ties that query to buffer 0, as we do'),
 (r'glTexStorage3D$',r'target=GL_TEXTURE_CUBE_MAP_ARRAY$','A cube map array whose depth is not a multiple of 6 is a value error in the Khronos suite (tex3D_validation), as we give; NVIDIA calls it an operation'),
]
MESA='Mesa\'s llvmpipe gives our answer; the driver here gives another'
# Mesa's answers where it and we agree and NVIDIA does not, recorded on a machine with both: a claim that still holds while our answer is Mesa's.
MESA_ANSWERS={}
try:
    for l in open('glemu/scripts/conformance-mesa-answers.tsv'):
        if l.startswith('#') or not l.strip(): continue
        probe,call,key,answer=l.rstrip('\n').split('\t')
        MESA_ANSWERS[(probe,call,key)]=answer
except FileNotFoundError:
    pass
# Reasons about Apple's driver hold only where it is the one recorded: elsewhere what they cover is open.
APPLE=any(w in open('vulkan/build/conformance/guided-oracle.txt' if os.path.exists('vulkan/build/conformance/guided-oracle.txt') else __file__).readline() + open('vulkan/build/conformance/oracle.txt').readline() for w in ('Apple', 'Metal'))
APPLE_ONLY={LACK, CORE, LENIENT, STRICT}
lines=[];open_=[]
LACK_KEYS=re.compile(r'GL_(SHADER_STORAGE|COMPUTE|ATOMIC_COUNTER|DISPATCH_INDIRECT|DEBUG_|QUERY_RESULT_NO_WAIT|QUERY_TARGET|ANY_SAMPLES_PASSED_CONSERVATIVE|TEXTURE_BUFFER_OFFSET|TEXTURE_BUFFER_SIZE|TEXTURE_IMMUTABLE|IMAGE_FORMAT|DEPTH_STENCIL_TEXTURE_MODE|TEXTURE_VIEW|TEXTURE_2D_MULTISAMPLE_ARRAY|TEXTURE_CUBE_MAP_ARRAY_)')
for k in o:
    if k not in v or o[k]==v[k]: continue
    f,key=k
    why=MESA if MESA_ANSWERS.get(('guided',f,key))==v[k].split('|')[0] else None
    for a,b,r in RULES:
        if why is None and re.match(a,f) and re.search(b,key) and (APPLE or r not in APPLE_ONLY): why=r; break
    if why is None and APPLE and LACK_KEYS.search(key): why=LACK
    if why is None: open_.append((f,key,o[k],v[k]))
    else: lines.append(f'guided\t{f} {key}\t{why}')
print('known',len(lines),'open',len(open_))
for x in open_[:60]: print(' ',*x)
m=rd('vulkan/build/conformance/modpath-oracle.txt'); b=rd('vulkan/build/conformance/modpath-backend.txt')
mlines=[];mopen=[]
for k in m:
    if k not in b or m[k]==b[k]: continue
    f=k[0].split()[0]; key=k[1]; why=MESA if MESA_ANSWERS.get(('modpath',k[0],key))==b[k].split('|')[0] else None
    for a,c,r in RULES:
        if why is None and re.match(a,f) and re.search(c,key) and (APPLE or r not in APPLE_ONLY): why=r; break
    if why is None and APPLE and LACK_KEYS.search(key): why=LACK
    if why is None: mopen.append((k[0],key,m[k],b[k]))
    else: mlines.append(f'modpath\t{k[0]} {key}\t{why}')
print('modpath known',len(mlines),'open',len(mopen))
for x in mopen[:40]: print(' ',*x)
lines=lines+mlines
if sys.argv[1:]==['write']:
    P='glemu/scripts/conformance-known-differences.txt'
    keep=[l for l in open(P).read().split('\n') if l and not l.startswith(('guided\t','modpath\t'))]
    open(P,'w').write('\n'.join(keep+lines)+'\n')
