/* SPDX-License-Identifier: GPL-2.0-or-later */
/*
 * YukiSU Zygisk Kernel Support
 *
 * This provides kernel-level support for Zygisk injection:
 * - Detects app_process (zygote) execution
 * - Pauses zygote until userspace daemon completes injection
 * - Provides IOCTL interface for daemon communication
 */

#ifndef __KSU_ZYGISK_H
#define __KSU_ZYGISK_H

#include <linux/types.h>

#ifdef CONFIG_KSU_ZYGISK

// Initialize zygisk support
void ksu_zygisk_init(void);

// Cleanup zygisk support
void ksu_zygisk_exit(void);

// Called from execve hook when app_process is detected
// Returns: true if zygote should be paused
bool ksu_zygisk_on_app_process(pid_t pid, bool is_64bit);

// Wait for zygote (called from userspace via IOCTL)
// Returns: pid of detected zygote, or 0 on timeout/error
int ksu_zygisk_wait_zygote(int *pid, bool *is_64bit, unsigned int timeout_ms);

// Resume a paused zygote
int ksu_zygisk_resume_zygote(pid_t pid);

// Enable/disable zygisk support
void ksu_zygisk_set_enabled(bool enable);
bool ksu_zygisk_is_enabled(void);

#else // CONFIG_KSU_ZYGISK not defined - provide stub functions

static inline void ksu_zygisk_init(void)
{
}
static inline void ksu_zygisk_exit(void)
{
}
static inline bool ksu_zygisk_on_app_process(pid_t pid, bool is_64bit)
{
	return false;
}
static inline int ksu_zygisk_wait_zygote(int *pid, bool *is_64bit,
					 unsigned int timeout_ms)
{
	return -1;
}
static inline int ksu_zygisk_resume_zygote(pid_t pid)
{
	return -1;
}
static inline void ksu_zygisk_set_enabled(bool enable)
{
}
static inline bool ksu_zygisk_is_enabled(void)
{
	return false;
}

#endif // #ifdef CONFIG_KSU_ZYGISK

#endif // #ifndef __KSU_ZYGISK_H
