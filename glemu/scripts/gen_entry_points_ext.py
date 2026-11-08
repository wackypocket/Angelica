"""ARB, EXT and KHR functions for gen_entry_points.py, for a mod that calls one whether or not GlBase advertises it. Vendor extensions and
anything else missing from here reach the shared no-op, which logs its caller: an empty body would only hide the call."""

LETTERS = 'abcdefghijklmnopqrstuvwxyz'


def names(sig, start=0):
    return LETTERS[start:len(sig) - 1]


def define(fn, entries):
    def call(target):
        return target if target in entries else '$' + target

    def alias(name, sig, target, args=None):
        """args is the target's arguments as letters; by default all of them in order."""
        given = ', '.join(args if args is not None else names(sig))
        fn(name, sig, f'{"" if sig[0] == "v" else "return "}{call(target)}({given});')

    def wrapped(name, sig, enter, leave, target, skip):
        fn(name, sig, f'{enter} {call(target)}({", ".join(names(sig, skip))}); {leave}')

    def on_unit(name, sig, target):
        wrapped(name, sig, 'final int was = useUnit(a);', 'useUnit(was);', target, 1)

    def on_texture(name, sig, target):
        wrapped(name, sig, 'final int was = useTexture(b, a);', 'useTexture(b, was);', target, 1)

    def on_matrix(name, sig, target):
        wrapped(name, sig, 'final int was = useMatrix(a);', 'useMatrix(was);', target, 1)

    def on_vao(name, sig, target):
        wrapped(name, sig, 'final int was = bindVertexArray(a);', 'bindVertexArray(was);', target, 1)

    # EXT_direct_state_access names a texture and also its target, where core names only the texture.
    for name, sig, target in (
            ('glCompressedTextureSubImage1DEXT', 'viiiiiiip', 'glCompressedTextureSubImage1D'), ('glCompressedTextureSubImage2DEXT', 'viiiiiiiiip', 'glCompressedTextureSubImage2D'),
            ('glCompressedTextureSubImage3DEXT', 'viiiiiiiiiiip', 'glCompressedTextureSubImage3D'), ('glCopyTextureSubImage1DEXT', 'viiiiiii', 'glCopyTextureSubImage1D'),
            ('glCopyTextureSubImage2DEXT', 'viiiiiiiii', 'glCopyTextureSubImage2D'), ('glCopyTextureSubImage3DEXT', 'viiiiiiiiii', 'glCopyTextureSubImage3D'),
            ('glGenerateTextureMipmapEXT', 'vii', 'glGenerateTextureMipmap'), ('glGetTextureLevelParameterfvEXT', 'viiiip', 'glGetTextureLevelParameterfv'),
            ('glGetTextureLevelParameterivEXT', 'viiiip', 'glGetTextureLevelParameteriv'), ('glGetTextureParameterIivEXT', 'viiip', 'glGetTextureParameterIiv'),
            ('glGetTextureParameterIuivEXT', 'viiip', 'glGetTextureParameterIuiv'), ('glGetTextureParameterfvEXT', 'viiip', 'glGetTextureParameterfv'),
            ('glGetTextureParameterivEXT', 'viiip', 'glGetTextureParameteriv'), ('glTextureBufferEXT', 'viiii', 'glTextureBuffer'),
            ('glTextureBufferRangeEXT', 'viiiipp', 'glTextureBufferRange'), ('glTextureParameterIivEXT', 'viiip', 'glTextureParameterIiv'),
            ('glTextureParameterIuivEXT', 'viiip', 'glTextureParameterIuiv'), ('glTextureParameterfEXT', 'viiif', 'glTextureParameterf'),
            ('glTextureParameterfvEXT', 'viiip', 'glTextureParameterfv'), ('glTextureParameteriEXT', 'viiii', 'glTextureParameteri'),
            ('glTextureParameterivEXT', 'viiip', 'glTextureParameteriv'), ('glTextureStorage1DEXT', 'viiiii', 'glTextureStorage1D'),
            ('glTextureStorage2DEXT', 'viiiiii', 'glTextureStorage2D'), ('glTextureStorage2DMultisampleEXT', 'viiiiiiz', 'glTextureStorage2DMultisample'),
            ('glTextureStorage3DEXT', 'viiiiiii', 'glTextureStorage3D'), ('glTextureStorage3DMultisampleEXT', 'viiiiiiiz', 'glTextureStorage3DMultisample'),
            ('glTextureSubImage1DEXT', 'viiiiiiip', 'glTextureSubImage1D'), ('glTextureSubImage2DEXT', 'viiiiiiiiip', 'glTextureSubImage2D'),
            ('glTextureSubImage3DEXT', 'viiiiiiiiiiip', 'glTextureSubImage3D')):
        alias(name, sig, target, 'a' + names(sig, 2))

    for name, sig, target in (
            ('glTextureImage1DEXT', 'viiiiiiiip', 'glTexImage1D'), ('glTextureImage2DEXT', 'viiiiiiiiip', 'glTexImage2D'), ('glTextureImage3DEXT', 'viiiiiiiiiip', 'glTexImage3D'),
            ('glCopyTextureImage1DEXT', 'viiiiiiii', 'glCopyTexImage1D'), ('glCopyTextureImage2DEXT', 'viiiiiiiii', 'glCopyTexImage2D'),
            ('glCompressedTextureImage1DEXT', 'viiiiiiip', 'glCompressedTexImage1D'), ('glCompressedTextureImage2DEXT', 'viiiiiiiip', 'glCompressedTexImage2D'),
            ('glCompressedTextureImage3DEXT', 'viiiiiiiiip', 'glCompressedTexImage3D')):
        on_texture(name, sig, target)

    for name, sig, target in (
            ('glBindMultiTextureEXT', 'viii', 'glBindTexture'), ('glMultiTexImage1DEXT', 'viiiiiiiip', 'glTexImage1D'), ('glMultiTexImage2DEXT', 'viiiiiiiiip', 'glTexImage2D'),
            ('glMultiTexImage3DEXT', 'viiiiiiiiiip', 'glTexImage3D'), ('glMultiTexSubImage1DEXT', 'viiiiiiip', 'glTexSubImage1D'), ('glMultiTexSubImage2DEXT', 'viiiiiiiiip', 'glTexSubImage2D'),
            ('glMultiTexSubImage3DEXT', 'viiiiiiiiiiip', 'glTexSubImage3D'), ('glCopyMultiTexImage1DEXT', 'viiiiiiii', 'glCopyTexImage1D'), ('glCopyMultiTexImage2DEXT', 'viiiiiiiii', 'glCopyTexImage2D'),
            ('glCopyMultiTexSubImage1DEXT', 'viiiiiii', 'glCopyTexSubImage1D'), ('glCopyMultiTexSubImage2DEXT', 'viiiiiiiii', 'glCopyTexSubImage2D'), ('glCopyMultiTexSubImage3DEXT', 'viiiiiiiiii', 'glCopyTexSubImage3D'),
            ('glCompressedMultiTexImage1DEXT', 'viiiiiiip', 'glCompressedTexImage1D'), ('glCompressedMultiTexImage2DEXT', 'viiiiiiiip', 'glCompressedTexImage2D'), ('glCompressedMultiTexImage3DEXT', 'viiiiiiiiip', 'glCompressedTexImage3D'),
            ('glCompressedMultiTexSubImage1DEXT', 'viiiiiiip', 'glCompressedTexSubImage1D'), ('glCompressedMultiTexSubImage2DEXT', 'viiiiiiiiip', 'glCompressedTexSubImage2D'), ('glCompressedMultiTexSubImage3DEXT', 'viiiiiiiiiiip', 'glCompressedTexSubImage3D'),
            ('glGenerateMultiTexMipmapEXT', 'vii', 'glGenerateMipmap'), ('glGetCompressedMultiTexImageEXT', 'viiip', 'glGetCompressedTexImage'),
            ('glGetMultiTexEnvfvEXT', 'viiip', 'glGetTexEnvfv'), ('glGetMultiTexEnvivEXT', 'viiip', 'glGetTexEnviv'), ('glGetMultiTexGendvEXT', 'viiip', 'glGetTexGendv'),
            ('glGetMultiTexGenfvEXT', 'viiip', 'glGetTexGenfv'), ('glGetMultiTexGenivEXT', 'viiip', 'glGetTexGeniv'), ('glGetMultiTexLevelParameterfvEXT', 'viiiip', 'glGetTexLevelParameterfv'),
            ('glGetMultiTexLevelParameterivEXT', 'viiiip', 'glGetTexLevelParameteriv'), ('glGetMultiTexParameterIivEXT', 'viiip', 'glGetTexParameterIiv'), ('glGetMultiTexParameterIuivEXT', 'viiip', 'glGetTexParameterIuiv'),
            ('glGetMultiTexParameterfvEXT', 'viiip', 'glGetTexParameterfv'), ('glGetMultiTexParameterivEXT', 'viiip', 'glGetTexParameteriv'), ('glMultiTexBufferEXT', 'viiii', 'glTexBuffer'),
            ('glMultiTexEnvfEXT', 'viiif', 'glTexEnvf'), ('glMultiTexEnvfvEXT', 'viiip', 'glTexEnvfv'), ('glMultiTexEnviEXT', 'viiii', 'glTexEnvi'), ('glMultiTexEnvivEXT', 'viiip', 'glTexEnviv'),
            ('glMultiTexGendEXT', 'viiid', 'glTexGend'), ('glMultiTexGendvEXT', 'viiip', 'glTexGendv'), ('glMultiTexGenfEXT', 'viiif', 'glTexGenf'), ('glMultiTexGenfvEXT', 'viiip', 'glTexGenfv'),
            ('glMultiTexGeniEXT', 'viiii', 'glTexGeni'), ('glMultiTexGenivEXT', 'viiip', 'glTexGeniv'), ('glMultiTexParameterIivEXT', 'viiip', 'glTexParameterIiv'), ('glMultiTexParameterIuivEXT', 'viiip', 'glTexParameterIuiv'),
            ('glMultiTexParameterfEXT', 'viiif', 'glTexParameterf'), ('glMultiTexParameterfvEXT', 'viiip', 'glTexParameterfv'), ('glMultiTexParameteriEXT', 'viiii', 'glTexParameteri'), ('glMultiTexParameterivEXT', 'viiip', 'glTexParameteriv')):
        on_unit(name, sig, target)
    fn('glGetMultiTexImageEXT', 'viiiiip', 'final int was = useUnit(a); rb().getTexImage(b, c, d, e, pix(f)); useUnit(was);')

    for name, sig, target in (
            ('glMatrixFrustumEXT', 'vidddddd', 'glFrustum'), ('glMatrixOrthoEXT', 'vidddddd', 'glOrtho'), ('glMatrixLoadIdentityEXT', 'vi', 'glLoadIdentity'),
            ('glMatrixLoadfEXT', 'vip', 'glLoadMatrixf'), ('glMatrixLoaddEXT', 'vip', 'glLoadMatrixd'), ('glMatrixMultfEXT', 'vip', 'glMultMatrixf'), ('glMatrixMultdEXT', 'vip', 'glMultMatrixd'),
            ('glMatrixLoadTransposefEXT', 'vip', 'glLoadTransposeMatrixf'), ('glMatrixLoadTransposedEXT', 'vip', 'glLoadTransposeMatrixd'), ('glMatrixMultTransposefEXT', 'vip', 'glMultTransposeMatrixf'),
            ('glMatrixMultTransposedEXT', 'vip', 'glMultTransposeMatrixd'), ('glMatrixPopEXT', 'vi', 'glPopMatrix'), ('glMatrixPushEXT', 'vi', 'glPushMatrix'),
            ('glMatrixRotatefEXT', 'viffff', 'glRotatef'), ('glMatrixRotatedEXT', 'vidddd', 'glRotated'), ('glMatrixScalefEXT', 'vifff', 'glScalef'), ('glMatrixScaledEXT', 'viddd', 'glScaled'),
            ('glMatrixTranslatefEXT', 'vifff', 'glTranslatef'), ('glMatrixTranslatedEXT', 'viddd', 'glTranslated')):
        on_matrix(name, sig, target)
    for name, sig, target, args in (
            ('glVertexArrayVertexOffsetEXT', 'viiiiip', 'glVertexPointer', 'cdef'), ('glVertexArrayColorOffsetEXT', 'viiiiip', 'glColorPointer', 'cdef'),
            ('glVertexArrayTexCoordOffsetEXT', 'viiiiip', 'glTexCoordPointer', 'cdef'), ('glVertexArrayNormalOffsetEXT', 'viiiip', 'glNormalPointer', 'cde'),
            ('glVertexArrayEdgeFlagOffsetEXT', 'viiip', 'glEdgeFlagPointer', 'cd'), ('glVertexArrayFogCoordOffsetEXT', 'viiiip', 'glFogCoordPointer', 'cde'),
            ('glVertexArrayIndexOffsetEXT', 'viiiip', 'glIndexPointer', 'cde'), ('glVertexArraySecondaryColorOffsetEXT', 'viiiiip', 'glSecondaryColorPointer', 'cdef'),
            ('glVertexArrayVertexAttribOffsetEXT', 'viiiiizip', 'glVertexAttribPointer', 'cdefgh'), ('glVertexArrayVertexAttribIOffsetEXT', 'viiiiiip', 'glVertexAttribIPointer', 'cdefg'),
            ('glVertexArrayVertexAttribLOffsetEXT', 'viiiiiip', 'glVertexAttribLPointer', 'cdefg')):
        fn(name, sig, f'final int vao = bindVertexArray(a); final int vbo = useArrayBuffer(b); {call(target)}({", ".join(args)}); useArrayBuffer(vbo); bindVertexArray(vao);')
    fn('glVertexArrayMultiTexCoordOffsetEXT', 'viiiiiip', 'final int vao = bindVertexArray(a); final int vbo = useArrayBuffer(b); final int unit = useClientUnit(c); glTexCoordPointer(d, e, f, g); useClientUnit(unit); useArrayBuffer(vbo); bindVertexArray(vao);')
    fn('glMultiTexCoordPointerEXT', 'viiiip', 'final int was = useClientUnit(a); glTexCoordPointer(b, c, d, e); useClientUnit(was);')
    on_vao('glEnableVertexArrayEXT', 'vii', 'glEnableClientState')
    on_vao('glDisableVertexArrayEXT', 'vii', 'glDisableClientState')
    on_vao('glGetVertexArrayIntegervEXT', 'viip', 'glGetIntegerv')
    on_vao('glGetVertexArrayPointervEXT', 'viip', 'glGetPointerv')
    on_vao('glGetVertexArrayIntegeri_vEXT', 'viiip', 'glGetVertexAttribiv')
    on_vao('glGetVertexArrayPointeri_vEXT', 'viiip', 'glGetVertexAttribPointerv')
    for name in ('glEnableClientStateIndexedEXT', 'glEnableClientStateiEXT'):
        fn(name, 'vii', 'final int was = useClientUnit(0x84C0 + b); $glEnableClientState(a); useClientUnit(was);')
    for name in ('glDisableClientStateIndexedEXT', 'glDisableClientStateiEXT'):
        fn(name, 'vii', 'final int was = useClientUnit(0x84C0 + b); $glDisableClientState(a); useClientUnit(was);')
    for name in ('glGetPointerIndexedvEXT', 'glGetPointeri_vEXT'):
        fn(name, 'viip', 'final int was = useClientUnit(0x84C0 + b); glGetPointerv(a, c); useClientUnit(was);')
    alias('glGetFloatIndexedvEXT', 'viip', 'glGetFloati_v')
    alias('glGetDoubleIndexedvEXT', 'viip', 'glGetDoublei_v')
    fn('glClientAttribDefaultEXT', 'vi', 'GlExtensions.clientAttribDefault(a);')
    fn('glPushClientAttribDefaultEXT', 'vi', '$glPushClientAttrib(a); GlExtensions.clientAttribDefault(a);')
    alias('glNamedCopyBufferSubDataEXT', 'viippp', 'glCopyNamedBufferSubData')
    alias('glFramebufferDrawBufferEXT', 'vii', 'glNamedFramebufferDrawBuffer')
    alias('glFramebufferDrawBuffersEXT', 'viip', 'glNamedFramebufferDrawBuffers')
    alias('glFramebufferReadBufferEXT', 'vii', 'glNamedFramebufferReadBuffer')
    for name, sig, call_args in (('glNamedFramebufferTexture1DEXT', 'viiiii', 'rb().framebufferTexture2D(0x8CA9, b, c, d, e);'),
                                 ('glNamedFramebufferTexture2DEXT', 'viiiii', 'rb().framebufferTexture2D(0x8CA9, b, c, d, e);'),
                                 ('glNamedFramebufferTexture3DEXT', 'viiiiii', '$glFramebufferTextureLayer(0x8CA9, b, d, e, f);'),
                                 ('glNamedFramebufferTextureFaceEXT', 'viiiii', 'rb().framebufferTexture2D(0x8CA9, b, e, c, d);')):
        fn(name, sig, f'final int was = bindFramebuffer(a); {call_args} bindFramebuffer(was);')
    alias('glNamedRenderbufferStorageMultisampleCoverageEXT', 'viiiiii', 'glNamedRenderbufferStorageMultisample', 'acdef')

    # Plain renames.
    for name, sig, target in (
            ('glColorMaskIndexedEXT', 'vizzzz', 'glColorMaski'), ('glEnableIndexedEXT', 'vii', 'glEnablei'), ('glDisableIndexedEXT', 'vii', 'glDisablei'), ('glIsEnabledIndexedEXT', 'zii', 'glIsEnabledi'),
            ('glGetBooleanIndexedvEXT', 'viip', 'glGetBooleani_v'), ('glGetIntegerIndexedvEXT', 'viip', 'glGetIntegeri_v')):
        alias(name, sig, target)
    fn('glBindBufferOffsetEXT', 'viiip', 'final var buffer = c == 0 ? null : gl().buffers().get(c); rb().bindBufferRange(a, b, c, d, buffer == null ? 0L : buffer.size - d);')
    fn('glClearColorIiEXT', 'viiii', 'rb().clearColor(a, b, c, d);')
    fn('glClearColorIuiEXT', 'viiii', 'rb().clearColor(a & 0xFFFFFFFFL, b & 0xFFFFFFFFL, c & 0xFFFFFFFFL, d & 0xFFFFFFFFL);')
    for name in ('glFramebufferTextureFaceARB', 'glFramebufferTextureFaceEXT'):
        fn(name, 'viiiii', 'rb().framebufferTexture2D(a, b, e, c, d);')

    # The older spelling of separate programs, through one pipeline of their own.
    fn('glCreateShaderProgramEXT', 'iip', 'return GlPipelines.createShaderProgram(a, str(b));')
    fn('glUseShaderProgramEXT', 'vii', 'GlPipelines.useShaderProgram(a, b);')
    fn('glActiveProgramEXT', 'vi', 'GlPipelines.activeProgram(a);')

    # Markers and labels, through core's debug groups and object labels.
    fn('glPushGroupMarkerEXT', 'vip', 'GlExtensions.pushMarker(a, b);')
    fn('glPopGroupMarkerEXT', 'v', '$glPopDebugGroup();')
    fn('glInsertEventMarkerEXT', 'vip', 'GlExtensions.insertMarker(a, b);')
    fn('glLabelObjectEXT', 'viiip', 'rb().objectLabel(GlExtensions.labelIdentifier(a), b, text(d, c));')

    # Calls GL lets an implementation treat as hints, or that have nothing to act on here.
    for name, sig in (
            ('glLockArraysEXT', 'vii'), ('glUnlockArraysEXT', 'v'), ('glPrimitiveBoundingBoxARB', 'vffffffff'),
            ('glBlendBarrierKHR', 'v'), ('glFramebufferFetchBarrierEXT', 'v'), ('glEvaluateDepthValuesARB', 'v'),
            # Storage here is never sparse, so every page is committed already.
            ('glBufferPageCommitmentARB', 'vippz'), ('glNamedBufferPageCommitmentARB', 'vippz'), ('glNamedBufferPageCommitmentEXT', 'vippz'),
            ('glTexPageCommitmentARB', 'viiiiiiiiz'), ('glTexturePageCommitmentEXT', 'viiiiiiiiz')):
        fn(name, sig, '')

    # 64-bit integer uniforms.
    for n in (1, 2, 3, 4):
        for t in ('i64', 'ui64'):
            fn(f'glUniform{n}{t}ARB', 'vi' + 'p' * n, f'uniform64(0, a, {", ".join(LETTERS[1:1 + n])});')
            fn(f'glUniform{n}{t}vARB', 'viip', f'gl().uniform64(0, a, b, {n}, c);')
            fn(f'glProgramUniform{n}{t}ARB', 'vii' + 'p' * n, f'uniform64(a, b, {", ".join(LETTERS[2:2 + n])});')
            fn(f'glProgramUniform{n}{t}vARB', 'viiip', f'gl().uniform64(a, b, c, {n}, d);')
    for name in ('glGetUniformi64vARB', 'glGetUniformui64vARB'):
        fn(name, 'viip', 'gl().getUniform64(a, b, c);')
    for name in ('glGetnUniformi64vARB', 'glGetnUniformui64vARB'):
        fn(name, 'viiip', 'gl().getUniform64(a, b, d);')

    # ARB_shading_language_include.
    fn('glNamedStringARB', 'viipip', 'GlslIncludes.put(text(c, b), text(e, d));')
    fn('glDeleteNamedStringARB', 'vip', 'GlslIncludes.remove(text(b, a));')
    fn('glIsNamedStringARB', 'zip', 'return GlslIncludes.get(text(b, a)) != null;')
    fn('glGetNamedStringARB', 'vipipp', 'final String named = GlslIncludes.get(text(b, a)); putName(named == null ? "" : named, c, d, e);')
    fn('glGetNamedStringivARB', 'vipip', 'final String named = GlslIncludes.get(text(b, a)); memPutInt(d, named == null ? 0 : c == 0x8DEA ? 0x8DAE : named.length() + 1);')
    fn('glCompileShaderIncludeARB', 'viipp', '$glCompileShader(a);')

    # ARB_imaging, on the glDrawPixels path.
    fn('glColorTable', 'viiiiip', 'GlImaging.colorTable(a, b, c, d, e, f);')
    fn('glColorSubTable', 'viiiiip', 'GlImaging.colorSubTable(a, b, c, d, e, f);')
    fn('glCopyColorTable', 'viiiii', 'GlImaging.copyColorTable(a, b, c, d, e);')
    fn('glCopyColorSubTable', 'viiiii', 'GlImaging.copyColorSubTable(a, b, c, d, e);')
    fn('glGetColorTable', 'viiip', 'GlImaging.getColorTable(a, b, c, d);')
    fn('glGetnColorTable', 'viiiip', 'GlImaging.getColorTable(a, b, c, e);')
    fn('glConvolutionFilter1D', 'viiiiip', 'GlImaging.convolutionFilter(a, b, c, 1, d, e, f);')
    fn('glConvolutionFilter2D', 'viiiiiip', 'GlImaging.convolutionFilter(a, b, c, d, e, f, g);')
    fn('glSeparableFilter2D', 'viiiiiipp', 'GlImaging.separableFilter(a, b, c, d, e, f, g, h);')
    fn('glCopyConvolutionFilter1D', 'viiiii', 'GlImaging.copyConvolutionFilter(a, b, c, d, e, 1);')
    fn('glCopyConvolutionFilter2D', 'viiiiii', 'GlImaging.copyConvolutionFilter(a, b, c, d, e, f);')
    fn('glGetConvolutionFilter', 'viiip', 'GlImaging.getConvolutionFilter(a, b, c, d);')
    fn('glGetnConvolutionFilter', 'viiiip', 'GlImaging.getConvolutionFilter(a, b, c, e);')
    fn('glGetSeparableFilter', 'viiippp', 'GlImaging.getSeparableFilter(a, b, c, d, e);')
    fn('glGetnSeparableFilter', 'viiiipipp', 'GlImaging.getSeparableFilter(a, b, c, e, g);')
    fn('glConvolutionParameterf', 'viif', 'GlImaging.convolutionParameter(a, b, c);')
    fn('glConvolutionParameteri', 'viii', 'GlImaging.convolutionParameter(a, b, (float) c);')
    for suffix, floats in (('fv', 'true'), ('iv', 'false')):
        fn(f'glColorTableParameter{suffix}', 'viip', f'GlImaging.colorTableParameter(a, b, c, {floats});')
        fn(f'glGetColorTableParameter{suffix}', 'viip', f'GlImaging.getColorTableParameter(a, b, c, {floats});')
        fn(f'glConvolutionParameter{suffix}', 'viip', f'GlImaging.convolutionParameter(a, b, c, {floats});')
        fn(f'glGetConvolutionParameter{suffix}', 'viip', f'GlImaging.getConvolutionParameter(a, b, c, {floats});')
        fn(f'glGetHistogramParameter{suffix}', 'viip', f'GlImaging.getHistogramParameter(b, c, {floats});')
        fn(f'glGetMinmaxParameter{suffix}', 'viip', f'GlImaging.getMinmaxParameter(b, c, {floats});')
    fn('glHistogram', 'viiiz', 'GlImaging.histogram(b, c, d);')
    fn('glResetHistogram', 'vi', 'GlImaging.resetHistogram();')
    fn('glGetHistogram', 'viziip', 'GlImaging.getHistogram(b, c, d, e);')
    fn('glGetnHistogram', 'viziiip', 'GlImaging.getHistogram(b, c, d, f);')
    fn('glMinmax', 'viiz', 'GlImaging.minmax(b, c);')
    fn('glResetMinmax', 'vi', 'GlImaging.resetMinmax();')
    fn('glGetMinmax', 'viziip', 'GlImaging.getMinmax(b, c, d, e);')
    fn('glGetnMinmax', 'viziiip', 'GlImaging.getMinmax(b, c, d, f);')

    # Layered blits, and textures that are renderbuffers' storage.
    fn('glBlitFramebufferLayerEXT', 'viiiiiiiiiiii', 'gl().blitFramebufferLayer(a, b, c, d, e, f, g, h, i, j, k, l);')
    fn('glBlitFramebufferLayersEXT', 'viiiiiiiiii', 'gl().blitFramebufferLayers(a, b, c, d, e, f, g, h, i, j);')
    fn('glTextureRenderbufferEXT', 'viii', 'gl().textureFromRenderbuffer(a, c);')
    fn('glMultiTexRenderbufferEXT', 'viii', 'final int was = useUnit(a); gl().textureFromRenderbuffer(gl().st().boundTexture(b), c); useUnit(was);')

    fn('glActiveStencilFaceEXT', 'vi', 'GlExtensions.activeStencilFace(a);')
    fn('glUniformBufferEXT', 'viii', 'GlExtensions.uniformBuffer(a, b, c);')
    fn('glGetUniformBufferSizeEXT', 'iii', 'return GlExtensions.uniformBufferSize(a, b);')
    fn('glGetUniformOffsetEXT', 'pii', 'return 0L;')
    fn('glDispatchComputeGroupSizeARB', 'viiiiii', 'GlExtensions.dispatchGroupSize(a, b, c, d, e, f);')

    # One shading rate is a conforming set: requests are clamped to it, so attachments and combiners have nothing to change.
    fn('glShadingRateEXT', 'vi', '')
    fn('glShadingRateCombinerOpsEXT', 'vii', '')
    fn('glFramebufferShadingRateEXT', 'viiiiiii', '')
    fn('glGetFragmentShadingRatesEXT', 'viipp', 'if (c != 0L) memPutInt(c, 1); if (b > 0 && d != 0L) memPutInt(d, 0x96A6);')
