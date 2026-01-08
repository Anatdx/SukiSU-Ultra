// Murasaki Client - JNI Implementation
// 用于管理器直接与 ksud 通信

#include <android/log.h>
#include <errno.h>
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

#define LOG_TAG "MurasakiClient"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// 协议定义 (与 ksud 端一致)
#define MURASAKI_MAGIC 0x4D525341
#define MURASAKI_PROTOCOL_VERSION 1
#define MAX_PACKET_SIZE (64 * 1024)

// 命令定义
enum MurasakiCommand {
  CMD_GET_VERSION = 1,
  CMD_GET_KSU_VERSION = 2,
  CMD_GET_PRIVILEGE_LEVEL = 3,
  CMD_IS_KERNEL_MODE_AVAILABLE = 4,

  CMD_GET_SELINUX_CONTEXT = 10,
  CMD_SET_SELINUX_CONTEXT = 11,

  CMD_HYMO_ADD_RULE = 20,
  CMD_HYMO_ADD_MERGE_RULE = 21,
  CMD_HYMO_DELETE_RULE = 22,
  CMD_HYMO_CLEAR_RULES = 23,
  CMD_HYMO_GET_ACTIVE_RULES = 24,
  CMD_HYMO_SET_STEALTH = 25,
  CMD_HYMO_SET_DEBUG = 26,
  CMD_HYMO_SET_MIRROR_PATH = 27,
  CMD_HYMO_FIX_MOUNTS = 28,

  CMD_IS_UID_GRANTED_ROOT = 42,
  CMD_SHOULD_UMOUNT_FOR_UID = 43,
  CMD_NUKE_EXT4_SYSFS = 46,
};

// 请求头
typedef struct {
  uint32_t magic;
  uint32_t version;
  uint32_t cmd;
  uint32_t seq;
  uint32_t data_size;
  uint32_t reserved;
} RequestHeader;

// 响应头
typedef struct {
  uint32_t magic;
  uint32_t seq;
  int32_t result;
  uint32_t data_size;
} ResponseHeader;

// Socket 路径 (抽象命名空间)
static const char *SOCKET_PATH = "\0murasaki";

// 全局连接 (单例)
static int g_socket_fd = -1;
static uint32_t g_seq = 0;

// 连接到服务
static int murasaki_connect(void) {
  if (g_socket_fd >= 0) {
    return 0; // 已连接
  }

  g_socket_fd = socket(AF_UNIX, SOCK_STREAM, 0);
  if (g_socket_fd < 0) {
    LOGE("Failed to create socket: %s", strerror(errno));
    return -errno;
  }

  struct sockaddr_un addr;
  memset(&addr, 0, sizeof(addr));
  addr.sun_family = AF_UNIX;
  memcpy(addr.sun_path, SOCKET_PATH, strlen(SOCKET_PATH));

  socklen_t len = offsetof(struct sockaddr_un, sun_path) + strlen(SOCKET_PATH);

  if (connect(g_socket_fd, (struct sockaddr *)&addr, len) < 0) {
    LOGE("Failed to connect: %s", strerror(errno));
    close(g_socket_fd);
    g_socket_fd = -1;
    return -errno;
  }

  LOGI("Connected to Murasaki service");
  return 0;
}

// 断开连接
static void murasaki_disconnect(void) {
  if (g_socket_fd >= 0) {
    close(g_socket_fd);
    g_socket_fd = -1;
  }
}

