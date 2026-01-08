// Shizuku 兼容服务 - C++ 原生实现
// 直接用 libbinder_ndk 实现完整的 IShizukuService

#ifdef __ANDROID__

#include "shizuku_service.hpp"
#include "../core/ksucalls.hpp"
#include "../log.hpp"
#include "binder_wrapper.hpp"

#include <android/binder_parcel.h>
#include <fcntl.h>
#include <signal.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <unistd.h>
#include <cstdlib>
#include <cstring>
#include <fstream>

// 系统属性操作
extern "C" {
int __system_property_get(const char* name, char* value);
int __system_property_set(const char* name, const char* value);
}

namespace ksud {
namespace shizuku {

// 使用 murasaki 命名空间的 BinderWrapper
using murasaki::BinderWrapper;

// ==================== RemoteProcessHolder ====================

AIBinder_Class* RemoteProcessHolder::binderClass_ = nullptr;

RemoteProcessHolder::RemoteProcessHolder(pid_t pid, int stdin_fd, int stdout_fd, int stderr_fd)
    : pid_(pid),
      stdin_fd_(stdin_fd),
      stdout_fd_(stdout_fd),
      stderr_fd_(stderr_fd),
      exit_code_(-1),
      exited_(false) {
    // 创建 Binder class (只需一次)
    if (!binderClass_) {
        binderClass_ = AIBinder_Class_define(REMOTE_PROCESS_DESCRIPTOR, nullptr, nullptr,
                                             RemoteProcessHolder::onTransact);
    }

    // 创建 Binder 对象
    binder_ = AIBinder_new(binderClass_, this);
}

RemoteProcessHolder::~RemoteProcessHolder() {
    destroy();
    if (stdin_fd_ >= 0)
        close(stdin_fd_);
    if (stdout_fd_ >= 0)
        close(stdout_fd_);
    if (stderr_fd_ >= 0)
        close(stderr_fd_);
    if (binder_) {
        AIBinder_decStrong(binder_);
    }
}

int RemoteProcessHolder::getOutputStream() {
    return stdin_fd_;
}
int RemoteProcessHolder::getInputStream() {
    return stdout_fd_;
}
int RemoteProcessHolder::getErrorStream() {
    return stderr_fd_;
}

int RemoteProcessHolder::waitFor() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (exited_)
        return exit_code_;

    int status;
    if (waitpid(pid_, &status, 0) > 0) {
        exited_ = true;
        if (WIFEXITED(status)) {
            exit_code_ = WEXITSTATUS(status);
        } else if (WIFSIGNALED(status)) {
            exit_code_ = 128 + WTERMSIG(status);
        }
    }
    return exit_code_;
}

int RemoteProcessHolder::exitValue() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!exited_) {
        // 非阻塞检查
        int status;
        pid_t result = waitpid(pid_, &status, WNOHANG);
        if (result > 0) {
            exited_ = true;
            if (WIFEXITED(status)) {
                exit_code_ = WEXITSTATUS(status);
            } else if (WIFSIGNALED(status)) {
                exit_code_ = 128 + WTERMSIG(status);
            }
        }
    }
    return exited_ ? exit_code_ : -1;
}

void RemoteProcessHolder::destroy() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!exited_ && pid_ > 0) {
        kill(pid_, SIGKILL);
        int status;
        waitpid(pid_, &status, 0);
        exited_ = true;
        exit_code_ = 137;  // 128 + SIGKILL(9)
    }
}

bool RemoteProcessHolder::alive() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (exited_)
        return false;

    int status;
    pid_t result = waitpid(pid_, &status, WNOHANG);
    if (result > 0) {
        exited_ = true;
        if (WIFEXITED(status)) {
            exit_code_ = WEXITSTATUS(status);
        }
        return false;
    }
    return true;
}

bool RemoteProcessHolder::waitForTimeout(int64_t timeout_ms) {
    // 简化实现：轮询检查
    int64_t elapsed = 0;
    while (elapsed < timeout_ms) {
        if (!alive())
            return true;
        usleep(10000);  // 10ms
        elapsed += 10;
    }
    return !alive();
}

AIBinder* RemoteProcessHolder::getBinder() {
    return binder_;
}

