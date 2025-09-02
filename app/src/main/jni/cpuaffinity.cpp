// cpuaffinity.cpp — robust big-core detection + safe affinity (rev3, Qualcomm-aware)
//
// Additions in rev3 (aimed at Snapdragon 865 and similar):
// - Prefer cpuinfo_max_freq over scaling_max_freq everywhere (avoid thermal/governor caps)
// - Policy union threshold relaxed (>= 0.80 of top policy)
// - If top policy is single-CPU (prime-only), include the 2nd best policy CPUs unconditionally
// - New fallback: use topology cluster_id of CPU7 (prime) to include its cluster mates (e.g., 4-7)
// - Keep per-CPU expansion with 90% of global peak (now using cpuinfo-first), and median fallback
// - Safety: intersect with cpuset allowed + online CPUs before sched_setaffinity()
//
#include <jni.h>
#include <sched.h>
#include <unistd.h>
#include <vector>
#include <string>
#include <fstream>
#include <algorithm>
#include <sstream>
#include <cstdlib>
#include <cstdio>
#include <dirent.h>
static void build_safe_affinity_mask_for_tid(pid_t tid, cpu_set_t* out, const std::vector<jint>& desired);



// -------- small IO helpers --------
static bool read_long_file_first_of(const std::vector<std::string>& paths, long& out) {
    for (const auto& p : paths) {
        std::ifstream f(p);
        if (!f.good()) continue;
        long v = 0;
        f >> v;
        if (!f.fail()) { out = v; return true; }
    }
    return false;
}

static bool read_long_file(const std::string& path, long& out) {
    std::ifstream f(path);
    if (!f.good()) return false;
    long v = 0;
    f >> v;
    if (!f.fail()) { out = v; return true; }
    return false;
}

static std::string read_string_file(const std::string& path) {
    std::ifstream f(path);
    if (!f.good()) return "";
    std::string s; std::getline(f, s);
    while (!s.empty() && (s.back()=='\n' || s.back()=='\r' || s.back()==' ' || s.back()=='\t' || s.back()==',')) s.pop_back();
    return s;
}

// Parse CPU list formats like "0-3,6,8-9" or "0 1 2 3"
static std::vector<int> parse_cpu_list(std::string s) {
    for (char& c : s) if (c==',' || c=='\t') c = ' ';
    std::vector<int> out;
    std::istringstream iss(s);
    std::string tok;
    while (iss >> tok) {
        size_t dash = tok.find('-');
        if (dash != std::string::npos) {
            int a = std::atoi(tok.substr(0, dash).c_str());
            int b = std::atoi(tok.substr(dash+1).c_str());
            if (a > b) std::swap(a, b);
            for (int i=a; i<=b; ++i) out.push_back(i);
        } else {
            out.push_back(std::atoi(tok.c_str()));
        }
    }
    std::sort(out.begin(), out.end());
    out.erase(std::unique(out.begin(), out.end()), out.end());
    return out;
}

// Online CPUs (best-effort)
static std::vector<int> read_online_cpus() {
    std::ifstream f("/sys/devices/system/cpu/online");
    if (!f.good()) return {};
    std::string line; std::getline(f, line);
    return parse_cpu_list(line);
}

// Number of configured CPUs (upper bound)
static int get_cpu_count_conf() {
    long n = sysconf(_SC_NPROCESSORS_CONF);
    if (n <= 0) n = 8;
    if (n > CPU_SETSIZE) n = CPU_SETSIZE;
    return (int)n;
}

// Read per-CPU max freq (kHz), **prefer cpuinfo_max_freq** then scaling_max_freq
static long read_cpu_max_khz_sysfs(int cpu) {
    char p1[192], p2[192];
    std::snprintf(p1, sizeof(p1), "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", cpu);
    std::snprintf(p2, sizeof(p2), "/sys/devices/system/cpu/cpu%d/cpufreq/scaling_max_freq", cpu);
    long khz = 0;
    read_long_file_first_of({p1, p2}, khz);
    return khz;
}

