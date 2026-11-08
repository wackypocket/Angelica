package com.gtnewhorizons.angelica.glemu.compat;

import com.gtnewhorizons.angelica.glemu.NativeBackendBase;
import com.gtnewhorizons.angelica.glsm.backend.BackendManager;
import com.gtnewhorizons.angelica.glsm.backend.GlCallLog;
import com.gtnewhorizons.angelica.glsm.redirect.GLSMRedirector;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.system.APIUtil;
import org.lwjgl.system.Callback;
import org.lwjgl.system.CallbackI;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.FunctionProvider;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Pointer;
import org.lwjgl.system.libffi.FFICIF;
import org.lwjgl.system.libffi.FFIClosure;
import org.lwjgl.system.libffi.FFIType;
import org.lwjgl.system.libffi.LibFFI;

/**
 * The driver behind LWJGL's GL function pointers while no real GL context exists. A call the redirector did not rewrite still
 * dispatches through these pointers, so each resolves to a native stub: {@link GlEntryPoints} first, then the GLSM method the
 * redirector would have chosen, then the ARB, EXT or KHR name without its suffix, and otherwise a no-op returning 0 that logs the call once.
 */
public final class GlFunctions implements FunctionProvider {

    public static final GlFunctions INSTANCE = new GlFunctions();

    private static final Logger LOG = LogManager.getLogger("Angelica/GPU");
    private static final List<String> ALIAS_SUFFIXES = List.of("ARB", "EXT", "KHR");
    /** ARB_vertex_program's calls, which look like GLSL's without the suffix but take a target rather than a program. */
    private static final Set<String> NOT_ALIASES = Set.of("glIsProgramARB", "glGetProgramivARB");
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();
    private static final Map<String, MethodHandle> ENTRY_POINTS = entryPoints();

    private final Map<String, Long> addresses = new ConcurrentHashMap<>();
    private long noOp;

    private GlFunctions() {}

    @Override public long getFunctionAddress(ByteBuffer functionName) {
        return address(MemoryUtil.memASCII(MemoryUtil.memAddress(functionName)));
    }

    long address(String name) {
        return addresses.computeIfAbsent(name, this::resolve);
    }

    private long resolve(String name) {
        final MethodHandle target = target(name);
        return target != null ? new Bound(name, target).address() : noOpStub();
    }

    private synchronized long noOpStub() {
        if (noOp == 0L) noOp = new NoOp().address();
        return noOp;
    }

    /** The method implementing a native GL function, or null when nothing does. */
    public static MethodHandle target(String name) {
        final MethodHandle direct = implementation(name);
        if (direct != null) return direct;
        if (NOT_ALIASES.contains(name)) return null;
        for (String suffix : ALIAS_SUFFIXES) {
            if (name.endsWith(suffix)) return implementation(name.substring(0, name.length() - suffix.length()));
        }
        return null;
    }

    private static MethodHandle implementation(String name) {
        final MethodHandle entryPoint = ENTRY_POINTS.get(name);
        return entryPoint != null ? entryPoint : glsmMethod(name);
    }

    /** The GLSM method taking the same primitive arguments as the native function, so only an unambiguous one is usable. */
    private static MethodHandle glsmMethod(String name) {
        final String glsmName = GLSMRedirector.getGLPrefixMethodRedirects().get(name);
        if (glsmName == null) return null;
        final ClassLoader loader = GlFunctions.class.getClassLoader();
        String descriptor = null;
        for (String overload : GLSMRedirector.targetOverloads(glsmName)) {
            if (overload.indexOf('L') >= 0 || overload.indexOf('[') >= 0) continue;
            if (descriptor != null) return null;
            descriptor = overload;
        }
        if (descriptor == null) return null;
        try {
            final Class<?> owner = Class.forName(GLSMRedirector.getTargetOwner(glsmName).replace('/', '.'), false, loader);
            return MethodHandles.publicLookup().findStatic(owner, glsmName, MethodType.fromMethodDescriptorString(descriptor, loader));
        } catch (ReflectiveOperationException | TypeNotPresentException e) {
            LOG.warn("Could not bind {}", name, e);
            return null;
        }
    }