// 发送请求并接收响应
static int murasaki_call(uint32_t cmd, const void *req_data, uint32_t req_size,
                         void *resp_data, uint32_t *resp_size) {
  if (murasaki_connect() != 0) {
    return -1;
  }

  // 构造请求头
  RequestHeader req_header = {.magic = MURASAKI_MAGIC,
                              .version = MURASAKI_PROTOCOL_VERSION,
                              .cmd = cmd,
                              .seq = ++g_seq,
                              .data_size = req_size,
                              .reserved = 0};

  // 发送请求头
  if (send(g_socket_fd, &req_header, sizeof(req_header), 0) !=
      sizeof(req_header)) {
    LOGE("Failed to send header");
    murasaki_disconnect();
    return -1;
  }

  // 发送请求数据
  if (req_size > 0 && req_data) {
    if (send(g_socket_fd, req_data, req_size, 0) != (ssize_t)req_size) {
      LOGE("Failed to send data");
      murasaki_disconnect();
      return -1;
    }
  }

  // 接收响应头
  ResponseHeader resp_header;
  if (recv(g_socket_fd, &resp_header, sizeof(resp_header), MSG_WAITALL) !=
      sizeof(resp_header)) {
    LOGE("Failed to recv header");
    murasaki_disconnect();
    return -1;
  }

  if (resp_header.magic != MURASAKI_MAGIC) {
    LOGE("Invalid response magic");
    return -1;
  }

  // 接收响应数据
  if (resp_header.data_size > 0) {
    if (resp_data && resp_size && *resp_size >= resp_header.data_size) {
      if (recv(g_socket_fd, resp_data, resp_header.data_size, MSG_WAITALL) !=
          (ssize_t)resp_header.data_size) {
        LOGE("Failed to recv data");
        murasaki_disconnect();
        return -1;
      }
      *resp_size = resp_header.data_size;
    } else {
      // 丢弃数据
      char buf[256];
      uint32_t remaining = resp_header.data_size;
      while (remaining > 0) {
        uint32_t chunk = remaining > sizeof(buf) ? sizeof(buf) : remaining;
        recv(g_socket_fd, buf, chunk, MSG_WAITALL);
        remaining -= chunk;
      }
    }
  } else if (resp_size) {
    *resp_size = 0;
  }

  return resp_header.result;
}

// ==================== JNI 函数 ====================

