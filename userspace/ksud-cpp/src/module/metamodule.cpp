#include "metamodule.hpp"
#include "../defs.hpp"
#include "../log.hpp"
#include "../utils.hpp"

#include <unistd.h>
#include <dirent.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <cstring>

namespace ksud {

static bool file_exists(const std::string& path) {
    struct stat st;
    return stat(path.c_str(), &st) == 0;
}

static int run_script(const std::string& script, bool block) {
    if (!file_exists(script)) return 0;

    LOGI("Running metamodule script: %s", script.c_str());

    pid_t pid = fork();
    if (pid == 0) {
        setsid();
        chdir("/");

        setenv("KSU", "true", 1);
        setenv("KSU_VER", KSUD_VERSION, 1);
        setenv("PATH", "/data/adb/ksu/bin:/data/adb/ap/bin:/system/bin:/vendor/bin", 1);

        execl("/system/bin/sh", "sh", script.c_str(), nullptr);
        _exit(127);
    }

    if (pid < 0) {
        LOGE("Failed to fork for script: %s", script.c_str());
        return -1;
    }

    if (block) {
        int status;
        waitpid(pid, &status, 0);
        return WIFEXITED(status) ? WEXITSTATUS(status) : -1;
    }

    return 0;
}

int metamodule_init() {
    LOGD("Metamodule init");
    return 0;
}

int metamodule_exec_stage_script(const std::string& stage, bool block) {
    std::string script = std::string(METAMODULE_DIR) + stage + ".sh";
    return run_script(script, block);
}

int metamodule_exec_mount_script() {
    std::string script = std::string(METAMODULE_DIR) + "mount.sh";
    return run_script(script, true);
}

} // namespace ksud