binder_status_t RemoteProcessHolder::onTransact(AIBinder* binder, transaction_code_t code,
                                                const AParcel* in, AParcel* out) {
    auto* holder = static_cast<RemoteProcessHolder*>(AIBinder_getUserData(binder));
    if (!holder)
        return STATUS_UNEXPECTED_NULL;

    switch (code) {
    case TRANSACTION_getOutputStream: {
        int fd = holder->getOutputStream();
        // 返回 ParcelFileDescriptor - 需要 dup 一份
        int dupFd = dup(fd);
        AParcel_writeParcelFileDescriptor(out, dupFd);
        return STATUS_OK;
    }
    case TRANSACTION_getInputStream: {
        int fd = holder->getInputStream();
        int dupFd = dup(fd);
        AParcel_writeParcelFileDescriptor(out, dupFd);
        return STATUS_OK;
    }
    case TRANSACTION_getErrorStream: {
        int fd = holder->getErrorStream();
        int dupFd = dup(fd);
        AParcel_writeParcelFileDescriptor(out, dupFd);
        return STATUS_OK;
    }
    case TRANSACTION_waitFor: {
        int result = holder->waitFor();
        AParcel_writeInt32(out, result);
        return STATUS_OK;
    }
    case TRANSACTION_exitValue: {
        int result = holder->exitValue();
        AParcel_writeInt32(out, result);
        return STATUS_OK;
    }
    case TRANSACTION_destroy: {
        holder->destroy();
        return STATUS_OK;
    }
    case TRANSACTION_alive: {
        bool result = holder->alive();
        AParcel_writeBool(out, result);
        return STATUS_OK;
    }
    case TRANSACTION_waitForTimeout: {
        int64_t timeout;
        AParcel_readInt64(in, &timeout);
        // 忽略 unit 参数，假设是毫秒
        const char* unit = nullptr;
        AParcel_readString(in, &unit, nullptr);
        bool result = holder->waitForTimeout(timeout);
        AParcel_writeBool(out, result);
        return STATUS_OK;
    }
    default:
        return STATUS_UNKNOWN_TRANSACTION;
    }
}

// ==================== ShizukuService ====================

ShizukuService& ShizukuService::getInstance() {
    static ShizukuService instance;
    return instance;
}

ShizukuService::~ShizukuService() {
    stop();
    if (binder_) {
        AIBinder_decStrong(binder_);
    }
}

int ShizukuService::init() {
    if (binder_) {
        LOGW("ShizukuService already initialized");
        return 0;
    }

    LOGI("Initializing Shizuku compatible service...");

    // 初始化 Binder wrapper
    if (!BinderWrapper::instance().init()) {
        LOGE("Failed to init binder wrapper for Shizuku");
        return -1;
    }

    // 创建 Binder class
    binderClass_ =
        AIBinder_Class_define(SHIZUKU_DESCRIPTOR, nullptr, nullptr, ShizukuService::onTransact);

    if (!binderClass_) {
        LOGE("Failed to define Shizuku binder class");
        return -1;
    }

    // 创建 Binder 对象
    binder_ = AIBinder_new(binderClass_, this);
    if (!binder_) {
        LOGE("Failed to create Shizuku binder");
        return -1;
    }

    // 注册到 ServiceManager
    // Shizuku 使用 "user_service" 或直接注册为 "binder"
    // 我们注册为 moe.shizuku.server.IShizukuService (与 Sui 一致)
    auto addService = BinderWrapper::instance().AServiceManager_addService;
    if (!addService) {
        LOGE("AServiceManager_addService not available");
        return -1;
    }

    // 尝试多个服务名以提高兼容性
    const char* serviceNames[] = {
        "user_service",                        // Shizuku 标准名
        "moe.shizuku.server.IShizukuService",  // 完整描述符
    };

    bool registered = false;
    for (const char* name : serviceNames) {
        binder_status_t status = addService(binder_, name);
        if (status == STATUS_OK) {
            LOGI("Shizuku service registered as '%s'", name);
            registered = true;
        } else {
            LOGW("Failed to register as '%s': %d", name, status);
        }
    }

    if (!registered) {
        LOGE("Failed to register Shizuku service with any name");
        return -1;
    }

    return 0;
}

void ShizukuService::startThreadPool() {
    if (running_)
        return;
    running_ = true;

    // Binder 线程池已由 Murasaki 服务启动
    LOGI("Shizuku service ready");
}

void ShizukuService::stop() {
    running_ = false;
}

uid_t ShizukuService::getCallingUid() {
    return AIBinder_getCallingUid();
}

