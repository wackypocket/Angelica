package com.gtnewhorizons.angelica.glemu.shader;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SampleRateLoweringTest {
    @Test
    void inputsBecomeSampled() {
        assertTrue(SampleRateLowering.lower("#version 460\nin vec4 a;\nout vec4 c;\nvoid main() {}\n").contains("sample in vec4 a"));
    }

    @Test
    void interpolationAlreadyAskedForStays() {
        final String flat = "#version 460\nflat in int a;\nvoid main() {}\n";
        assertEquals(flat, SampleRateLowering.lower(flat));
    }

    @Test
    void blockMembersBecomeSampled() {
        assertTrue(SampleRateLowering.lower("#version 460\nin Block { vec4 a; } b;\nvoid main() {}\n").contains("sample  vec4 a") || SampleRateLowering.lower("#version 460\nin Block { vec4 a; } b;\nvoid main() {}\n").contains("sample vec4 a"));
    }

    @Test
    void parametersAreLeftAlone() {
        final String source = "#version 460\nvoid f(in vec4 a) {}\nvoid main() {}\n";
        assertEquals(source, SampleRateLowering.lower(source));
    }
}
