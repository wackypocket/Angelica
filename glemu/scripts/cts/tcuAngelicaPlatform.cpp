// The conformance suite's platform: OpenGL is whatever the JVM that loaded this library provides.
#include "tcuPlatform.hpp"
#include "tcuDefs.hpp"
#include "tcuRenderTarget.hpp"
#include "gluPlatform.hpp"
#include "gluContextFactory.hpp"
#include "gluRenderConfig.hpp"
#include "gluRenderContext.hpp"
#include "glwFunctions.hpp"
#include "glwInitFunctions.hpp"

#include <jni.h>
#include <string>
#include <unordered_map>
#include <vector>

int angelica_cts_main(int argc, char **argv);

namespace
{

// Everything runs on the thread the JVM called in on.
JNIEnv *jniEnv;
jclass bridgeClass;
jmethodID addressMethod;
jmethodID frameDoneMethod;

const int TARGET_SIZE = 256;

class Loader : public glw::FunctionLoader
{
public:
    glw::GenericFuncType get(const char *name) const override
    {
        jstring text          = jniEnv->NewStringUTF(name);
        const jlong address   = jniEnv->CallStaticLongMethod(bridgeClass, addressMethod, text);
        jniEnv->DeleteLocalRef(text);
        return reinterpret_cast<glw::GenericFuncType>(address);
    }
};

class Context : public glu::RenderContext
{
public:
    explicit Context(const glu::RenderConfig &config)
        : m_type(config.type)
        , m_target(TARGET_SIZE, TARGET_SIZE, tcu::PixelFormat(8, 8, 8, 8), 24, 8, 0)
    {
        const Loader loader;
        glu::initCoreFunctions(&m_functions, &loader, config.type.getAPI());
        glu::initExtensionFunctions(&m_functions, &loader, config.type.getAPI());
    }

    glu::ContextType getType(void) const override
    {
        return m_type;
    }

    const glw::Functions &getFunctions(void) const override
    {
        return m_functions;
    }

    const tcu::RenderTarget &getRenderTarget(void) const override
    {
        return m_target;
    }

    void postIterate(void) override
    {
        jniEnv->CallStaticVoidMethod(bridgeClass, frameDoneMethod);
    }

    glw::GenericFuncType getProcAddress(const char *name) const override
    {
        return Loader().get(name);
    }

private:
    const glu::ContextType m_type;
    const tcu::RenderTarget m_target;
    glw::Functions m_functions;
};

class Factory : public glu::ContextFactory
{
public:
    Factory(void) : glu::ContextFactory("angelica", "The JVM's OpenGL")
    {
    }

    glu::RenderContext *createContext(const glu::RenderConfig &config, const tcu::CommandLine &,
                                      const glu::RenderContext *) const override
    {
        // There is one GL state in the process, so a context with other flags or a second profile cannot be offered.
        if (!glu::isContextTypeGLCore(config.type) || config.type.getFlags() != glu::ContextFlags(0))
            throw tcu::NotSupportedError("only a plain core context exists here");
        return new Context(config);
    }
};

class Platform : public tcu::Platform, private glu::Platform
{
public:
    Platform(void)
    {
        m_contextFactoryRegistry.registerFactory(new Factory());
    }

    const glu::Platform &getGLPlatform(void) const override
    {
        return *this;
    }

    bool processEvents(void) override
    {
        return true;
    }
};

} // namespace

tcu::Platform *createPlatform(void)
{
    return new Platform();
}

extern "C" JNIEXPORT jint JNICALL Java_com_gtnewhorizons_angelica_glemu_nativetest_glsm_GlCtsBridge_run(JNIEnv *env, jclass cls, jobjectArray args)
{
    jniEnv          = env;
    bridgeClass     = cls;
    addressMethod   = env->GetStaticMethodID(cls, "address", "(Ljava/lang/String;)J");
    frameDoneMethod = env->GetStaticMethodID(cls, "frameDone", "()V");

    std::vector<std::string> text;
    text.emplace_back("glcts");
    for (jsize i = 0; i < env->GetArrayLength(args); i++)
    {
        jstring arg       = static_cast<jstring>(env->GetObjectArrayElement(args, i));
        const char *chars = env->GetStringUTFChars(arg, nullptr);
        text.emplace_back(chars);
        env->ReleaseStringUTFChars(arg, chars);
    }
    std::vector<char *> argv;
    for (std::string &arg : text)
        argv.push_back(&arg[0]);
    return angelica_cts_main(static_cast<int>(argv.size()), argv.data());
}
