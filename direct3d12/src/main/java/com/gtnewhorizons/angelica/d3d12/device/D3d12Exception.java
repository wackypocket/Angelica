package com.gtnewhorizons.angelica.d3d12.device;

public final class D3d12Exception extends RuntimeException {
    public final int hresult;

    public D3d12Exception(int hresult, String what) {
        super(what + " failed: 0x" + Integer.toHexString(hresult).toUpperCase());
        this.hresult = hresult;
    }
}
