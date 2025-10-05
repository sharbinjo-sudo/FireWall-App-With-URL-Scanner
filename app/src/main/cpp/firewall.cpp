#include <jni.h>
#include <android/log.h>
#include <unistd.h>
#include <fcntl.h>
#include <thread>
#include <atomic>
#include <set>
#include <sys/socket.h>
#include <linux/in.h>
#include <linux/ip.h>
#include <linux/udp.h>
#include <linux/tcp.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "nativefirewall", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "nativefirewall", __VA_ARGS__)

static std::atomic<bool> running(false);
static std::set<int> blockedUids;

extern "C" JNIEXPORT void JNICALL
Java_com_vpn_fwwithmlb_NativeFirewall_setBlockedUidsNative(JNIEnv* env, jclass, jintArray uidArray) {
    blockedUids.clear();
    jsize length = env->GetArrayLength(uidArray);
    jint* uids = env->GetIntArrayElements(uidArray, nullptr);
    for (int i = 0; i < length; i++) {
        blockedUids.insert(uids[i]);
        LOGI("Added blocked UID: %d", uids[i]);
    }
    env->ReleaseIntArrayElements(uidArray, uids, 0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_vpn_fwwithmlb_NativeFirewall_startNative(JNIEnv* env, jclass, jint tunFd) {
    if (running) {
        LOGI("Firewall already running");
        return;
    }

    running = true;
    std::thread([tunFd]() {
        LOGI("Starting native firewall thread on fd=%d", tunFd);

        int fd = dup(tunFd);
        if (fd < 0) {
            LOGE("Failed to dup tun fd");
            return;
        }

        uint8_t buffer[4096];
        while (running) {
            ssize_t len = read(fd, buffer, sizeof(buffer));
            if (len <= 0) continue;

            struct iphdr* ip = (struct iphdr*)buffer;
            int proto = ip->protocol;
            // Here you could add UID-based filtering if you extract socket UID (requires advanced routing)
            LOGI("Packet received proto=%d len=%zd", proto, len);

            // Drop packets for now (no forwarding)
        }

        close(fd);
        LOGI("Firewall thread stopped.");
    }).detach();
}

extern "C" JNIEXPORT void JNICALL
Java_com_vpn_fwwithmlb_NativeFirewall_stopNative(JNIEnv*, jclass) {
    running = false;
    LOGI("Stopping native firewall");
}