// Try to read a topology cluster id; returns -1 if unavailable
static int read_cluster_id(int cpu) {
    char p1[256], p2[256];
    std::snprintf(p1, sizeof(p1), "/sys/devices/system/cpu/cpu%d/topology/cluster_id", cpu);
    std::snprintf(p2, sizeof(p2), "/sys/devices/system/cpu/cpu%d/topology/core_group_id", cpu); // alt name on some kernels
    long id = -1;
    if (read_long_file(p1, id)) return (int)id;
    if (read_long_file(p2, id)) return (int)id;
    return -1;
}

struct Policy { int id; long max_khz; std::vector<int> cpus; };

static std::vector<Policy> read_policies() {
    std::vector<Policy> pols;
    for (int p=0; p<64; ++p) {
        char base[256];
        std::snprintf(base, sizeof(base), "/sys/devices/system/cpu/cpufreq/policy%d", p);
        std::string path_max1 = std::string(base) + "/cpuinfo_max_freq";
        std::string path_max2 = std::string(base) + "/scaling_max_freq";
        long maxf = 0;
        if (!read_long_file_first_of({path_max1, path_max2}, maxf)) continue;

        std::string rel = read_string_file(std::string(base) + "/related_cpus");
        if (rel.empty()) rel = read_string_file(std::string(base) + "/affected_cpus");
        if (rel.empty()) rel = read_string_file(std::string(base) + "/cpus");
        std::vector<int> cpus = parse_cpu_list(rel);
        if (cpus.empty()) continue;

        pols.push_back({p, maxf, cpus});
    }
    return pols;
}

// Preferred: cpufreq policies (clusters). Merge all near-top freq policies (big + prime).
static std::vector<int> detect_big_by_policy(long& out_top_policy_khz, std::vector<Policy>& out_pols) {
    out_pols = read_policies();
    if (out_pols.empty()) { out_top_policy_khz = 0; return {}; }

    long max_khz = 0;
    for (auto& pl : out_pols) if (pl.max_khz > max_khz) max_khz = pl.max_khz;
    out_top_policy_khz = max_khz;

    // Include policies within 0.80 of top (captures big + prime even when split aggressively)
    const double THRESH = 0.80;
    std::vector<int> union_cpus;
    for (auto& pl : out_pols) {
        if ((double)pl.max_khz >= (double)max_khz * THRESH) {
            union_cpus.insert(union_cpus.end(), pl.cpus.begin(), pl.cpus.end());
        }
    }

    // If we still have only the prime (single cpu) and there is a "next best" policy,
    // include the second policy CPUs unconditionally (typical 4-6 vs 7 split on SD865).
    if (union_cpus.size() <= 1 && out_pols.size() >= 2) {
        std::sort(out_pols.begin(), out_pols.end(), [](const Policy&a, const Policy&b){return a.max_khz > b.max_khz;});
        const Policy& second = out_pols[1];
        union_cpus.insert(union_cpus.end(), second.cpus.begin(), second.cpus.end());
    }

    if (!union_cpus.empty()) {
        std::sort(union_cpus.begin(), union_cpus.end());
        union_cpus.erase(std::unique(union_cpus.begin(), union_cpus.end()), union_cpus.end());
    }
    return union_cpus; // may still be [7] on some very odd kernels
}