bool ShizukuService::checkCallerPermission(uid_t uid) {
    if (uid == 0 || uid == 2000)
        return true;  // root 和 shell

    // 检查 KSU allowlist
    // 复用 murasaki_binder.cpp 的逻辑
    const char* allowlist_path = "/data/adb/ksu/.allowlist";
    std::ifstream ifs(allowlist_path, std::ios::binary);
    if (!ifs)
        return false;

    uint32_t magic, version;
    ifs.read(reinterpret_cast<char*>(&magic), sizeof(magic));
    ifs.read(reinterpret_cast<char*>(&version), sizeof(version));

    if (magic != 0x7f4b5355)
        return false;

    // 简化：读取 profile 检查 uid
    struct {
        uint32_t version;
        char key[256];
        int32_t current_uid;
        uint8_t allow_su;
        char padding[3];
        char rest[512];  // root_profile 等
    } profile;

    while (ifs.read(reinterpret_cast<char*>(&profile), sizeof(profile))) {
        if (profile.current_uid == static_cast<int32_t>(uid) && profile.allow_su) {
            return true;
        }
    }

    // 检查本地权限缓存
    std::lock_guard<std::mutex> lock(permMutex_);
    auto it = permissions_.find(uid);
    return it != permissions_.end() && it->second;
}

void ShizukuService::allowUid(uid_t uid, bool allow) {
    std::lock_guard<std::mutex> lock(permMutex_);
    permissions_[uid] = allow;
}

ClientRecord* ShizukuService::findClient(uid_t uid, pid_t pid) {
    std::lock_guard<std::mutex> lock(clientsMutex_);
    uint64_t key = (static_cast<uint64_t>(uid) << 32) | pid;
    auto it = clients_.find(key);
    return it != clients_.end() ? it->second.get() : nullptr;
}

ClientRecord* ShizukuService::requireClient(uid_t uid, pid_t pid) {
    std::lock_guard<std::mutex> lock(clientsMutex_);
    uint64_t key = (static_cast<uint64_t>(uid) << 32) | pid;
    auto it = clients_.find(key);
    if (it != clients_.end()) {
        return it->second.get();
    }

    // 创建新记录
    auto record = std::make_unique<ClientRecord>();
    record->uid = uid;
    record->pid = pid;
    record->allowed = checkCallerPermission(uid);
    record->apiVersion = SHIZUKU_SERVER_VERSION;

    auto* ptr = record.get();
    clients_[key] = std::move(record);
    return ptr;
}

RemoteProcessHolder* ShizukuService::createProcess(const std::vector<std::string>& cmd,
                                                   const std::vector<std::string>& env,
                                                   const std::string& dir) {
    if (cmd.empty())
        return nullptr;

    // 创建管道
    int stdin_pipe[2], stdout_pipe[2], stderr_pipe[2];
    if (pipe(stdin_pipe) < 0 || pipe(stdout_pipe) < 0 || pipe(stderr_pipe) < 0) {
        LOGE("Failed to create pipes");
        return nullptr;
    }

    pid_t pid = fork();
    if (pid < 0) {
        LOGE("Failed to fork");
        close(stdin_pipe[0]);
        close(stdin_pipe[1]);
        close(stdout_pipe[0]);
        close(stdout_pipe[1]);
        close(stderr_pipe[0]);
        close(stderr_pipe[1]);
        return nullptr;
    }

    if (pid == 0) {
        // 子进程
        close(stdin_pipe[1]);   // 关闭写端
        close(stdout_pipe[0]);  // 关闭读端
        close(stderr_pipe[0]);  // 关闭读端

        dup2(stdin_pipe[0], STDIN_FILENO);
        dup2(stdout_pipe[1], STDOUT_FILENO);
        dup2(stderr_pipe[1], STDERR_FILENO);

        close(stdin_pipe[0]);
        close(stdout_pipe[1]);
        close(stderr_pipe[1]);

        // 切换工作目录
        if (!dir.empty()) {
            chdir(dir.c_str());
        }

        // 设置环境变量
        for (const auto& e : env) {
            putenv(strdup(e.c_str()));
        }

        // 准备参数
        std::vector<char*> argv;
        for (const auto& c : cmd) {
            argv.push_back(strdup(c.c_str()));
        }
        argv.push_back(nullptr);

        // 执行
        execvp(argv[0], argv.data());
        _exit(127);
    }

    // 父进程
    close(stdin_pipe[0]);   // 关闭读端
    close(stdout_pipe[1]);  // 关闭写端
    close(stderr_pipe[1]);  // 关闭写端

    return new RemoteProcessHolder(pid, stdin_pipe[1], stdout_pipe[0], stderr_pipe[0]);
}

// ==================== Transaction Handler ====================

