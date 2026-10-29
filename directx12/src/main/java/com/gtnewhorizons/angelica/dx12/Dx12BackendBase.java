package com.gtnewhorizons.angelica.dx12;

import com.gtnewhorizons.angelica.config.SystemProperties;
import com.gtnewhorizons.angelica.glemu.NativeBackendBase;
import com.gtnewhorizons.angelica.dx12.device.Dx12Drawable;
import com.gtnewhorizons.angelica.dx12.gl.Dx12GL;
import com.gtnewhorizons.angelica.dx12.shader.HlslCompiler;

public abstract class Dx12BackendBase extends NativeBackendBase<Dx12GL> {
    @Override protected Dx12GL createGl() {
        return Dx12Runtime.gl();
    }

    @Override public boolean isDx12() {
        return true;
    }

    @Override public String shaderPackBlocker() {
        return HlslCompiler.shaderPackBlocker();
    }

    @Override protected String apiName() {
        return "Direct3D 12";
    }

    @Override protected boolean statsEnabled() {
        return SystemProperties.DX12_STATS;
    }

    @Override protected boolean gateEngaged() {
        return Dx12Gate.isRequested() && Dx12Gate.isEngaged();
    }

    @Override protected void destroyDevice() {
        if (Dx12Runtime.gpu() != null) Dx12Runtime.gpu().destroy();
    }

    @Override protected boolean ownsDrawable(Object drawable) {
        if (drawable == null) return false;
        if (drawable instanceof Dx12Drawable) return true;
        return Dx12DisplayBridge.isDx12SharedDrawable(drawable);
    }

    public Dx12GL dx12() {
        return gl;
    }
}
