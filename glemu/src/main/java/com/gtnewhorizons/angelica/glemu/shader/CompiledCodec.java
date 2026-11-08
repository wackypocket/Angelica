package com.gtnewhorizons.angelica.glemu.shader;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * A {@link ShaderCompiler.Compiled} as bytes for the shader disk cache. Sets and maps come back in the order they were
 * written, and mutable as freshly built ones are. Anything unreadable is a miss.
 */
final class CompiledCodec {

    private static final int VERSION = 1;

    private CompiledCodec() {}

    static byte[] encode(ShaderCompiler.Compiled c) {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream(c.spirv().length + 256);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(VERSION);
            out.writeInt(c.spirv().length);
            out.write(c.spirv());
            writeSet(out, c.boolUniforms());
            writeSet(out, c.explicitInputs());
            writeMap(out, c.bindings());
            writeMap(out, c.explicitVaryings());
            writeMap(out, c.explicitUniforms());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return bytes.toByteArray();
    }

    static ShaderCompiler.Compiled decode(byte[] data) {
        if (data == null) return null;
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            if (in.readInt() != VERSION) return null;
            final byte[] spirv = new byte[in.readInt()];
            in.readFully(spirv);
            final Set<String> bools = readSet(in), inputs = readSet(in);
            final Map<String, Integer> bindings = readMap(in), varyings = readMap(in), uniforms = readMap(in);
            if (in.available() != 0) return null;
            return new ShaderCompiler.Compiled(spirv, bools, inputs, bindings, varyings, uniforms);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static void writeSet(DataOutputStream out, Set<String> set) throws IOException {
        out.writeInt(set.size());
        for (String s : set) out.writeUTF(s);
    }

    private static void writeMap(DataOutputStream out, Map<String, Integer> map) throws IOException {
        out.writeInt(map.size());
        for (Map.Entry<String, Integer> e : map.entrySet()) {
            out.writeUTF(e.getKey());
            out.writeInt(e.getValue());
        }
    }

    private static Set<String> readSet(DataInputStream in) throws IOException {
        final int n = in.readInt();
        final Set<String> set = new LinkedHashSet<>(n * 2);
        for (int i = 0; i < n; i++) set.add(in.readUTF());
        return set;
    }

    private static Map<String, Integer> readMap(DataInputStream in) throws IOException {
        final int n = in.readInt();
        final Map<String, Integer> map = new LinkedHashMap<>(n * 2);
        for (int i = 0; i < n; i++) map.put(in.readUTF(), in.readInt());
        return map;
    }
}
