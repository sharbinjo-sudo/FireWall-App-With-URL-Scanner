#include <jni.h>
#include <string>
#include <thread>
#include <atomic>
#include <vector>
#include <set>
#include <mutex>
#include <unistd.h>
#include <fcntl.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <android/log.h>
#include <netinet/in.h>
#include <arpa/inet.h>

#define LOG_TAG "Native firewall"
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define ALOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define ALOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

static std::atomic_bool s_running(false);
static std::thread s_thread;
static int s_tun_fd = -1;

// blocked UIDs set
static std::set<int> s_blocked_uids;
static std::mutex s_blocked_mutex;

extern "C" JNIEXPORT void JNICALL
Java_com_vpn_fwwithmlb_NativeFirewall_setBlockedUidsNative(JNIEnv* env, jclass, jintArray uids) {
    std::lock_guard<std::mutex> lock(s_blocked_mutex);
    s_blocked_uids.clear();
    if (uids == nullptr) return;
    jsize len = env->GetArrayLength(uids);
    jint* arr = env->GetIntArrayElements(uids, nullptr);
    for (jsize i = 0; i < len; ++i) {
        s_blocked_uids.insert(arr[i]);
    }
    env->ReleaseIntArrayElements(uids, arr, 0);
    ALOGI("Blocked UID list updated (%zu entries)", s_blocked_uids.size());
}

static inline uint16_t parse_u16_be(const unsigned char* b) {
    return (b[0] << 8) | b[1];
}

// IPv4 header min size 20
struct ipv4_header {
    unsigned char ver_ihl;
    unsigned char tos;
    uint16_t total_len;
    uint16_t id;
    uint16_t frag_off;
    unsigned char ttl;
    unsigned char protocol;
    uint16_t checksum;
    uint32_t src_addr;
    uint32_t dst_addr;
};

struct tcp_header {
    uint16_t src_port;
    uint16_t dst_port;
    uint32_t seq;
    uint32_t ack;
    uint16_t flags_offset;
    uint16_t window;
    uint16_t checksum;
    uint16_t urgptr;
};

struct udp_header {
    uint16_t src_port;
    uint16_t dst_port;
    uint16_t len;
    uint16_t checksum;
};

// helper: convert hex ip:port pair in /proc/net/* to host order ints
static bool parse_proc_net_line(const std::string &line, std::string &local_hex, std::string &rem_hex, int &uid) {
    // Many fields: sl local_address rem_address st tx_queue rx_queue tr tm->when retrnsmt uid ...
    // We'll tokenise to find local_address and uid
    std::vector<std::string> tokens;
    size_t pos = 0, found;
    while ((found = line.find_first_of(" \t", pos)) != std::string::npos) {
        if (found > pos) tokens.push_back(line.substr(pos, found - pos));
        pos = line.find_first_not_of(" \t", found);
        if (pos == std::string::npos) break;
    }
    if (pos != std::string::npos && pos < line.size()) tokens.push_back(line.substr(pos));
    if (tokens.size() < 10) return false;
    // tokens[1] is local_address, tokens[2] rem_address, tokens[7] uid (in many kernels tokens[7] is uid)
    local_hex = tokens[1];
    rem_hex = tokens[2];
    try {
        uid = std::stoi(tokens[7]);
    } catch (...) {
        uid = -1;
    }
    return true;
}

// match local ip:port -> uid by scanning /proc/net/{tcp,udp}
static int lookup_uid_for_tuple(uint32_t src_addr, uint16_t src_port, uint8_t proto) {
    // convert to hex like AABBCCDD:PORT_HEX (note proc uses little-endian hex)
    // proc format local_address is e.g., 0100007F:1F90
    // We'll open /proc/net/tcp or udp depending on proto
    const char* path = (proto == 6) ? "/proc/net/tcp" : "/proc/net/udp";
    FILE* f = fopen(path, "r");
    if (!f) return -1;
    char *line = nullptr;
    size_t sz = 0;
    getline(&line, &sz, f); // skip header
    int found_uid = -1;
    while (getline(&line, &sz, f) > 0) {
        std::string s(line);
        std::string local_hex, rem_hex;
        int uid = -1;
        if (!parse_proc_net_line(s, local_hex, rem_hex, uid)) continue;
        // local_hex contains "HHHHHHHH:PPPP"
        // Convert local_hex ip to uint32 (little-endian hex)
        size_t colon = local_hex.find(':');
        if (colon == std::string::npos) continue;
        std::string iphex = local_hex.substr(0, colon);
        std::string porthex = local_hex.substr(colon + 1);
        // parse port
        unsigned long proth = strtoul(porthex.c_str(), nullptr, 16);
        if ((uint16_t)proth != src_port) continue;

        // parse ip bytes in little-endian order
        if (iphex.size() != 8) continue;
        // iphex is e.g. 0100007F -> bytes: 01 00 00 7F => ip = 127.0.0.1 (0x7F000001)
        unsigned int b0 = (unsigned int)strtoul(iphex.substr(0,2).c_str(), nullptr, 16);
        unsigned int b1 = (unsigned int)strtoul(iphex.substr(2,2).c_str(), nullptr, 16);
        unsigned int b2 = (unsigned int)strtoul(iphex.substr(4,2).c_str(), nullptr, 16);
        unsigned int b3 = (unsigned int)strtoul(iphex.substr(6,2).c_str(), nullptr, 16);
        uint32_t ip_le = (b0) | (b1<<8) | (b2<<16) | (b3<<24);
        // convert to network order
        uint32_t ip_n = ntohl(ip_le);
        if (ip_n == src_addr) {
            found_uid = uid;
            break;
        }
    }
    if (line) free(line);
    fclose(f);
    return found_uid;
}

