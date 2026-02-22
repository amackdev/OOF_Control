
#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <string.h>
#include <unistd.h>
#include <sys/ioctl.h>

static jint throwErrno(JNIEnv* env, const char* msg) {
    // We don't throw Java exceptions here; return negative errno for caller to format.
    (void)env; (void)msg;
    return -errno;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_oof_control_utils_NativeIoctl_open(JNIEnv* env, jclass /*clazz*/, jstring path, jint flags) {
    const char* cpath = env->GetStringUTFChars(path, nullptr);
    if (!cpath) return -ENOMEM;
    int fd = open(cpath, flags);
    env->ReleaseStringUTFChars(path, cpath);
    if (fd < 0) return throwErrno(env, "open");
    return fd;
}

extern "C" JNIEXPORT void JNICALL
Java_com_oof_control_utils_NativeIoctl_close(JNIEnv* env, jclass /*clazz*/, jint fd) {
    (void)env;
    if (fd >= 0) close(fd);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_oof_control_utils_NativeIoctl_ioctl(JNIEnv* env, jclass /*clazz*/, jint fd, jlong request,
                                                     jbyteArray data, jint dataLen) {
    if (fd < 0) return -EBADF;
    if (!data) return -EINVAL;

    jsize n = env->GetArrayLength(data);
    if (dataLen < 0 || dataLen > n) dataLen = n;

    jbyte* buf = env->GetByteArrayElements(data, nullptr);
    if (!buf) return -ENOMEM;

    int rc = ioctl(fd, (unsigned long)request, buf);

    env->ReleaseByteArrayElements(data, buf, 0); // copy back (IOWR)

    if (rc < 0) return -errno;
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_oof_control_utils_NativeIoctl_ioctlLong(JNIEnv* env, jclass /*clazz*/, jint fd, jlong request,
                                                         jlong arg) {
    (void)env;
    // Driver expects immediate unsigned long for some commands (e.g. SELECT_TOUCH_ID).
    int rc = ioctl(fd, static_cast<unsigned long>(request), static_cast<unsigned long>(arg));
    if (rc < 0) return -errno;
    return 0;
}



extern "C" JNIEXPORT jlong JNICALL
Java_com_oof_control_utils_NativeIoctl_reqSelectTouchId(JNIEnv* env, jclass /*clazz*/) {
    (void)env;
    // _IOC(_IOC_NONE, 'T', SELECT_TOUCH_ID(3), 0)
    return static_cast<jlong>(_IOC(_IOC_NONE, 'T', 3, 0));
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_oof_control_utils_NativeIoctl_reqCommonData(JNIEnv* env, jclass /*clazz*/) {
    (void)env;
    // _IOC(_IOC_READ|_IOC_WRITE, 'T', COMMON_DATA_CMD(0), sizeof(common_data_t)=520)
    return static_cast<jlong>(_IOC(_IOC_READ | _IOC_WRITE, 'T', 0, 520));
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_oof_control_utils_NativeIoctl_reqHardwareParam(JNIEnv* env, jclass /*clazz*/) {
    (void)env;
    // _IOC(_IOC_READ, 'T', HARDWARE_PARAM_CMD(1), sizeof(hardware_param_t)=214)
    return static_cast<jlong>(_IOC(_IOC_READ, 'T', 1, 214));
}
