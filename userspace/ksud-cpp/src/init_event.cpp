#include "init_event.hpp"
#include "core/ksucalls.hpp"
#include "core/feature.hpp"
#include "module/module.hpp"
#include "module/module_config.hpp"
#include "module/metamodule.hpp"
#include "umount.hpp"
#include "restorecon.hpp"
#include "assets.hpp"
#include "profile/profile.hpp"
#include "defs.hpp"
#include "log.hpp"
#include "utils.hpp"

#include <unistd.h>
#include <sys/stat.h>

namespace ksud {

static void run_stage(const std::string& stage, bool block) {
    umask(0);

    // Check for Magisk (like Rust version)
    if (has_magisk()) {
        LOGW("Magisk detected, skip %s", stage.c_str());
        return;
    }

    if (is_safe_mode()) {
        LOGW("safe mode, skip %s scripts", stage.c_str());
        return;
    }

    // Execute common scripts first
    exec_common_scripts(stage + ".d", block);

    // Execute metamodule stage script (priority)
    metamodule_exec_stage_script(stage, block);

    // Execute regular modules stage scripts
    exec_stage_script(stage, block);
}

int on_post_data_fs() {
    LOGI("post-fs-data triggered");

    // Report to kernel first
    report_post_fs_data();

    umask(0);

    // Clear all temporary module configs early (like Rust version)
    clear_all_temp_configs();

    // TODO: catch_bootlog for logcat and dmesg

    // Check for Magisk (like Rust version)
    if (has_magisk()) {
        LOGW("Magisk detected, skip post-fs-data!");
        return 0;
    }

    // Check for safe mode FIRST (like Rust version)
    bool safe_mode = is_safe_mode();

    if (safe_mode) {
        LOGW("safe mode, skip common post-fs-data.d scripts");
    } else {
        // Execute common post-fs-data scripts
        exec_common_scripts("post-fs-data.d", true);
    }

    // Ensure directories exist
    ensure_dir_exists(WORKING_DIR);
    ensure_dir_exists(MODULE_DIR);
    ensure_dir_exists(LOG_DIR);
    ensure_dir_exists(PROFILE_DIR);

    // Ensure binaries exist (AFTER safe mode check, like Rust)
    if (ensure_binaries(true) != 0) {
        LOGW("Failed to ensure binaries");
    }

    // if we are in safe mode, we should disable all modules
    if (safe_mode) {
        LOGW("safe mode, skip post-fs-data scripts and disable all modules!");
        disable_all_modules();
        return 0;
    }

    // Handle updated modules
    handle_updated_modules();

    // Prune modules marked for removal
    prune_modules();

    // Restorecon
    restorecon("/data/adb", true);

    // Load sepolicy rules from modules
    load_sepolicy_rule();

    // Apply profile sepolicies
    apply_profile_sepolies();

    // Load feature config
    feature_load_config();

    // Execute metamodule post-fs-data script first (priority)
    metamodule_exec_stage_script("post-fs-data", true);

    // Execute module post-fs-data scripts
    exec_stage_script("post-fs-data", true);

    // Load system.prop from modules
    load_system_prop();

    // Execute metamodule mount script
    metamodule_exec_mount_script();

    // Load umount config and apply to kernel
    umount_apply_config();

    // Run post-mount stage
    run_stage("post-mount", true);

    chdir("/");

    LOGI("post-fs-data completed");
    return 0;
}

void on_services() {
    LOGI("services triggered");
    run_stage("service", false);
    LOGI("services completed");
}

void on_boot_completed() {
    LOGI("boot-completed triggered");

    // Report to kernel
    report_boot_complete();

    // Run boot-completed stage
    run_stage("boot-completed", false);

    LOGI("boot-completed completed");
}

} // namespace ksud
