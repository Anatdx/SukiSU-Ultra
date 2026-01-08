// Murasaki Service - Binder Service Implementation
// KernelSU 内核级 API 服务端

#include "murasaki_service.hpp"
#include "../core/ksucalls.hpp"
#include "../defs.hpp"
#include "../hymo/hymo_utils.hpp"
#include "../hymo/mount/hymofs.hpp"
#include "../log.hpp"
#include "../profile/profile.hpp"
#include "../sepolicy/sepolicy.hpp"
#include "../umount.hpp"

#include <poll.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>
#include <atomic>
#include <mutex>
#include <thread>

namespace ksud {
namespace murasaki {

// Murasaki 服务版本
static constexpr int MURASAKI_VERSION = 1;

// Unix socket 路径 (用于初期实现，后续可换成真正的 Binder)
static constexpr const char* MURASAKI_SOCKET_PATH = "/dev/socket/murasaki";

// 全局服务实例
static std::atomic<bool> g_service_running{false};
static std::thread g_service_thread;
static std::mutex g_service_mutex;

// ==================== 辅助函数 ====================

static bool is_ksu_available() {
    return get_version() > 0;
}

static int get_ksu_version() {
    return get_version();
}

static bool is_uid_granted_root(int uid) {
    // TODO: 通过 ioctl 检查
    // 暂时简单实现：检查是否 root
    return uid == 0;
}

static bool is_uid_should_umount(int uid) {
    // TODO: 通过 ioctl 检查
    return false;
}

static bool apply_sepolicy_rules(const std::string& rules) {
    // TODO: 调用 sepolicy 模块
    return false;
}

static bool nuke_ext4_sysfs() {
    return hymo::ksu_nuke_sysfs("") == true;
}

// ==================== MurasakiService 实现 ====================

MurasakiService& MurasakiService::getInstance() {
    static MurasakiService instance;
    return instance;
}

int MurasakiService::init() {
    if (initialized_) {
        LOGW("MurasakiService already initialized");
        return 0;
    }

    LOGI("MurasakiService: Initializing...");

    // 检查 KernelSU 是否可用
    if (!is_ksu_available()) {
        LOGE("MurasakiService: KernelSU not available!");
        return -1;
    }

    // TODO: 实际的 Binder 注册
    // 目前先用 Unix socket 作为过渡方案
    // 后续可以切换到 libbinder

    initialized_ = true;
    LOGI("MurasakiService: Initialized successfully");
    return 0;
}

void MurasakiService::run() {
    if (!initialized_) {
        LOGE("MurasakiService: Not initialized!");
        return;
    }

    running_ = true;
    LOGI("MurasakiService: Starting service loop...");

    // TODO: 实际的 Binder 消息循环
    // IPCThreadState::self()->joinThreadPool();

    // 临时：简单的等待循环
    while (running_) {
        std::this_thread::sleep_for(std::chrono::seconds(1));
    }

    LOGI("MurasakiService: Service loop ended");
}

void MurasakiService::stop() {
    running_ = false;
}

bool MurasakiService::isRunning() const {
    return running_;
}

// ==================== 服务接口实现 ====================

int MurasakiService::getVersion() {
    return MURASAKI_VERSION;
}

int MurasakiService::getKernelSuVersion() {
    return get_ksu_version();
}

PrivilegeLevel MurasakiService::getPrivilegeLevel(int callingUid) {
    // 检查是否有 Root 权限
    if (is_uid_granted_root(callingUid)) {
        // 进一步检查是否支持内核级
        if (is_ksu_available() && HymoFS::check_status() == HymoFS::Status::Available) {
            return PrivilegeLevel::KERNEL;
        }
        return PrivilegeLevel::ROOT;
    }
    return PrivilegeLevel::SHELL;
}

bool MurasakiService::isKernelModeAvailable() {
    return is_ksu_available() && HymoFS::check_status() == HymoFS::Status::Available;
}

std::string MurasakiService::getSelinuxContext(int pid) {
    char buf[256] = {0};
    std::string path = "/proc/" + std::to_string(pid == 0 ? getpid() : pid) + "/attr/current";

    FILE* f = fopen(path.c_str(), "r");
    if (f) {
        if (fgets(buf, sizeof(buf), f)) {
            // 去掉换行符
            char* nl = strchr(buf, '\n');
            if (nl)
                *nl = '\0';
        }
        fclose(f);
    }
    return std::string(buf);
}

int MurasakiService::setSelinuxContext(const std::string& context) {
    // 需要内核支持
    // TODO: 通过 ioctl 设置
    LOGW("MurasakiService::setSelinuxContext not implemented yet");
    return -ENOSYS;
}

// ==================== HymoFS 操作 ====================

int MurasakiService::hymoAddRule(const std::string& src, const std::string& target, int type) {
    return HymoFS::add_rule(src, target, type) ? 0 : -1;
}

int MurasakiService::hymoClearRules() {
    return HymoFS::clear_rules() ? 0 : -1;
}

int MurasakiService::hymoSetStealth(bool enable) {
    return HymoFS::set_stealth(enable) ? 0 : -1;
}

int MurasakiService::hymoSetDebug(bool enable) {
    return HymoFS::set_debug(enable) ? 0 : -1;
}

int MurasakiService::hymoSetMirrorPath(const std::string& path) {
    return HymoFS::set_mirror_path(path) ? 0 : -1;
}

int MurasakiService::hymoFixMounts() {
    return HymoFS::fix_mounts() ? 0 : -1;
}

std::string MurasakiService::hymoGetActiveRules() {
    auto result = HymoFS::get_active_rules();
    if (std::holds_alternative<std::string>(result)) {
        return std::get<std::string>(result);
    }
    return "";
}

// ==================== KSU 操作 ====================

std::string MurasakiService::getAppProfile(int uid) {
    // TODO: 从 profile 模块获取
    return "";
}

int MurasakiService::setAppProfile(int uid, const std::string& profileJson) {
    // TODO: 设置 profile
    return -ENOSYS;
}

bool MurasakiService::isUidGrantedRoot(int uid) {
    return is_uid_granted_root(uid);
}

bool MurasakiService::shouldUmountForUid(int uid) {
    return is_uid_should_umount(uid);
}

int MurasakiService::injectSepolicy(const std::string& rules) {
    return apply_sepolicy_rules(rules) ? 0 : -1;
}

int MurasakiService::addTryUmount(const std::string& path) {
    // TODO: 实现
    return -ENOSYS;
}

int MurasakiService::nukeExt4Sysfs() {
    return nuke_ext4_sysfs() ? 0 : -1;
}

// ==================== 全局函数 ====================

void start_murasaki_service_async() {
    std::lock_guard<std::mutex> lock(g_service_mutex);

    if (g_service_running.load()) {
        LOGW("Murasaki service already running");
        return;
    }

    g_service_thread = std::thread([]() {
        auto& service = MurasakiService::getInstance();
        if (service.init() == 0) {
            g_service_running.store(true);
            service.run();
        }
        g_service_running.store(false);
    });

    // 分离线程，让它在后台运行
    g_service_thread.detach();

    LOGI("Murasaki service started in background");
}

void stop_murasaki_service() {
    std::lock_guard<std::mutex> lock(g_service_mutex);

    if (!g_service_running.load()) {
        return;
    }

    MurasakiService::getInstance().stop();
    LOGI("Murasaki service stopped");
}

bool is_murasaki_service_available() {
    return g_service_running.load();
}

}  // namespace murasaki
}  // namespace ksud
