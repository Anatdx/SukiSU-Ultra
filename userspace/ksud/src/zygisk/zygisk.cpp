/**
 * YukiSU Zygisk Support Implementation
 *
 * Uses kernel IOCTL to detect zygote and coordinate injection.
 */

#include "zygisk.hpp"
#include "../core/ksucalls.hpp"
#include "../defs.hpp"
#include "../log.hpp"

#include <atomic>
#include <cerrno>
#include <cstring>
#include <thread>

#include <fcntl.h>
#include <linux/ioctl.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <unistd.h>

namespace ksud {
namespace zygisk {

// IOCTL definitions (must match kernel/supercalls.h)
#define KSU_IOCTL_ZYGISK_WAIT_ZYGOTE _IOC(_IOC_READ, 'K', 120, 0)
#define KSU_IOCTL_ZYGISK_RESUME_ZYGOTE _IOC(_IOC_WRITE, 'K', 121, 0)
#define KSU_IOCTL_ZYGISK_ENABLE _IOC(_IOC_WRITE, 'K', 122, 0)

struct ksu_zygisk_wait_cmd {
    int32_t pid;
    uint8_t is_64bit;
    uint32_t timeout_ms;
} __attribute__((packed));

struct ksu_zygisk_resume_cmd {
    int32_t pid;
} __attribute__((packed));

struct ksu_zygisk_enable_cmd {
    uint8_t enable;
} __attribute__((packed));

// Tracer paths
static constexpr const char* TRACER_PATH_64 = "/data/adb/yukizygisk/bin/zygisk-ptrace64";
static constexpr const char* TRACER_PATH_32 = "/data/adb/yukizygisk/bin/zygisk-ptrace32";

// State
static std::atomic<bool> g_enabled{false};
static std::atomic<bool> g_running{false};
static std::thread g_monitor_thread;

// Enable zygisk in kernel
static bool kernel_enable_zygisk(int ksu_fd, bool enable) {
    ksu_zygisk_enable_cmd cmd = {static_cast<uint8_t>(enable ? 1 : 0)};
    int ret = ioctl(ksu_fd, KSU_IOCTL_ZYGISK_ENABLE, &cmd);
    if (ret < 0) {
        LOGE("IOCTL ZYGISK_ENABLE failed: %s", strerror(errno));
        return false;
    }
    LOGI("Zygisk %s in kernel", enable ? "enabled" : "disabled");
    return true;
}

// Wait for zygote
static bool kernel_wait_zygote(int ksu_fd, int* pid, bool* is_64bit, uint32_t timeout_ms) {
    ksu_zygisk_wait_cmd cmd;
    cmd.pid = 0;
    cmd.is_64bit = 0;
    cmd.timeout_ms = timeout_ms;

    int ret = ioctl(ksu_fd, KSU_IOCTL_ZYGISK_WAIT_ZYGOTE, &cmd);
    if (ret < 0) {
        if (errno == ETIMEDOUT) {
            return false;
        }
        LOGE("IOCTL ZYGISK_WAIT_ZYGOTE failed: %s", strerror(errno));
        return false;
    }

    *pid = cmd.pid;
    *is_64bit = cmd.is_64bit != 0;
    return true;
}

// Resume zygote
static bool kernel_resume_zygote(int ksu_fd, int pid) {
    ksu_zygisk_resume_cmd cmd;
    cmd.pid = pid;

    int ret = ioctl(ksu_fd, KSU_IOCTL_ZYGISK_RESUME_ZYGOTE, &cmd);
    if (ret < 0) {
        LOGE("IOCTL ZYGISK_RESUME_ZYGOTE failed: %s", strerror(errno));
        return false;
    }
    return true;
}

// Spawn tracer to inject
static void spawn_tracer(int target_pid, bool is_64bit) {
    const char* tracer = is_64bit ? TRACER_PATH_64 : TRACER_PATH_32;

    if (access(tracer, X_OK) != 0) {
        LOGE("Tracer not found: %s", tracer);
        return;
    }

    LOGI("Spawning tracer for zygote pid=%d (%s)", target_pid, is_64bit ? "64-bit" : "32-bit");

    pid_t pid = fork();
    if (pid == 0) {
        char pid_str[16];
        snprintf(pid_str, sizeof(pid_str), "%d", target_pid);
        execl(tracer, "zygisk-ptrace", "trace", pid_str, nullptr);
        LOGE("execl tracer failed: %s", strerror(errno));
        _exit(1);
    } else if (pid > 0) {
        int status;
        waitpid(pid, &status, 0);
        if (WIFEXITED(status) && WEXITSTATUS(status) == 0) {
            LOGI("Tracer completed successfully");
        } else {
            LOGE("Tracer failed with status %d", status);
        }
    } else {
        LOGE("fork failed: %s", strerror(errno));
    }
}

// Monitor thread function
static void monitor_thread_func() {
    LOGI("Zygisk monitor thread started");

    // Get KSU fd (ksud already has it via ksucalls)
    int ksu_fd = ksu_get_fd();
    if (ksu_fd < 0) {
        LOGE("Cannot get KSU fd, zygisk disabled");
        return;
    }

    // Enable zygisk in kernel
    if (!kernel_enable_zygisk(ksu_fd, true)) {
        LOGE("Failed to enable zygisk in kernel");
        return;
    }

    g_running = true;

    while (g_running && g_enabled) {
        int zygote_pid;
        bool is_64bit;

        // Wait for kernel to detect and pause zygote
        // Use 5 second timeout so we can check g_running periodically
        if (!kernel_wait_zygote(ksu_fd, &zygote_pid, &is_64bit, 5000)) {
            continue;
        }

        LOGI("Kernel detected zygote: pid=%d is_64bit=%d", zygote_pid, is_64bit);

        // Inject
        spawn_tracer(zygote_pid, is_64bit);

        // Resume zygote
        kernel_resume_zygote(ksu_fd, zygote_pid);
    }

    // Disable zygisk in kernel
    kernel_enable_zygisk(ksu_fd, false);

    LOGI("Zygisk monitor thread stopped");
}

void start_zygisk_monitor() {
    if (g_monitor_thread.joinable()) {
        LOGW("Zygisk monitor already running");
        return;
    }

    // Check if zygisk files exist
    if (access(TRACER_PATH_64, X_OK) != 0 && access(TRACER_PATH_32, X_OK) != 0) {
        LOGI("Zygisk tracer not found, zygisk support disabled");
        return;
    }

    g_enabled = true;
    g_monitor_thread = std::thread(monitor_thread_func);
    LOGI("Zygisk monitor started");
}

void stop_zygisk_monitor() {
    g_enabled = false;
    g_running = false;

    if (g_monitor_thread.joinable()) {
        g_monitor_thread.join();
    }

    LOGI("Zygisk monitor stopped");
}

bool is_enabled() {
    return g_enabled;
}

void set_enabled(bool enable) {
    g_enabled = enable;
}

}  // namespace zygisk
}  // namespace ksud
