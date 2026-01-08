// Murasaki IPC Protocol Definition
// 定义 App 和 ksud 之间的通信协议

#pragma once

#include <cstdint>
#include <cstring>

namespace ksud {
namespace murasaki {

// 协议版本
static constexpr uint32_t MURASAKI_PROTOCOL_VERSION = 1;

// 魔数标识
static constexpr uint32_t MURASAKI_MAGIC = 0x4D525341;  // "MRSA"

// 最大数据包大小
static constexpr size_t MAX_PACKET_SIZE = 64 * 1024;  // 64KB

// 命令类型
enum class Command : uint32_t {
    // 基础信息
    GET_VERSION = 1,
    GET_KSU_VERSION = 2,
    GET_PRIVILEGE_LEVEL = 3,
    IS_KERNEL_MODE_AVAILABLE = 4,

    // SELinux
    GET_SELINUX_CONTEXT = 10,
    SET_SELINUX_CONTEXT = 11,

    // HymoFS
    HYMO_ADD_RULE = 20,
    HYMO_ADD_MERGE_RULE = 21,
    HYMO_DELETE_RULE = 22,
    HYMO_CLEAR_RULES = 23,
    HYMO_GET_ACTIVE_RULES = 24,
    HYMO_SET_STEALTH = 25,
    HYMO_SET_DEBUG = 26,
    HYMO_SET_MIRROR_PATH = 27,
    HYMO_FIX_MOUNTS = 28,
    HYMO_HIDE_PATH = 29,
    HYMO_HIDE_OVERLAY_XATTRS = 30,

    // KSU 操作
    GET_APP_PROFILE = 40,
    SET_APP_PROFILE = 41,
    IS_UID_GRANTED_ROOT = 42,
    SHOULD_UMOUNT_FOR_UID = 43,
    INJECT_SEPOLICY = 44,
    ADD_TRY_UMOUNT = 45,
    NUKE_EXT4_SYSFS = 46,

    // 进程执行 (Shizuku 兼容)
    NEW_PROCESS = 100,

    // 权限
    REQUEST_PERMISSION = 200,
    CHECK_PERMISSION = 201,
};

// 请求头
struct RequestHeader {
    uint32_t magic;      // MURASAKI_MAGIC
    uint32_t version;    // 协议版本
    uint32_t cmd;        // Command
    uint32_t seq;        // 序列号
    uint32_t data_size;  // 数据长度
    uint32_t reserved;

    void init(Command c, uint32_t sequence, uint32_t size) {
        magic = MURASAKI_MAGIC;
        version = MURASAKI_PROTOCOL_VERSION;
        cmd = static_cast<uint32_t>(c);
        seq = sequence;
        data_size = size;
        reserved = 0;
    }

    bool is_valid() const {
        return magic == MURASAKI_MAGIC && version == MURASAKI_PROTOCOL_VERSION &&
               data_size <= MAX_PACKET_SIZE;
    }
};

// 响应头
struct ResponseHeader {
    uint32_t magic;      // MURASAKI_MAGIC
    uint32_t seq;        // 对应请求的序列号
    int32_t result;      // 0 成功，负数错误码
    uint32_t data_size;  // 数据长度

    void init(uint32_t sequence, int32_t res, uint32_t size) {
        magic = MURASAKI_MAGIC;
        seq = sequence;
        result = res;
        data_size = size;
    }
};

// ==================== 请求数据结构 ====================

// HymoFS 添加规则
struct HymoAddRuleRequest {
    char src[256];
    char target[256];
    int32_t type;

    void set(const char* s, const char* t, int ty) {
        strncpy(src, s, sizeof(src) - 1);
        strncpy(target, t, sizeof(target) - 1);
        type = ty;
    }
};

// HymoFS 设置布尔值
struct HymoSetBoolRequest {
    int32_t value;
};

// HymoFS 设置路径
struct HymoSetPathRequest {
    char path[256];
};

// SELinux 上下文
struct SelinuxContextRequest {
    int32_t pid;  // 0 表示当前进程
};

struct SelinuxContextResponse {
    char context[256];
};

// UID 查询
struct UidRequest {
    int32_t uid;
};

struct BoolResponse {
    int32_t value;
};

// App Profile
struct AppProfileRequest {
    int32_t uid;
    char profile_json[4096];  // JSON 格式
};

// SEPolicy 注入
struct SepolicyRequest {
    char rules[4096];
};

// 字符串响应
struct StringResponse {
    uint32_t length;
    char data[1];  // 变长数据
};

}  // namespace murasaki
}  // namespace ksud