static void worker_loop(int tun_fd) {
    const size_t BUF_SIZE = 32768;
    std::vector<char> buffer(BUF_SIZE);
    while (s_running.load()) {
        ssize_t len = read(tun_fd, buffer.data(), BUF_SIZE);
        if (len <= 0) {
            if (len < 0) {
                ALOGW("read() returned %zd", len);
            }
            std::this_thread::sleep_for(std::chrono::milliseconds(10));
            continue;
        }

        if ((size_t)len < sizeof(ipv4_header)) {
            ALOGD("Short packet: %zd", len);
            continue;
        }

        const unsigned char* pkt = reinterpret_cast<const unsigned char*>(buffer.data());
        const ipv4_header* ih = reinterpret_cast<const ipv4_header*>(pkt);
        unsigned char ver = ih->ver_ihl >> 4;
        if (ver != 4) {
            // currently we only handle IPv4
            continue;
        }

        uint32_t src_ip = ntohl(ih->src_addr);
        unsigned char proto = ih->protocol;
        uint16_t src_port = 0;

        if (proto == 6) { // TCP
            if ((size_t)len < sizeof(ipv4_header) + sizeof(tcp_header)) continue;
            const tcp_header* th = reinterpret_cast<const tcp_header*>(pkt + ((ih->ver_ihl & 0x0F) * 4));
            src_port = ntohs(th->src_port);
        } else if (proto == 17) { // UDP
            if ((size_t)len < sizeof(ipv4_header) + sizeof(udp_header)) continue;
            const udp_header* uh = reinterpret_cast<const udp_header*>(pkt + ((ih->ver_ihl & 0x0F) * 4));
            src_port = ntohs(uh->src_port);
        } else {
            // other protocols - skip for now
            continue;
        }

        int uid = lookup_uid_for_tuple(src_ip, src_port, proto);
        if (uid >= 0) {
            std::lock_guard<std::mutex> lock(s_blocked_mutex);
            if (s_blocked_uids.find(uid) != s_blocked_uids.end()) {
                // drop packet silently
                ALOGI("Dropped packet from UID=%d proto=%d src=%s:%u", uid, proto,
                      inet_ntoa(*(in_addr*)&ih->src_addr), src_port);
                continue;
            }
        } else {
            // if no uid found, optionally forward (we'll just forward for now)
        }

        // For this prototype we just forward the packet to a helper via writing to a pipe
        // Integration point: write to tun2socks input or implement forwarding here
        // For now, do nothing (i.e., let the packet be dropped) — so only blocked UIDs are dropped explicitly.
        // NOTE: To provide full connectivity you must integrate a forwarder (tun2socks).
    }
    ALOGI("Native firewall worker exiting");
}

extern "C" JNIEXPORT void JNICALL
Java_com_vpn_fwwithmlb_NativeFirewall_startNative(JNIEnv* env, jclass, jint tunFd) {
    if (s_running.load()) {
        ALOGW("native firewall already running");
        return;
    }
    s_tun_fd = tunFd;
    // Set non-blocking read? keep blocking
    int flags = fcntl(s_tun_fd, F_GETFL, 0);
    fcntl(s_tun_fd, F_SETFL, flags & ~O_NONBLOCK);

    s_running.store(true);
    s_thread = std::thread([tunFd]() {
        worker_loop(tunFd);
    });
    ALOGI("Native firewall started on fd=%d", tunFd);
}

extern "C" JNIEXPORT void JNICALL
Java_com_vpn_fwwithmlb_NativeFirewall_stopNative(JNIEnv* env, jclass) {
    if (!s_running.load()) return;
    s_running.store(false);
    if (s_thread.joinable()) s_thread.join();
    if (s_tun_fd >= 0) {
        close(s_tun_fd);
        s_tun_fd = -1;
    }
    ALOGI("Native firewall stopped");
}
