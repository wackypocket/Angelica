package com.gtnewhorizons.angelica.metal;

import com.gtnewhorizons.angelica.metal.device.ObjC;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("metal-device")
class ObjCBridgeTest {

    @Test
    void stringsLargerThanTheMemoryStackRoundTrip() {
        // Shader library sources go through nsString and are far larger than LWJGL's 64 KiB memory stack.
        final StringBuilder sb = new StringBuilder(300_000);
        for (int i = 0; i < 10_000; i++) sb.append("line ").append(i).append(" of a Metal shading language source ü\n");
        final String source = sb.toString();
        final long ns = ObjC.nsString(source);
        try {
            assertEquals(source, ObjC.string(ns));
        } finally {
            ObjC.release(ns);
        }
    }

    @Test
    void selectorsClassesAndBooleansCrossTheBridge() {
        final long ns = ObjC.nsString("bridge");
        try {
            assertEquals(6L, ObjC.send(ns, ObjC.sel("length")));
            assertTrue(ObjC.responds(ns, "length"));
            assertFalse(ObjC.responds(ns, "noSuchSelectorOnNSString:"));
        } finally {
            ObjC.release(ns);
        }
    }

    @Test
    void doubleReturnsComeBackThroughLibffi() {
        final long text = ObjC.nsString("2.5");
        try {
            assertEquals(2.5, ObjC.sendD(text, ObjC.sel("doubleValue")), 0.0);
        } finally {
            ObjC.release(text);
        }
    }
}