    private static Map<String, MethodHandle> entryPoints() {
        final Map<String, MethodHandle> out = new HashMap<>();
        for (Method m : GlEntryPoints.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(m.getModifiers())) continue;
            try {
                out.put(m.getName(), MethodHandles.publicLookup().unreflect(m));
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
        return out;
    }

    private static FFIType ffiType(Class<?> c) {
        if (c == void.class) return LibFFI.ffi_type_void;
        if (c == int.class) return LibFFI.ffi_type_sint32;
        if (c == long.class) return LibFFI.ffi_type_sint64;
        if (c == float.class) return LibFFI.ffi_type_float;
        if (c == double.class) return LibFFI.ffi_type_double;
        if (c == short.class) return LibFFI.ffi_type_sint16;
        if (c == byte.class) return LibFFI.ffi_type_sint8;
        if (c == boolean.class) return LibFFI.ffi_type_uint8;
        throw new IllegalArgumentException(c.getName());
    }

    private static Object readArg(Class<?> c, long at) {
        final long value = MemoryUtil.memGetAddress(at);
        if (c == int.class) return MemoryUtil.memGetInt(value);
        if (c == long.class) return MemoryUtil.memGetLong(value);
        if (c == float.class) return MemoryUtil.memGetFloat(value);
        if (c == double.class) return MemoryUtil.memGetDouble(value);
        if (c == short.class) return MemoryUtil.memGetShort(value);
        if (c == byte.class) return MemoryUtil.memGetByte(value);
        return MemoryUtil.memGetByte(value) != 0;
    }

    private static void writeReturn(Class<?> c, long ret, Object value) {
        if (c == void.class) return;
        if (c == int.class) APIUtil.apiClosureRet(ret, value == null ? 0 : (int) value);
        else if (c == long.class) APIUtil.apiClosureRetL(ret, value == null ? 0L : (long) value);
        else if (c == float.class) APIUtil.apiClosureRet(ret, value == null ? 0f : (float) value);
        else if (c == double.class) APIUtil.apiClosureRet(ret, value == null ? 0d : (double) value);
        else if (c == short.class) APIUtil.apiClosureRet(ret, value == null ? (short) 0 : (short) value);
        else if (c == byte.class) APIUtil.apiClosureRet(ret, value == null ? (byte) 0 : (byte) value);
        else APIUtil.apiClosureRet(ret, value != null && (boolean) value);
    }

    /**
     * One upcall receives every function. LWJGL's own callbacks bind a Java signature per C signature on newer JVMs, and there
     * are too many of those here, so each function is its own libffi closure over this dispatcher.
     */
    @FunctionalInterface
    interface Dispatcher extends CallbackI {
        Callback.Descriptor DESCRIPTOR = new Callback.Descriptor(Dispatcher.class, MethodHandles.lookup(),
            APIUtil.apiCreateCIF(LibFFI.ffi_type_void, LibFFI.ffi_type_pointer, LibFFI.ffi_type_pointer, LibFFI.ffi_type_pointer, LibFFI.ffi_type_pointer));

        @Override default Callback.Descriptor getDescriptor() {
            return DESCRIPTOR;
        }

        @Override default void callback(long ret, long args) {
            dispatch(MemoryUtil.memGetAddress(MemoryUtil.memGetAddress(args)),
                MemoryUtil.memGetAddress(MemoryUtil.memGetAddress(args + Pointer.POINTER_SIZE)),
                MemoryUtil.memGetAddress(MemoryUtil.memGetAddress(args + 2L * Pointer.POINTER_SIZE)),
                MemoryUtil.memGetAddress(MemoryUtil.memGetAddress(args + 3L * Pointer.POINTER_SIZE)));
        }

        void dispatch(long cif, long ret, long args, long userData);
    }

    private static final List<Function> FUNCTIONS = new CopyOnWriteArrayList<>();
    private static final long DISPATCHER = ((Dispatcher) (cif, ret, args, userData) -> FUNCTIONS.get((int) userData).call(ret, args)).address();

    private static long closure(Function function, FFIType returns, FFIType... args) {
        final FFICIF cif = APIUtil.apiCreateCIF(returns, args);
        final int index;
        synchronized (FUNCTIONS) {
            index = FUNCTIONS.size();
            FUNCTIONS.add(function);
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final PointerBuffer code = stack.mallocPointer(1);
            final FFIClosure closure = LibFFI.ffi_closure_alloc(FFIClosure.SIZEOF, code);
            if (closure == null) throw new OutOfMemoryError("ffi_closure_alloc");
            final int status = LibFFI.ffi_prep_closure_loc(closure, cif, DISPATCHER, index, code.get(0));
            if (status != LibFFI.FFI_OK) throw new IllegalStateException("ffi_prep_closure_loc: " + status);
            return code.get(0);
        }
    }

    /** A call reaching this table with no native backend running: ignored like any function nothing implements. */
    private static final class NeedsNativeBackend extends RuntimeException {
        NeedsNativeBackend() {
            super(null, null, false, false);
        }
    }

    static final RuntimeException NEEDS_NATIVE_BACKEND = new NeedsNativeBackend();

    static void reportIgnoredCall() {
        if (GlCallLog.ENABLED) GlCallLog.refusal("not implemented");
        final List<StackWalker.StackFrame> frames = new ArrayList<>();
        StackWalker.getInstance().walk(s -> {
            s.dropWhile(f -> !f.getClassName().startsWith("org.lwjgl.opengl.")).dropWhile(f -> f.getClassName().startsWith("org.lwjgl.")).limit(1).forEach(frames::add);
            return null;
        });
        final String caller = frames.isEmpty() ? "unknown" : frames.get(0).toString();
        if (REPORTED.add(caller)) LOG.warn("Unsupported GL call ignored, from {}", caller);
    }

    private interface Function {
        void call(long ret, long args);
    }

    private static final class Bound implements Function {
        private final String name;
        private final MethodHandle target;
        private final Class<?>[] params;
        private final Class<?> returns;

        Bound(String name, MethodHandle target) {
            this.name = name;
            this.target = target;
            params = target.type().parameterArray();
            returns = target.type().returnType();
        }

        long address() {
            final FFIType[] args = new FFIType[params.length];
            for (int i = 0; i < args.length; i++) args[i] = ffiType(params[i]);
            return closure(this, ffiType(returns), args);
        }

        @Override public void call(long ret, long args) {
            if (GlCallLog.ENABLED) GlCallLog.call(name);
            final Object[] values = new Object[params.length];
            for (int i = 0; i < values.length; i++) values[i] = readArg(params[i], args + (long) i * Pointer.POINTER_SIZE);
            final ReentrantLock lock = BackendManager.RENDER_BACKEND instanceof NativeBackendBase<?> nativeBackend ? nativeBackend.directCallLock() : null;
            Object result = null;
            if (lock != null) lock.lock();
            try {
                result = target.invokeWithArguments(values);
            } catch (NeedsNativeBackend e) {
                reportIgnoredCall();
            } catch (Throwable t) {
                // Unwinding through a native frame would take the game down.
                if (REPORTED.add(target.toString())) LOG.error("GL call {} failed", target, t);
            } finally {
                if (lock != null) lock.unlock();
            }
            writeReturn(returns, ret, result);
        }
    }

    private static final class NoOp implements Function {
        long address() {
            return closure(this, LibFFI.ffi_type_sint64);
        }

        @Override public void call(long ret, long args) {
            reportIgnoredCall();
            APIUtil.apiClosureRetL(ret, 0L);
        }
    }
}
