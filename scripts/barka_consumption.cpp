// Observe only successful packet IO on the TUN. Forward calls unchanged.
#include <jni.h>
#include <unistd.h>
#include <atomic>
#include <cerrno>
static std::atomic<int> barka_fd{-1};
static std::atomic<unsigned long long> barka_up{0}, barka_down{0};
extern "C" ssize_t __real_read(int, void*, size_t);
extern "C" ssize_t __real_write(int, const void*, size_t);
extern "C" ssize_t __wrap_read(int fd, void* data, size_t count) {
    ssize_t n=__real_read(fd,data,count);int saved=errno;
    if(n>0 && fd==barka_fd.load(std::memory_order_relaxed)) barka_up.fetch_add(n,std::memory_order_relaxed);
    errno=saved;return n;
}
extern "C" ssize_t __wrap_write(int fd, const void* data, size_t count) {
    ssize_t n=__real_write(fd,data,count);int saved=errno;
    if(n>0 && fd==barka_fd.load(std::memory_order_relaxed)) barka_down.fetch_add(n,std::memory_order_relaxed);
    errno=saved;return n;
}
extern "C" JNIEXPORT void JNICALL
Java_com_LondonX_tun2socks_Tun2Socks_setConsumptionFd(JNIEnv*,jclass,jint fd) {
    barka_up.store(0);barka_down.store(0);barka_fd.store(fd);
}
extern "C" JNIEXPORT jlongArray JNICALL
Java_com_LondonX_tun2socks_Tun2Socks_consumptionBytes(JNIEnv* env,jclass) {
    jlong values[2]={static_cast<jlong>(barka_up.load()),static_cast<jlong>(barka_down.load())};
    jlongArray result=env->NewLongArray(2);
    if(result)env->SetLongArrayRegion(result,0,2,values);
    return result;
}
