// native_firewall.cpp
#include <jni.h>
#include <thread>
#include <atomic>
#include <set>
#include <mutex>
#include <vector>
#include <unistd.h>
#include <fcntl.h>
#include <android/log.h>
#include <arpa/inet.h>
#include <netinet/ip.h>
#include <string>
#include <fstream>
#include <sstream>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "nativefirewall", __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "nativefirewall", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "nativefirewall", __VA_ARGS__)

static std::atomic_bool running(false);
static std::thread worker;
static std::set<int> blocked_uids;
static std::mutex blocked_mutex;

/**
 * Lookup UID for a given source IPv4 (host-order) and source-port.
 * Note: /proc/net/tcp and /proc/net/udp store IP as little-endian hex string.
 * We parse hex directly and compare to ntohl(iph->saddr) (host-order).
 */
static int lookup_uid_ipv4(uint32_t src_addr_host, uint16_t src_port, int proto) {
    const char* path = (proto == IPPROTO_TCP) ? "/proc/net/tcp" : "/proc/net/udp";
    std::ifstream f(path);
    if (!f.is_open()) return -1;

    std::string line;
    std::getline(f, line); // skip header
    while (std::getline(f, line)) {
        std::istringstream iss(line);
        std::string sl, local_addr, rem, st, txq, rxq, tr, tm_when, retr, uid_s;
        if (!(iss >> sl >> local_addr >> rem >> st >> txq >> rxq >> tr >> tm_when >> retr >> uid_s)) continue;

        auto colon = local_addr.find(':');
        if (colon == std::string::npos) continue;
        std::string iphex = local_addr.substr(0, colon);
        std::string porthex = local_addr.substr(colon + 1);

        // parse port (hex) - this is straightforward
        unsigned long port_val = strtoul(porthex.c_str(), nullptr, 16);
        if ((uint16_t)port_val != src_port) continue;

        // parse iphex (hex) to unsigned long
        // /proc/net/tcp shows little-endian hex like "0100007F" for 127.0.0.1
        unsigned long iphex_val = strtoul(iphex.c_str(), nullptr, 16);

        // iphex_val (as read) equals ntohl(iph->saddr) if iph->saddr was converted by ntohl()
        if ((uint32_t)iphex_val == src_addr_host) {
            try {
                return stoi(uid_s);
            } catch (...) {
                return -1;
            }
        }
    }
    return -1;
}

static void worker_loop(int tun_fd) {
    LOGI("🔥 Native VPN firewall worker starting on fd=%d", tun_fd);

    const int BUF_SIZE = 65536;
    std::vector<uint8_t> buf(BUF_SIZE);

    int fd = dup(tun_fd);
    if (fd < 0) {
        LOGE("dup(tun_fd) failed");
        return;
    }

    // set blocking
    int flags = fcntl(fd, F_GETFL, 0);
    flags &= ~O_NONBLOCK;
    fcntl(fd, F_SETFL, flags);

    while (running.load()) {
        ssize_t len = read(fd, buf.data(), BUF_SIZE);
        if (len <= 0) {
            usleep(5000);
            continue;
        }

        if (len < (ssize_t)sizeof(struct iphdr)) continue;
        struct iphdr* iph = (struct iphdr*)buf.data();
        if (iph->version != 4) continue;

        uint32_t src_addr_host = ntohl(iph->saddr); // host-order
        int ihl = (iph->ihl & 0x0F) * 4;
        int proto = iph->protocol;
        uint16_t src_port = 0;

        if (proto == IPPROTO_TCP || proto == IPPROTO_UDP) {
            if ((size_t)len < (size_t)(ihl + 4)) continue;
            uint8_t* ptr = buf.data() + ihl;
            src_port = (ptr[0] << 8) | ptr[1]; // network-order -> correct numeric port
        }

        int uid = -1;
        if (src_port != 0) uid = lookup_uid_ipv4(src_addr_host, src_port, proto);

        bool drop = false;
        {
            std::lock_guard<std::mutex> lk(blocked_mutex);
            if (uid >= 0 && blocked_uids.find(uid) != blocked_uids.end()) drop = true;
        }

        if (drop) {
            LOGI("🚫 DROPPED: UID=%d proto=%d src=%u.%u.%u.%u:%u len=%zd",
                 uid, proto,
                 (src_addr_host >> 24) & 0xFF, (src_addr_host >> 16) & 0xFF,
                 (src_addr_host >> 8) & 0xFF, src_addr_host & 0xFF,
                 src_port, len);
            continue; // consume packet (drop)
        }

        // Allowed packet: we do nothing (blackhole behavior). If you want to forward later,
        // integrate a tun2socks forwarder and write to it here.
        // Optionally: write(fd, buf.data(), len); to echo back (not recommended).
#ifdef NATIVEFW_VERBOSE
        LOGI("ALLOWED: uid=%d proto=%d len=%zd", uid, proto, len);
#endif
    }

    close(fd);
    LOGI("🛑 Native VPN firewall worker stopped");
}

extern "C"
JNIEXPORT void JNICALL
Java_com_vpn_fwwithmlb_NativeBridge_setBlockedUidsNative(JNIEnv* env, jclass, jintArray uids) {
    std::lock_guard<std::mutex> lk(blocked_mutex);
    blocked_uids.clear();
    if (uids == nullptr) return;
    jsize len = env->GetArrayLength(uids);
    jint* arr = env->GetIntArrayElements(uids, nullptr);
    for (jsize i = 0; i < len; ++i) blocked_uids.insert((int)arr[i]);
    env->ReleaseIntArrayElements(uids, arr, 0);
    LOGI("🧱 setBlockedUidsNative: %zu UIDs", blocked_uids.size());
}

extern "C"
JNIEXPORT void JNICALL
Java_com_vpn_fwwithmlb_NativeBridge_startNative(JNIEnv*, jclass, jint tunFd) {
    if (running.load()) {
        LOGW("startNative called but already running");
        return;
    }
    running.store(true);
    worker = std::thread([tunFd]() { worker_loop(tunFd); });
    LOGI("startNative: worker thread launched");
}

extern "C"
JNIEXPORT void JNICALL
Java_com_vpn_fwwithmlb_NativeBridge_stopNative(JNIEnv*, jclass) {
    if (!running.load()) return;
    running.store(false);
    if (worker.joinable()) worker.join();
    LOGI("stopNative: thread joined");
}
