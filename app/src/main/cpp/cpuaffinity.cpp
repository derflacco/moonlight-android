#include <jni.h>
#include <sched.h>
#include <unistd.h>
#include <sys/syscall.h>

#include <vector>
#include <string>
#include <fstream>
#include <sstream>
#include <algorithm>

static pid_t mytid() {
#if defined(__linux__)
    return (pid_t)syscall(__NR_gettid);
#else
    return (pid_t)getpid();
#endif
}

static std::vector<int> parse_cpu_list(std::string s) {
    for (char& c : s) {
        if (c == ',' || c == '\t') c = ' ';
    }

    std::vector<int> out;
    std::istringstream iss(s);
    std::string tok;

    while (iss >> tok) {
        size_t dash = tok.find('-');
        if (dash != std::string::npos) {
            int a = std::atoi(tok.substr(0, dash).c_str());
            int b = std::atoi(tok.substr(dash + 1).c_str());
            if (a > b) std::swap(a, b);
            for (int i = a; i <= b; ++i) out.push_back(i);
        } else {
            out.push_back(std::atoi(tok.c_str()));
        }
    }

    std::sort(out.begin(), out.end());
    out.erase(std::unique(out.begin(), out.end()), out.end());
    return out;
}

static std::vector<int> read_online_cpus() {
    std::ifstream f("/sys/devices/system/cpu/online");
    if (!f.good()) return {};
    std::string line;
    std::getline(f, line);
    return parse_cpu_list(line);
}

static int cpu_count_conf() {
    long n = sysconf(_SC_NPROCESSORS_CONF);
    if (n <= 0) n = 8;
    if (n > CPU_SETSIZE) n = CPU_SETSIZE;
    return (int)n;
}

static void build_safe_affinity_mask(cpu_set_t* out, const std::vector<int>& desired) {
    CPU_ZERO(out);

    cpu_set_t wanted;
    CPU_ZERO(&wanted);
    for (int c : desired) {
        if (c >= 0 && c < CPU_SETSIZE) CPU_SET(c, &wanted);
    }

    cpu_set_t allowed;
    CPU_ZERO(&allowed);
    (void)sched_getaffinity(mytid(), sizeof(cpu_set_t), &allowed);

    cpu_set_t online;
    CPU_ZERO(&online);
    auto online_list = read_online_cpus();
    if (!online_list.empty()) {
        for (int c : online_list) if (c >= 0 && c < CPU_SETSIZE) CPU_SET(c, &online);
    } else {
        const int n = cpu_count_conf();
        for (int c = 0; c < n; ++c) CPU_SET(c, &online);
    }

    for (int c = 0; c < CPU_SETSIZE; ++c) {
        if (CPU_ISSET(c, &wanted) && CPU_ISSET(c, &online)) {
            if (CPU_COUNT(&allowed) == 0 || CPU_ISSET(c, &allowed)) {
                CPU_SET(c, out);
            }
        }
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_limelight_utils_CpuAffinity_nativeSetAffinity(JNIEnv* env, jclass, jintArray cores) {
    if (!cores) return;

    jsize n = env->GetArrayLength(cores);
    if (n <= 0) return;

    std::vector<jint> tmp((size_t)n);
    env->GetIntArrayRegion(cores, 0, n, tmp.data());

    std::vector<int> desired;
    desired.reserve((size_t)n);
    for (jsize i = 0; i < n; ++i) desired.push_back((int)tmp[(size_t)i]);

    cpu_set_t set;
    build_safe_affinity_mask(&set, desired);
    if (CPU_COUNT(&set) == 0) return;

    (void)sched_setaffinity(mytid(), sizeof(cpu_set_t), &set);
}

extern "C" JNIEXPORT void JNICALL
Java_com_limelight_utils_CpuAffinity_nativeClearCurrentThreadAffinityAllOnline(JNIEnv*, jclass) {
    auto online_list = read_online_cpus();
    if (online_list.empty()) {
        int n = cpu_count_conf();
        online_list.reserve((size_t)n);
        for (int c = 0; c < n; ++c) online_list.push_back(c);
    }

    cpu_set_t set;
    build_safe_affinity_mask(&set, online_list);
    if (CPU_COUNT(&set) == 0) return;

    (void)sched_setaffinity(mytid(), sizeof(cpu_set_t), &set);
}
