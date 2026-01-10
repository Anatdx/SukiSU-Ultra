/**
 * YukiSU Zygisk Support
 *
 * Kernel-based zygote detection and injection support.
 * Integrated into ksud daemon for security.
 */

#ifndef KSUD_ZYGISK_HPP
#define KSUD_ZYGISK_HPP

namespace ksud {
namespace zygisk {

/**
 * Start the zygisk monitoring thread.
 * This will:
 * 1. Enable zygisk in kernel via IOCTL
 * 2. Wait for zygote detection (blocking on kernel)
 * 3. Spawn tracer to inject when zygote is detected
 * 4. Resume zygote after injection
 * 5. Loop for zygote restarts
 *
 * Should be called from run_daemon() before joining Binder thread pool.
 */
void start_zygisk_monitor();

/**
 * Stop the zygisk monitoring thread.
 */
void stop_zygisk_monitor();

/**
 * Check if zygisk support is enabled.
 */
bool is_enabled();

/**
 * Enable/disable zygisk support.
 */
void set_enabled(bool enable);

}  // namespace zygisk
}  // namespace ksud

#endif // #ifndef KSUD_ZYGISK_HPP
