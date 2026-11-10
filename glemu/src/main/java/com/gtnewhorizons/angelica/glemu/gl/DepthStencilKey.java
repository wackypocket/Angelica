package com.gtnewhorizons.angelica.glemu.gl;

public record DepthStencilKey(int depthCompare, boolean depthWrite, boolean stencil, int front, int back, int masks) {}