// Fallback: per-CPU median
static std::vector<int> detect_big_by_median(const std::vector<Policy>& pols) {
    const int n = get_cpu_count_conf();
    std::vector<std::pair<int,long>> cores; cores.reserve(n);
    for (int cpu=0; cpu<n; ++cpu) {
        long khz = read_cpu_max_khz_sysfs(cpu);
        if (khz <= 0) {
            // fallback: policy max for the policy that contains this cpu
            for (const auto& pl : pols) {
                if (std::find(pl.cpus.begin(), pl.cpus.end(), cpu) != pl.cpus.end()) {
                    khz = pl.max_khz;
                    break;
                }
            }
        }
        if (khz <= 0) continue;
        cores.push_back({cpu, khz});
    }
    if (cores.empty()) return {};

    std::vector<long> freqs; freqs.reserve(cores.size());
    for (auto &c : cores) freqs.push_back(c.second);
    std::sort(freqs.begin(), freqs.end());
    long median = freqs[freqs.size()/2];

    std::vector<int> big;
    for (auto &c : cores) if (c.second >= median) big.push_back(c.first);
    if (big.empty()) {
        // very defensive: take upper half by index
        std::sort(cores.begin(), cores.end(), [](auto&a, auto&b){return a.second < b.second;});
        for (size_t i = cores.size()/2; i < cores.size(); ++i) big.push_back(cores[i].first);
    }
    std::sort(big.begin(), big.end());
    big.erase(std::unique(big.begin(), big.end()), big.end());
    return big;
}

// Expand a tentative big set using per-CPU/policy frequencies: include any CPU within 90% of global peak
static std::vector<int> expand_by_peak(const std::vector<int>& tentative, const std::vector<Policy>& pols) {
    const int n = get_cpu_count_conf();
    long peak = 0;
    std::vector<long> f(n, 0);
    // First try per-CPU sysfs (cpuinfo-first)
    for (int cpu=0; cpu<n; ++cpu) {
        f[cpu] = read_cpu_max_khz_sysfs(cpu);
        if (f[cpu] > peak) peak = f[cpu];
    }
    // Fill gaps using policy max for CPUs in that policy (covers ROMs exposing freq only for lead CPU)
    for (const auto& pl : pols) {
        for (int c : pl.cpus) {
            if (c >= 0 && c < n) {
                if (pl.max_khz > f[c]) f[c] = pl.max_khz;
                if (f[c] > peak) peak = f[c];
            }
        }
    }
    if (peak <= 0) return tentative;

    std::vector<int> out = tentative;
    const double TH = 0.90; // relaxed
    for (int cpu=0; cpu<n; ++cpu) {
        if (f[cpu] > 0 && (double)f[cpu] >= (double)peak * TH) out.push_back(cpu);
    }
    std::sort(out.begin(), out.end());
    out.erase(std::unique(out.begin(), out.end()), out.end());
    return out;
}

// Final Qualcomm-aware fallback using topology cluster_id of CPU7 (prime)
static std::vector<int> fallback_cluster_of_prime() {
    const int n = get_cpu_count_conf();
    if (n <= 7) return {};
    int prime = 7;
    int cid = read_cluster_id(prime);
    if (cid < 0) return {};
    std::vector<int> out;
    for (int c=0; c<n; ++c) {
        int ccid = read_cluster_id(c);
        if (ccid >= 0 && ccid == cid) out.push_back(c);
    }
    std::sort(out.begin(), out.end());
    out.erase(std::unique(out.begin(), out.end()), out.end());
    return out; // e.g., {4,5,6,7}
}

static std::vector<int> detect_big_uncached() {
    long top_policy_khz = 0;
    std::vector<Policy> pols;
    auto big = detect_big_by_policy(top_policy_khz, pols);

    // If result is degenerate (≤1), try expand via per-CPU/policy 90%-of-peak
    if (big.size() <= 1) {
        auto expanded = expand_by_peak(big, pols);
        if (expanded.size() > big.size()) return expanded;
    }
    if (!big.empty()) return big;

    // Fallbacks
    big = detect_big_by_median(pols);
    if (big.size() <= 1) big = expand_by_peak(big, pols);
    if (big.size() <= 1) big = fallback_cluster_of_prime();
    return big;
}

static const std::vector<int>& cached_big_cores() {
    static std::vector<int> cache;
    static bool inited = false;
    if (!inited) {
        cache = detect_big_uncached();
        inited = true;
    }
    return cache;
}
// === TID enumeration and thread name helpers ===
static std::vector<pid_t> list_all_tids_self() {
    std::vector<pid_t> tids;
    DIR* d = opendir("/proc/self/task");
    if (!d) return tids;
    struct dirent* ent;
    while ((ent = readdir(d)) != nullptr) {
        if (ent->d_name[0] == '.') continue;
        int tid = std::atoi(ent->d_name);
        if (tid > 0) tids.push_back((pid_t)tid);
    }
    closedir(d);
    return tids;
}