binder_status_t ShizukuService::onTransact(AIBinder* binder, transaction_code_t code,
                                           const AParcel* in, AParcel* out) {
    auto* service = static_cast<ShizukuService*>(AIBinder_getUserData(binder));
    if (!service)
        return STATUS_UNEXPECTED_NULL;

    LOGD("Shizuku transaction: code=%d, uid=%d", code, AIBinder_getCallingUid());

    switch (code) {
    case TRANSACTION_getVersion:
        return service->handleGetVersion(in, out);
    case TRANSACTION_getUid:
        return service->handleGetUid(in, out);
    case TRANSACTION_checkPermission:
        return service->handleCheckPermission(in, out);
    case TRANSACTION_newProcess:
        return service->handleNewProcess(in, out);
    case TRANSACTION_getSELinuxContext:
        return service->handleGetSELinuxContext(in, out);
    case TRANSACTION_getSystemProperty:
        return service->handleGetSystemProperty(in, out);
    case TRANSACTION_setSystemProperty:
        return service->handleSetSystemProperty(in, out);
    case TRANSACTION_checkSelfPermission:
        return service->handleCheckSelfPermission(in, out);
    case TRANSACTION_requestPermission:
        return service->handleRequestPermission(in, out);
    case TRANSACTION_attachApplication:
        return service->handleAttachApplication(in, out);
    case TRANSACTION_exit:
        return service->handleExit(in, out);
    case TRANSACTION_isHidden:
        return service->handleIsHidden(in, out);
    case TRANSACTION_getFlagsForUid:
        return service->handleGetFlagsForUid(in, out);
    case TRANSACTION_updateFlagsForUid:
        return service->handleUpdateFlagsForUid(in, out);
    default:
        LOGW("Unknown Shizuku transaction: %d", code);
        return STATUS_UNKNOWN_TRANSACTION;
    }
}

// ==================== Handler Implementations ====================

binder_status_t ShizukuService::handleGetVersion(const AParcel* in, AParcel* out) {
    (void)in;
    uid_t uid = getCallingUid();
    if (!checkCallerPermission(uid)) {
        LOGW("getVersion: permission denied for uid %d", uid);
        // 仍然返回版本，但记录警告
    }
    AParcel_writeInt32(out, SHIZUKU_SERVER_VERSION);
    return STATUS_OK;
}

binder_status_t ShizukuService::handleGetUid(const AParcel* in, AParcel* out) {
    (void)in;
    AParcel_writeInt32(out, getuid());
    return STATUS_OK;
}

binder_status_t ShizukuService::handleCheckPermission(const AParcel* in, AParcel* out) {
    const char* permission = nullptr;
    AParcel_readString(in, &permission, nullptr);

    // 简化实现：返回 PERMISSION_GRANTED (0)
    AParcel_writeInt32(out, 0);
    return STATUS_OK;
}

binder_status_t ShizukuService::handleNewProcess(const AParcel* in, AParcel* out) {
    uid_t uid = getCallingUid();

    if (!checkCallerPermission(uid)) {
        LOGE("newProcess: permission denied for uid %d", uid);
        return STATUS_PERMISSION_DENIED;
    }

    // 读取命令数组
    int32_t cmdCount;
    AParcel_readInt32(in, &cmdCount);
    std::vector<std::string> cmd;
    for (int32_t i = 0; i < cmdCount; i++) {
        const char* str = nullptr;
        AParcel_readString(in, &str, nullptr);
        if (str)
            cmd.push_back(str);
    }

    // 读取环境变量数组
    int32_t envCount;
    AParcel_readInt32(in, &envCount);
    std::vector<std::string> env;
    for (int32_t i = 0; i < envCount; i++) {
        const char* str = nullptr;
        AParcel_readString(in, &str, nullptr);
        if (str)
            env.push_back(str);
    }

    // 读取工作目录
    const char* dir = nullptr;
    AParcel_readString(in, &dir, nullptr);

    LOGI("newProcess: cmd[0]=%s, uid=%d", cmd.empty() ? "(empty)" : cmd[0].c_str(), uid);

    // 创建进程
    auto* holder = createProcess(cmd, env, dir ? dir : "");
    if (!holder) {
        LOGE("Failed to create process");
        return STATUS_FAILED_TRANSACTION;
    }

    // 返回 IRemoteProcess binder
    AParcel_writeStrongBinder(out, holder->getBinder());

    return STATUS_OK;
}

binder_status_t ShizukuService::handleGetSELinuxContext(const AParcel* in, AParcel* out) {
    (void)in;

    char context[256] = {0};
    FILE* f = fopen("/proc/self/attr/current", "r");
    if (f) {
        fread(context, 1, sizeof(context) - 1, f);
        fclose(f);
        // 去掉换行
        size_t len = strlen(context);
        if (len > 0 && context[len - 1] == '\n') {
            context[len - 1] = '\0';
        }
    }

    AParcel_writeString(out, context, strlen(context));
    return STATUS_OK;
}

