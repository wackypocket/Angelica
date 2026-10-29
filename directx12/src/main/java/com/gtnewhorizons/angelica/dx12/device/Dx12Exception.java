package com.gtnewhorizons.angelica.dx12.device;

public final class Dx12Exception extends RuntimeException {
    public final int hresult;

    public Dx12Exception(int hresult, String what) {
        super(what + " failed: 0x" + Integer.toHexString(hresult).toUpperCase());
        this.hresult = hresult;
    }
}