static std::string read_thread_name_comm(pid_t tid) {
    char path[256];
    std::snprintf(path, sizeof(path), "/proc/self/task/%d/comm", (int)tid);
    std::ifstream f(path);
    if (!f.good()) return "";
    std::string s; std::getline(f, s);
    while (!s.empty() && (s.back()=='\n' || s.back()=='\r')) s.pop_back();
    return s;
}
extern "C" JNIEXPORT jintArray JNICALL
Java_com_limelight_utils_CpuAffinity_nativeDetectBigCores(JNIEnv* env, jclass) {
    const auto& big = cached_big_cores();
    jintArray arr = env->NewIntArray((jsize)big.size());
    if (!arr) return nullptr;
    if (!big.empty()) env->SetIntArrayRegion(arr, 0, (jsize)big.size(), big.data());
    return arr;
}

// Build a mask that is the intersection of desired, allowed (cgroup/cpuset) and online CPUs
static void build_safe_affinity_mask(cpu_set_t* out, const std::vector<jint>& desired) {
    CPU_ZERO(out);
    // desired -> tmp mask
    cpu_set_t tmp;
    CPU_ZERO(&tmp);
    for (jint c : desired) if (c >= 0 && c < CPU_SETSIZE) CPU_SET((int)c, &tmp);

    // allowed mask from kernel
    cpu_set_t allowed;
    CPU_ZERO(&allowed);
    pid_t tid = gettid();
    (void)sched_getaffinity(tid, sizeof(cpu_set_t), &allowed); // ignore errors

    // online mask from sysfs (optional)
    cpu_set_t online;
    CPU_ZERO(&online);
    auto online_list = read_online_cpus();
    if (!online_list.empty()) {
        for (int c : online_list) if (c >= 0 && c < CPU_SETSIZE) CPU_SET(c, &online);
    } else {
        const int n = get_cpu_count_conf();
        for (int c = 0; c < n; ++c) CPU_SET(c, &online);
    }

    // intersection
    for (int c = 0; c < CPU_SETSIZE; ++c) {
        if (CPU_ISSET(c, &tmp) && CPU_ISSET(c, &online)) {
            if (!CPU_COUNT(&allowed) || CPU_ISSET(c, &allowed)) {
                CPU_SET(c, out);
            }
        }
    }
}


// --- Affinity helpers per TID (definition) ---
static void read_allowed_mask_for_tid(pid_t tid, cpu_set_t* allowed) {
    CPU_ZERO(allowed);
    (void)sched_getaffinity(tid, sizeof(cpu_set_t), allowed);
}

static std::vector<int> parse_cpu_list_simple(const std::string& sraw) {
    std::string s = sraw; for (char& c : s) if (c==',' || c=='\t') c = ' ';
    std::vector<int> out; std::istringstream iss(s); std::string tok;
    while (iss >> tok) {
        size_t dash = tok.find('-');
        if (dash != std::string::npos) {
            int a = std::atoi(tok.substr(0, dash).c_str());
            int b = std::atoi(tok.substr(dash+1).c_str());
            if (a > b) std::swap(a,b);
            for (int i=a;i<=b;++i) out.push_back(i);
        } else {
            out.push_back(std::atoi(tok.c_str()));
        }
    }
    std::sort(out.begin(), out.end()); out.erase(std::unique(out.begin(), out.end()), out.end());
    return out;
}

static std::vector<int> read_online_cpus_vec() {
    std::ifstream f("/sys/devices/system/cpu/online");
    if (f.good()) {
        std::string line; std::getline(f, line);
        return parse_cpu_list_simple(line);
    } else {
        long n = sysconf(_SC_NPROCESSORS_CONF); if (n <= 0) n = 8;
        std::vector<int> v; for (int i=0;i<n && i<CPU_SETSIZE;i++) v.push_back(i); return v;
    }
}