#ifdef __cplusplus
extern "C" {
#endif // #ifdef __cplusplus

JNIEXPORT jint JNICALL
Java_io_murasaki_MurasakiNative_nativeGetVersion(JNIEnv *env, jclass clazz) {
  int32_t version = 0;
  uint32_t size = sizeof(version);
  if (murasaki_call(CMD_GET_VERSION, NULL, 0, &version, &size) == 0) {
    return version;
  }
  return -1;
}

JNIEXPORT jint JNICALL
Java_io_murasaki_MurasakiNative_nativeGetKsuVersion(JNIEnv *env, jclass clazz) {
  int32_t version = 0;
  uint32_t size = sizeof(version);
  if (murasaki_call(CMD_GET_KSU_VERSION, NULL, 0, &version, &size) == 0) {
    return version;
  }
  return -1;
}

JNIEXPORT jint JNICALL Java_io_murasaki_MurasakiNative_nativeGetPrivilegeLevel(
    JNIEnv *env, jclass clazz) {
  int32_t level = 0;
  uint32_t size = sizeof(level);
  if (murasaki_call(CMD_GET_PRIVILEGE_LEVEL, NULL, 0, &level, &size) == 0) {
    return level;
  }
  return -1;
}

JNIEXPORT jboolean JNICALL
Java_io_murasaki_MurasakiNative_nativeIsKernelModeAvailable(JNIEnv *env,
                                                            jclass clazz) {
  int32_t value = 0;
  uint32_t size = sizeof(value);
  if (murasaki_call(CMD_IS_KERNEL_MODE_AVAILABLE, NULL, 0, &value, &size) ==
      0) {
    return value != 0;
  }
  return JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_io_murasaki_MurasakiNative_nativeGetSelinuxContext(JNIEnv *env,
                                                        jclass clazz,
                                                        jint pid) {
  int32_t req_pid = pid;
  char context[256] = {0};
  uint32_t size = sizeof(context);
  if (murasaki_call(CMD_GET_SELINUX_CONTEXT, &req_pid, sizeof(req_pid), context,
                    &size) == 0) {
    return (*env)->NewStringUTF(env, context);
  }
  return NULL;
}

JNIEXPORT jint JNICALL Java_io_murasaki_MurasakiNative_nativeHymoAddRule(
    JNIEnv *env, jclass clazz, jstring src, jstring target, jint type) {
  struct {
    char src[256];
    char target[256];
    int32_t type;
  } req = {0};

  const char *src_str = (*env)->GetStringUTFChars(env, src, NULL);
  const char *target_str = (*env)->GetStringUTFChars(env, target, NULL);

  strncpy(req.src, src_str, sizeof(req.src) - 1);
  strncpy(req.target, target_str, sizeof(req.target) - 1);
  req.type = type;

  (*env)->ReleaseStringUTFChars(env, src, src_str);
  (*env)->ReleaseStringUTFChars(env, target, target_str);

  return murasaki_call(CMD_HYMO_ADD_RULE, &req, sizeof(req), NULL, NULL);
}

JNIEXPORT jint JNICALL Java_io_murasaki_MurasakiNative_nativeHymoClearRules(
    JNIEnv *env, jclass clazz) {
  return murasaki_call(CMD_HYMO_CLEAR_RULES, NULL, 0, NULL, NULL);
}

JNIEXPORT jint JNICALL Java_io_murasaki_MurasakiNative_nativeHymoSetStealth(
    JNIEnv *env, jclass clazz, jboolean enable) {
  int32_t value = enable ? 1 : 0;
  return murasaki_call(CMD_HYMO_SET_STEALTH, &value, sizeof(value), NULL, NULL);
}

JNIEXPORT jint JNICALL Java_io_murasaki_MurasakiNative_nativeHymoSetDebug(
    JNIEnv *env, jclass clazz, jboolean enable) {
  int32_t value = enable ? 1 : 0;
  return murasaki_call(CMD_HYMO_SET_DEBUG, &value, sizeof(value), NULL, NULL);
}

JNIEXPORT jint JNICALL Java_io_murasaki_MurasakiNative_nativeHymoSetMirrorPath(
    JNIEnv *env, jclass clazz, jstring path) {
  struct {
    char path[256];
  } req = {0};

  const char *path_str = (*env)->GetStringUTFChars(env, path, NULL);
  strncpy(req.path, path_str, sizeof(req.path) - 1);
  (*env)->ReleaseStringUTFChars(env, path, path_str);

  return murasaki_call(CMD_HYMO_SET_MIRROR_PATH, &req, sizeof(req), NULL, NULL);
}

JNIEXPORT jint JNICALL
Java_io_murasaki_MurasakiNative_nativeHymoFixMounts(JNIEnv *env, jclass clazz) {
  return murasaki_call(CMD_HYMO_FIX_MOUNTS, NULL, 0, NULL, NULL);
}

JNIEXPORT jstring JNICALL
Java_io_murasaki_MurasakiNative_nativeHymoGetActiveRules(JNIEnv *env,
                                                         jclass clazz) {
  char *rules = (char *)malloc(MAX_PACKET_SIZE);
  if (!rules)
    return NULL;

  uint32_t size = MAX_PACKET_SIZE;
  if (murasaki_call(CMD_HYMO_GET_ACTIVE_RULES, NULL, 0, rules, &size) == 0) {
    jstring result = (*env)->NewStringUTF(env, rules);
    free(rules);
    return result;
  }
  free(rules);
  return NULL;
}

JNIEXPORT jboolean JNICALL
Java_io_murasaki_MurasakiNative_nativeIsUidGrantedRoot(JNIEnv *env,
                                                       jclass clazz, jint uid) {
  int32_t req_uid = uid;
  int32_t value = 0;
  uint32_t size = sizeof(value);
  if (murasaki_call(CMD_IS_UID_GRANTED_ROOT, &req_uid, sizeof(req_uid), &value,
                    &size) == 0) {
    return value != 0;
  }
  return JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_io_murasaki_MurasakiNative_nativeNukeExt4Sysfs(JNIEnv *env, jclass clazz) {
  return murasaki_call(CMD_NUKE_EXT4_SYSFS, NULL, 0, NULL, NULL);
}

JNIEXPORT void JNICALL
Java_io_murasaki_MurasakiNative_nativeDisconnect(JNIEnv *env, jclass clazz) {
  murasaki_disconnect();
}

#ifdef __cplusplus
}
#endif // #ifdef __cplusplus
