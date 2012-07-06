#include <utils/misc.h>
#include "JNIHelp.h"
#include "jni.h"
#include "android_runtime/AndroidRuntime.h"

extern int internal_modem_power(int on);
extern int is_support_voice();
extern int find_usb_hub();

using namespace android;

static void throw_NullPointerException(JNIEnv *env, const char* msg)
{
    jclass clazz;
    clazz = env->FindClass("java/lang/NullPointerException");
    env->ThrowNew(clazz, msg);
}

static void 
com_android_internal_telephony_GSMPhone_enablePower(JNIEnv *env, jobject clazz, jboolean on)
{
    internal_modem_power(on);
}


static int
com_android_internal_telephony_GSMPhone_get3GSupportType(JNIEnv *env, jobject clazz)
{
    if(!find_usb_hub())
        return 0;
    return is_support_voice();
}

static JNINativeMethod method_table[] = {
    { "nativeEnablePower", "(Z)V", (void*)com_android_internal_telephony_GSMPhone_enablePower },
    { "nativeGet3GSupportType", "()I", (void*)com_android_internal_telephony_GSMPhone_get3GSupportType },
};
/*

 * Register native methods using JNI.
 */
/*static*/ int registerNativeMethods(JNIEnv* env,
    const char* className, const JNINativeMethod* gMethods, int numMethods)
{
   	return jniRegisterNativeMethods(env, className, gMethods, numMethods);
}
int register_com_android_internal_telephony_GSMPhone(JNIEnv *env)
{
    return AndroidRuntime::registerNativeMethods (
        env, "com/android/internal/telephony/gsm/GSMPhone",
        method_table, NELEM(method_table));
}


extern "C" jint JNI_OnLoad(JavaVM* vm, void* reserved)
{
    JNIEnv* env = NULL;
    jint result = -1;
    if (vm->GetEnv((void**) &env, JNI_VERSION_1_4) != JNI_OK) {
        LOGE("GetEnv failed!");
        return result;
    }
    LOG_ASSERT(env, "Could not retrieve the env!");

    register_com_android_internal_telephony_GSMPhone(env);

    return JNI_VERSION_1_4;
}