static void build_safe_affinity_mask_for_tid(pid_t tid, cpu_set_t* out, const std::vector<jint>& desired) {
    CPU_ZERO(out);
    cpu_set_t wanted; CPU_ZERO(&wanted);
    for (jint c : desired) if (c >= 0 && c < CPU_SETSIZE) CPU_SET((int)c, &wanted);

    cpu_set_t allowed; read_allowed_mask_for_tid(tid, &allowed);

    cpu_set_t online; CPU_ZERO(&online);
    auto o = read_online_cpus_vec();
    if (!o.empty()) { for (int c : o) if (c >= 0 && c < CPU_SETSIZE) CPU_SET(c, &online); }
    else {
        const int n = (int)sysconf(_SC_NPROCESSORS_CONF);
        for (int c=0; c<n && c<CPU_SETSIZE; ++c) CPU_SET(c, &online);
    }

    for (int c=0; c<CPU_SETSIZE; ++c) {
        if (CPU_ISSET(c, &wanted) && CPU_ISSET(c, &online)) {
            if (!CPU_COUNT(&allowed) || CPU_ISSET(c, &allowed)) CPU_SET(c, out);
        }
    }
}

/* removed duplicate int-overload: use jint version via small adapter at callsites */

extern "C" JNIEXPORT void JNICALL
Java_com_limelight_utils_CpuAffinity_nativeSetAffinity(JNIEnv* env, jclass, jintArray cores) {
    jsize n = env->GetArrayLength(cores);
    std::vector<jint> vec((size_t)n);
    env->GetIntArrayRegion(cores, 0, n, vec.data());

    cpu_set_t set;
    build_safe_affinity_mask(&set, vec);

    if (CPU_COUNT(&set) == 0) return; // no-op if nothing intersects
    pid_t tid = gettid(); // current thread id
    (void)sched_setaffinity(tid, sizeof(cpu_set_t), &set); // ignore error
}

extern "C" JNIEXPORT jint JNICALL
Java_com_limelight_utils_CpuAffinity_nativeGetCurrentCpu(JNIEnv*, jclass) {
#ifdef __linux__
    int cpu = sched_getcpu();
    return (cpu >= 0) ? cpu : -1;
#else
    return -1;
#endif
}


extern "C" JNIEXPORT jintArray JNICALL
Java_com_limelight_utils_CpuAffinity_nativeListTids(JNIEnv* env, jclass) {
    auto tids = list_all_tids_self();
    jintArray arr = env->NewIntArray((jsize)tids.size());
    if (!arr) return nullptr;
    if (!tids.empty()) {
        std::vector<jint> tmp; tmp.reserve(tids.size());
        for (pid_t t : tids) tmp.push_back((jint)t);
        env->SetIntArrayRegion(arr, 0, (jsize)tmp.size(), tmp.data());
    }
    return arr;
}


extern "C" JNIEXPORT jstring JNICALL
Java_com_limelight_utils_CpuAffinity_nativeReadThreadName(JNIEnv* env, jclass, jint tid) {
    std::string name = read_thread_name_comm((pid_t)tid);
    return env->NewStringUTF(name.c_str());
}


extern "C" JNIEXPORT void JNICALL
Java_com_limelight_utils_CpuAffinity_nativeSetAffinityForTid(JNIEnv* env, jclass, jint tid, jintArray cores) {
    jsize n = env->GetArrayLength(cores);
    std::vector<jint> desired((size_t)n);
    env->GetIntArrayRegion(cores, 0, n, desired.data());
    cpu_set_t set;
    build_safe_affinity_mask_for_tid((pid_t)tid, &set, desired);
    if (CPU_COUNT(&set) == 0) return;
    (void)sched_setaffinity((pid_t)tid, sizeof(cpu_set_t), &set);
}