binder_status_t ShizukuService::handleGetSystemProperty(const AParcel* in, AParcel* out) {
    const char* name = nullptr;
    const char* defaultValue = nullptr;
    AParcel_readString(in, &name, nullptr);
    AParcel_readString(in, &defaultValue, nullptr);

    char value[92] = {0};
    if (name && __system_property_get(name, value) > 0) {
        AParcel_writeString(out, value, strlen(value));
    } else {
        AParcel_writeString(out, defaultValue ? defaultValue : "",
                            defaultValue ? strlen(defaultValue) : 0);
    }
    return STATUS_OK;
}

binder_status_t ShizukuService::handleSetSystemProperty(const AParcel* in, AParcel* out) {
    (void)out;

    uid_t uid = getCallingUid();
    if (!checkCallerPermission(uid)) {
        return STATUS_PERMISSION_DENIED;
    }

    const char* name = nullptr;
    const char* value = nullptr;
    AParcel_readString(in, &name, nullptr);
    AParcel_readString(in, &value, nullptr);

    if (name && value) {
        __system_property_set(name, value);
    }
    return STATUS_OK;
}

binder_status_t ShizukuService::handleCheckSelfPermission(const AParcel* in, AParcel* out) {
    (void)in;
    uid_t uid = getCallingUid();
    bool allowed = checkCallerPermission(uid);
    AParcel_writeBool(out, allowed);
    return STATUS_OK;
}

binder_status_t ShizukuService::handleRequestPermission(const AParcel* in, AParcel* out) {
    (void)out;

    int32_t requestCode;
    AParcel_readInt32(in, &requestCode);

    uid_t uid = getCallingUid();
    pid_t pid = AIBinder_getCallingPid();

    // 自动授权 KSU 白名单中的 App
    if (checkCallerPermission(uid)) {
        LOGI("Auto-granting permission for uid %d (in KSU allowlist)", uid);
        auto* client = requireClient(uid, pid);
        client->allowed = true;
    } else {
        LOGW("Permission request from non-root app uid=%d, denied", uid);
    }

    return STATUS_OK;
}

binder_status_t ShizukuService::handleAttachApplication(const AParcel* in, AParcel* out) {
    (void)out;

    AIBinder* appBinder = nullptr;
    AParcel_readStrongBinder(in, &appBinder);

    // 读取 Bundle args (简化处理)
    // Bundle 序列化比较复杂，这里只记录客户端

    uid_t uid = getCallingUid();
    pid_t pid = AIBinder_getCallingPid();

    auto* client = requireClient(uid, pid);
    client->applicationBinder = appBinder;

    LOGI("attachApplication: uid=%d, pid=%d, allowed=%d", uid, pid, client->allowed);

    return STATUS_OK;
}

binder_status_t ShizukuService::handleExit(const AParcel* in, AParcel* out) {
    (void)in;
    (void)out;

    uid_t uid = getCallingUid();
    if (uid != 0 && uid != 2000) {
        LOGW("exit called by non-root uid %d, ignoring", uid);
        return STATUS_OK;
    }

    LOGI("Shizuku service exit requested");
    stop();
    return STATUS_OK;
}

binder_status_t ShizukuService::handleIsHidden(const AParcel* in, AParcel* out) {
    int32_t uid;
    AParcel_readInt32(in, &uid);

    // 简化：所有 App 都不隐藏
    AParcel_writeBool(out, false);
    return STATUS_OK;
}

binder_status_t ShizukuService::handleGetFlagsForUid(const AParcel* in, AParcel* out) {
    int32_t uid, mask;
    AParcel_readInt32(in, &uid);
    AParcel_readInt32(in, &mask);

    // 简化实现
    AParcel_writeInt32(out, 0);
    return STATUS_OK;
}

binder_status_t ShizukuService::handleUpdateFlagsForUid(const AParcel* in, AParcel* out) {
    (void)out;

    int32_t uid, mask, value;
    AParcel_readInt32(in, &uid);
    AParcel_readInt32(in, &mask);
    AParcel_readInt32(in, &value);

    // 简化实现：忽略
    return STATUS_OK;
}

// ==================== 启动函数 ====================

void start_shizuku_service() {
    auto& service = ShizukuService::getInstance();
    if (service.init() == 0) {
        service.startThreadPool();
        LOGI("Shizuku compatible service started");
    } else {
        LOGE("Failed to start Shizuku service");
    }
}

}  // namespace shizuku
}  // namespace ksud

#endif // #ifdef __ANDROID__
