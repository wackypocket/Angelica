package com.gtnewhorizons.angelica.vulkan.device;

import com.gtnewhorizons.angelica.glemu.shader.SpvcSupport;
import java.lang.invoke.MethodHandles;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.system.Callback;
import org.lwjgl.system.CallbackI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.SharedLibrary;
import org.lwjgl.system.macosx.MacOSXLibraryDL;
import org.lwjgl.system.macosx.ObjCRuntime;

import static org.lwjgl.system.APIUtil.apiCreateCIF;
import static org.lwjgl.system.JNI.invokeP;
import static org.lwjgl.system.JNI.invokePPP;
import static org.lwjgl.system.JNI.invokePPPP;
import static org.lwjgl.system.JNI.invokePPPPPP;
import static org.lwjgl.system.JNI.invokePPPPPV;
import static org.lwjgl.system.MemoryUtil.memAddress;
import static org.lwjgl.system.MemoryUtil.memGetAddress;
import static org.lwjgl.system.MemoryUtil.memPutAddress;
import static org.lwjgl.system.MemoryUtil.memUTF8;
import static org.lwjgl.system.Pointer.POINTER_SIZE;
import static org.lwjgl.system.libffi.LibFFI.ffi_type_pointer;
import static org.lwjgl.system.libffi.LibFFI.ffi_type_void;

/**
 * MoltenVK turns SPIR-V into Metal source itself and offers no step between that and compiling it, so what it gets wrong in the source is corrected
 * on the way into the device. What doesn't need correcting goes through untouched.
 */
final class MoltenVkMsl {
    private static final Logger LOG = LogManager.getLogger("Angelica/Vulkan");
    private static final long SEND = ObjCRuntime.getLibrary().getFunctionAddress("objc_msgSend");
    private static final long UTF8_STRING = ObjCRuntime.sel_getUid("UTF8String"), ALLOC = ObjCRuntime.sel_getUid("alloc");
    private static final long INIT_UTF8 = ObjCRuntime.sel_getUid("initWithUTF8String:"), RELEASE = ObjCRuntime.sel_getUid("release");
    private static long original, originalNow;
    private static CallbackI hook, hookNow;
    private static volatile boolean warned;

    private MoltenVkMsl() {}

    @FunctionalInterface
    private interface NewLibraryI extends CallbackI {
        Callback.Descriptor DESCRIPTOR = new Callback.Descriptor(NewLibraryI.class, MethodHandles.lookup(),
            apiCreateCIF(ffi_type_void, ffi_type_pointer, ffi_type_pointer, ffi_type_pointer, ffi_type_pointer, ffi_type_pointer));

        @Override default Callback.Descriptor getDescriptor() { return DESCRIPTOR; }

        @Override default void callback(long ret, long args) {
            invoke(arg(args, 0), arg(args, 1), arg(args, 2), arg(args, 3), arg(args, 4));
        }

        void invoke(long device, long selector, long source, long options, long completion);
    }

    /** The form that returns the library, with an error out parameter in place of the completion handler. */
    @FunctionalInterface
    private interface NewLibraryNowI extends CallbackI {
        Callback.Descriptor DESCRIPTOR = new Callback.Descriptor(NewLibraryNowI.class, MethodHandles.lookup(),
            apiCreateCIF(ffi_type_pointer, ffi_type_pointer, ffi_type_pointer, ffi_type_pointer, ffi_type_pointer, ffi_type_pointer));

        @Override default Callback.Descriptor getDescriptor() { return DESCRIPTOR; }

        @Override default void callback(long ret, long args) {
            memPutAddress(ret, invoke(arg(args, 0), arg(args, 1), arg(args, 2), arg(args, 3), arg(args, 4)));
        }

        long invoke(long device, long selector, long source, long options, long error);
    }

    private static long arg(long args, int index) {
        return memGetAddress(memGetAddress(args + index * (long) POINTER_SIZE));
    }

    static synchronized void install() {
        if (hook != null) return;
        final SharedLibrary metal = new MacOSXLibraryDL("/System/Library/Frameworks/Metal.framework/Metal");
        try {
            final long device = invokeP(metal.getFunctionAddress("MTLCreateSystemDefaultDevice"));
            if (device == 0L) return;
            final long type = ObjCRuntime.object_getClass(device);
            final long method = ObjCRuntime.class_getInstanceMethod(type, ObjCRuntime.sel_getUid("newLibraryWithSource:options:completionHandler:"));
            final long methodNow = ObjCRuntime.class_getInstanceMethod(type, ObjCRuntime.sel_getUid("newLibraryWithSource:options:error:"));
            invokePPP(device, RELEASE, SEND);
            if (method == 0L || methodNow == 0L) LOG.warn("Metal lacks a way MoltenVK compiles shaders by, so what it gets wrong in them can't be corrected there");
            if (method != 0L) {
                original = ObjCRuntime.method_getImplementation(method);
                hook = ((NewLibraryI) MoltenVkMsl::newLibrary);
                ObjCRuntime.method_setImplementation(method, hook.address());
            }
            if (methodNow != 0L) {
                originalNow = ObjCRuntime.method_getImplementation(methodNow);
                hookNow = ((NewLibraryNowI) MoltenVkMsl::newLibraryNow);
                ObjCRuntime.method_setImplementation(methodNow, hookNow.address());
            }
        } finally {
            metal.free();
        }
    }

    /** The source corrected, as a new string the caller releases, or 0 when it needs nothing. */
    private static long corrected(long source) {
        try {
            final String msl = memUTF8(invokePPP(source, UTF8_STRING, SEND));
            final String fixed = SpvcSupport.withEmptyPadding(msl);
            if (fixed.equals(msl)) {
                if (!warned && SpvcSupport.padsUnknownly(msl)) {
                    warned = true;
                    LOG.warn("MoltenVK pads arrays of structs in a way Angelica doesn't know, so a shader with one may fail to compile");
                }
                return 0L;
            }
            try (MemoryStack stack = MemoryStack.stackPush()) {
                final long string = invokePPP(ObjCRuntime.objc_getClass("NSString"), ALLOC, SEND);
                return invokePPPP(string, INIT_UTF8, memAddress(stack.UTF8(fixed)), SEND);
            }
        } catch (Throwable t) {
            LOG.error("Could not correct a MoltenVK shader", t);
            return 0L;
        }
    }

    private static void newLibrary(long device, long selector, long source, long options, long completion) {
        final long corrected = corrected(source);
        invokePPPPPV(device, selector, corrected != 0L ? corrected : source, options, completion, original);
        if (corrected != 0L) invokePPP(corrected, RELEASE, SEND);
    }

    private static long newLibraryNow(long device, long selector, long source, long options, long error) {
        final long corrected = corrected(source);
        final long library = invokePPPPPP(device, selector, corrected != 0L ? corrected : source, options, error, originalNow);
        if (corrected != 0L) invokePPP(corrected, RELEASE, SEND);
        return library;
    }
}
