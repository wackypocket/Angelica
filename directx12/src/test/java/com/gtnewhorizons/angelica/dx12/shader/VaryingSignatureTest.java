package com.gtnewhorizons.angelica.dx12.shader;

import com.gtnewhorizons.angelica.glemu.gl.GpuCaps;
import com.gtnewhorizons.angelica.glemu.shader.ProgramLinkerBase;
import com.gtnewhorizons.angelica.glemu.shader.ShaderCompiler;
import com.gtnewhorizons.angelica.glemu.shader.Spirv;
import com.gtnewhorizons.angelica.glemu.shader.StageReflection;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL20;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VaryingSignatureTest {

    private static final String VS = """
        #version 330 core
        layout(location = 0) in vec3 a_Position;
        out vec4 v_Color;
        out float v_FogDepth;
        out vec2 v_TexCoord0;
        out vec3 v_Normal;
        out float v_Id;
        out vec4 v_Unused[2];
        void main() {
            gl_Position = vec4(a_Position, 1.0);
            v_Color = vec4(1.0);
            v_FogDepth = a_Position.z;
            v_TexCoord0 = a_Position.xy;
            v_Normal = a_Position;
            v_Id = 3.0;
            v_Color.x = a_Position.y;
            v_Unused[1] = vec4(a_Position, 0.0);
            v_Unused[0].y = 2.0;
        }
        """;

    private static final String FS = """
        #version 330 core
        in vec2 v_TexCoord0;
        in vec3 v_Normal;
        flat in float v_Id;
        in float v_FogDepth;
        out vec4 fragColor;
        void main() { fragColor = vec4(v_TexCoord0, gl_FragCoord.z, v_Id); }
        """;

    private static final Pattern STRUCT = Pattern.compile("struct (SPIRV_Cross_Input|SPIRV_Cross_Output)\\s*\\{([^}]*)\\}");

    private static Spirv spirv(String glsl, int type, int fixup) {
        final ShaderCompiler.Result r = ShaderCompiler.compile(glsl, type, "varying-test", fixup);
        assertNotNull(r.compiled(), r.log());
        return new Spirv(ByteBuffer.wrap(r.compiled().spirv()).order(ByteOrder.LITTLE_ENDIAN));
    }

    private static List<String> signature(String hlsl, String struct) {
        final Matcher m = STRUCT.matcher(hlsl);
        while (m.find()) {
            if (!m.group(1).equals(struct)) continue;
            final List<String> members = new ArrayList<>();
            for (String line : m.group(2).split(";")) {
                final String t = line.trim().replaceAll("\\s+", " ");
                if (!t.isEmpty()) members.add(t.replaceAll("\\s\\w+ :", " :"));
            }
            return members;
        }
        return List.of();
    }

    @Test
    void vertexOutputsMatchFragmentInputsRegisterForRegister() {
        final Spirv vs = spirv(VS, GL20.GL_VERTEX_SHADER, ShaderCompiler.DEPTH_AT_END_OF_MAIN);
        final Spirv fs = spirv(FS, GL20.GL_FRAGMENT_SHADER, ShaderCompiler.DEPTH_NONE);
        final StageReflection rv = new StageReflection(vs, Collections.emptySet()), rf = new StageReflection(fs, Collections.emptySet());
        new ProgramLinkerBase<Program>() {
            @Override protected GpuCaps gpuCaps() {
                return null;
            }

            void link() {
                matchInterface(rv, rf);
            }
        }.link();

        final String vsHlsl = HlslCompiler.toHlsl(StageInterface.alignVertexOutputs(rv, rf), GL20.GL_VERTEX_SHADER, List.of());
        final String fsHlsl = HlslCompiler.toHlsl(fs.words, GL20.GL_FRAGMENT_SHADER, List.of());

        final List<String> out = signature(vsHlsl, "SPIRV_Cross_Output"), in = signature(fsHlsl, "SPIRV_Cross_Input");
        assertEquals(in, out, "VS output and PS input signatures must be identical.\n" + vsHlsl + "\n" + fsHlsl);
        assertEquals(List.of("float : TEXCOORD1", "float2 : TEXCOORD2", "float3 : TEXCOORD3", "nointerpolation float : TEXCOORD4", "float4 : SV_Position"), out,
            "the output the fragment shader never reads must be dropped and the flat one must stay flat:\n" + vsHlsl);
        assertTrue(vsHlsl.contains("v_Color.x = a_Position.y") && vsHlsl.contains("v_Unused[0].y = 2.0f"), "masked outputs are still computed, just not exported:\n" + vsHlsl);
    }
}
