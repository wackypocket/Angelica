package com.gtnewhorizons.angelica.d3d12;

import com.gtnewhorizons.angelica.config.SystemProperties;
import com.gtnewhorizons.angelica.glemu.NativeBackendBase;
import com.gtnewhorizons.angelica.d3d12.device.D3d12Drawable;
import com.gtnewhorizons.angelica.d3d12.gl.D3d12GL;

public abstract class D3d12BackendBase extends NativeBackendBase<D3d12GL> {
    @Override protected D3d12GL createGl() {
        return D3d12Runtime.gl();
    }

    @Override public boolean isD3d12() {
        return true;
    }

    @Override public void finishPendingShaderCompiles() {
        if (gl != null) gl.programs.finishPending();
    }

    @Override protected String apiName() {
        return "Direct3D 12";
    }

    @Override protected boolean statsEnabled() {
        return SystemProperties.D3D12_STATS;
    }

    @Override protected boolean gateEngaged() {
        return D3d12Gate.isRequested() && D3d12Gate.isEngaged();
    }

    @Override protected void destroyDevice() {
        if (D3d12Runtime.gpu() != null) D3d12Runtime.gpu().destroy();
    }

    @Override protected boolean ownsDrawable(Object drawable) {
        if (drawable == null) return false;
        if (drawable instanceof D3d12Drawable) return true;
        return D3d12DisplayBridge.isD3d12SharedDrawable(drawable);
    }

    public D3d12GL d3d12() {
        return gl;
    }
}