extern "C" JNIEXPORT void JNICALL
Java_com_limelight_utils_CpuAffinity_nativeClearAffinityForTidAllOnline(JNIEnv*, jclass, jint tid) {
    std::vector<jint> desired;
    auto online_list = read_online_cpus();
    if (!online_list.empty()) { for (int c : online_list) desired.push_back(c); }
    else { const int n = get_cpu_count_conf(); for (int c=0; c<n; ++c) desired.push_back(c); }
    cpu_set_t set;
    build_safe_affinity_mask_for_tid((pid_t)tid, &set, desired);
    if (CPU_COUNT(&set) == 0) return;
    (void)sched_setaffinity((pid_t)tid, sizeof(cpu_set_t), &set);
}


// ===== Added JNI for mask read + all-threads helpers =====

// Small helper: convert cpu_set_t to compact ranges "0-3,6-7"
static std::string _format_mask_ranges(const cpu_set_t* set) {
    std::vector<int> v;
    for (int c = 0; c < CPU_SETSIZE; ++c) if (CPU_ISSET(c, set)) v.push_back(c);
    if (v.empty()) return "";
    std::ostringstream oss;
    int start = v[0], prev = v[0];
    for (size_t i = 1; i <= v.size(); ++i) {
        if (i < v.size() && v[i] == prev + 1) { prev = v[i]; continue; }
        if (oss.tellp() > 0) oss << ",";
        if (start == prev) oss << start; else oss << start << "-" << prev;
        if (i < v.size()) { start = prev = v[i]; }
    }
    return oss.str();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_limelight_utils_CpuAffinity_nativeReadAllowedCpuListForCurrentThread(JNIEnv* env, jclass) {
    cpu_set_t allowed; CPU_ZERO(&allowed);
    pid_t tid = gettid();
    (void)sched_getaffinity(tid, sizeof(cpu_set_t), &allowed);
    std::string s = _format_mask_ranges(&allowed);
    return env->NewStringUTF(s.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_limelight_utils_CpuAffinity_nativeClearCurrentThreadAffinityAllOnline(JNIEnv*, jclass) {
    std::vector<jint> desired;
    auto online_list = read_online_cpus();
    if (!online_list.empty()) { for (int c : online_list) desired.push_back(c); }
    else { const int n = get_cpu_count_conf(); for (int c=0; c<n; ++c) desired.push_back(c); }
    cpu_set_t set;
    build_safe_affinity_mask_for_tid(gettid(), &set, desired);
    if (CPU_COUNT(&set) == 0) return;
    (void)sched_setaffinity(gettid(), sizeof(cpu_set_t), &set);
}

extern "C" JNIEXPORT void JNICALL
Java_com_limelight_utils_CpuAffinity_nativePinAllThreadsToCores(JNIEnv* env, jclass, jintArray cores) {
    jsize n = env->GetArrayLength(cores);
    std::vector<jint> desired((size_t)n);
    env->GetIntArrayRegion(cores, 0, n, desired.data());
    auto tids = list_all_tids_self();
    for (pid_t tid : tids) {
        cpu_set_t set;
        build_safe_affinity_mask_for_tid(tid, &set, desired);
        if (CPU_COUNT(&set) == 0) continue;
        (void)sched_setaffinity(tid, sizeof(cpu_set_t), &set);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_limelight_utils_CpuAffinity_nativeClearAllThreadsAffinityAllOnline(JNIEnv*, jclass) {
    std::vector<jint> desired;
    auto online_list = read_online_cpus();
    if (!online_list.empty()) { for (int c : online_list) desired.push_back(c); }
    else { const int n = get_cpu_count_conf(); for (int c=0; c<n; ++c) desired.push_back(c); }

    auto tids = list_all_tids_self();
    for (pid_t tid : tids) {
        cpu_set_t set;
        build_safe_affinity_mask_for_tid(tid, &set, desired);
        if (CPU_COUNT(&set) == 0) continue;
        (void)sched_setaffinity(tid, sizeof(cpu_set_t), &set);
    }
}
