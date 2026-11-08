package com.gtnewhorizons.angelica.glemu.shader;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ImageAccessTest {

    private static boolean both(String body) {
        return ProgramsBase.readAndWritten(body, 0, "img");
    }

    @Test
    void oneWayIsNotBoth() {
        assertFalse(both("imageLoad(img, p);"));
        assertFalse(both("imageStore(img, p, v);"));
        assertFalse(both("imageLoad(img[i], p); imageSize(img[i]);"));
        assertFalse(both("imageSize(img);"));
    }

    @Test
    void loadAndStoreAreBoth() {
        assertTrue(both("v = imageLoad(img, p); imageStore(img, p, -v);"));
    }

    @Test
    void atomicsAndOtherUsesAreBoth() {
        assertTrue(both("imageAtomicAdd(img, p, 1);"));
        assertTrue(both("helper(img);"));
        assertTrue(both("helper(p, img);"));
    }

    @Test
    void otherImagesDoNotCount() {
        assertFalse(both("imageLoad(img, p); imageStore(img2, p, v);"));
    }
}
