#include <linux/export.h>
#include <linux/fs.h>
#include <linux/kobject.h>
#include <linux/module.h>
#include <linux/workqueue.h>
#include <linux/kallsyms.h>
#include <linux/delay.h>

#include "allowlist.h"
#include "feature.h"
#include "file_wrapper.h"
#include "klog.h" // IWYU pragma: keep
#include "ksu.h"
#include "ksud.h"
#include "supercalls.h"
#include "superkey.h"
#include "syscall_hook_manager.h"
#include "throne_tracker.h"

struct cred *ksu_cred;

#include "sulog.h"

/**
 * try_yield_gki - Try to make GKI KernelSU yield to LKM
 *
 * This function attempts to find and call the GKI's ksu_yield() function
 * to gracefully take over from GKI KernelSU.
 */
static void try_yield_gki(void)
{
	int ret, retry;
	
	// Check if GKI's ksu_is_active symbol exists
	bool *gki_is_active = (bool *)kallsyms_lookup_name("ksu_is_active");
	if (!gki_is_active) {
		pr_info("KernelSU GKI not detected, LKM running standalone\n");
		return;
	}

	if (!(*gki_is_active)) {
		pr_info("KernelSU GKI already inactive, LKM taking over\n");
		return;
	}

	// Check if GKI has finished initializing
	bool *gki_initialized = (bool *)kallsyms_lookup_name("ksu_initialized");
	if (gki_initialized && !(*gki_initialized)) {
		pr_info("KernelSU GKI not fully initialized, waiting...\n");
		// Wait up to 5 seconds for GKI to initialize
		for (retry = 0; retry < 50 && !(*gki_initialized); retry++) {
			msleep(100);
		}
		if (!(*gki_initialized)) {
			pr_warn("KernelSU GKI init timeout, forcing takeover\n");
			*gki_is_active = false;
			return;
		}
		pr_info("KernelSU GKI now initialized\n");
	}

	// GKI is active and initialized, try to call ksu_yield()
	int (*gki_yield)(void) = (void *)kallsyms_lookup_name("ksu_yield");
	if (gki_yield) {
		pr_info("KernelSU GKI detected and active, requesting yield...\n");
		ret = gki_yield();
		if (ret == 0) {
			pr_info("KernelSU GKI yielded successfully\n");
		} else {
			pr_warn("KernelSU GKI yield returned %d\n", ret);
		}
	} else {
		// GKI doesn't have ksu_yield, just mark it inactive
		pr_warn("KernelSU GKI has no yield function, forcing takeover\n");
		*gki_is_active = false;
	}
}

void yukisu_custom_config_init(void)
{
}

void yukisu_custom_config_exit(void)
{
#if __SULOG_GATE
	ksu_sulog_exit();
#endif
}

int __init kernelsu_init(void)
{
#ifdef CONFIG_KSU_DEBUG
	pr_alert(
	    "*************************************************************");
	pr_alert(
	    "**     NOTICE NOTICE NOTICE NOTICE NOTICE NOTICE NOTICE    **");
	pr_alert(
	    "**                                                         **");
	pr_alert(
	    "**         You are running KernelSU in DEBUG mode          **");
	pr_alert(
	    "**                                                         **");
	pr_alert(
	    "**     NOTICE NOTICE NOTICE NOTICE NOTICE NOTICE NOTICE    **");
	pr_alert(
	    "*************************************************************");
#endif

	// Try to take over from GKI if it exists
	try_yield_gki();

	ksu_cred = prepare_creds();
	if (!ksu_cred) {
		pr_err("prepare cred failed!\n");
	}

	ksu_feature_init();

	ksu_supercalls_init();

	// Initialize SuperKey authentication (APatch-style)
	superkey_init();

	yukisu_custom_config_init();

	ksu_syscall_hook_manager_init();

	ksu_allowlist_init();

	ksu_throne_tracker_init();

	ksu_ksud_init();

	ksu_file_wrapper_init();

#ifdef MODULE
#ifndef CONFIG_KSU_DEBUG
	kobject_del(&THIS_MODULE->mkobj.kobj);
#endif
#endif
	return 0;
}

extern void ksu_observer_exit(void);
void kernelsu_exit(void)
{
	ksu_allowlist_exit();

	ksu_throne_tracker_exit();

	ksu_observer_exit();

	ksu_ksud_exit();

	ksu_syscall_hook_manager_exit();

	yukisu_custom_config_exit();

	ksu_supercalls_exit();

	ksu_feature_exit();

	if (ksu_cred) {
		put_cred(ksu_cred);
	}
}

module_init(kernelsu_init);
module_exit(kernelsu_exit);

MODULE_LICENSE("GPL");
MODULE_AUTHOR("weishu");
MODULE_DESCRIPTION("Android KernelSU");
#if LINUX_VERSION_CODE >= KERNEL_VERSION(6, 13, 0)
MODULE_IMPORT_NS("VFS_internal_I_am_really_a_filesystem_and_am_NOT_a_driver");
#else
MODULE_IMPORT_NS(VFS_internal_I_am_really_a_filesystem_and_am_NOT_a_driver);
#endif
