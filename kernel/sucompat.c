#include <linux/compiler_types.h>
#include <linux/mm.h>
#include <linux/preempt.h>
#include <linux/printk.h>
#include <linux/pgtable.h>
#include <asm/current.h>
#include <linux/cred.h>
#include <linux/fs.h>
#include <linux/ptrace.h>
#include <linux/sched/task_stack.h>
#include <linux/types.h>
#include <linux/uaccess.h>
#include <linux/version.h>

#include "allowlist.h"
#include "app_profile.h"
#include "feature.h"
#include "klog.h" // IWYU pragma: keep
#include "ksud.h"
#include "sucompat.h"
#include "util.h"

#include "sulog.h"

#define SU_PATH "/system/bin/su"
#define SH_PATH "/system/bin/sh"

bool ksu_su_compat_enabled __read_mostly = true;

static int su_compat_feature_get(u64 *value)
{
	*value = ksu_su_compat_enabled ? 1 : 0;
	return 0;
}

static int su_compat_feature_set(u64 value)
{
	bool enable = value != 0;
	ksu_su_compat_enabled = enable;
	pr_info("su_compat: set to %d\n", enable);
	return 0;
}

static const struct ksu_feature_handler su_compat_handler = {
    .feature_id = KSU_FEATURE_SU_COMPAT,
    .name = "su_compat",
    .get_handler = su_compat_feature_get,
    .set_handler = su_compat_feature_set,
};

static void __user *userspace_stack_buffer(const void *d, size_t len)
{
	// To avoid having to mmap a page in userspace, just write below the
	// stack pointer.
	char __user *p = (void __user *)current_user_stack_pointer() - len;

	return copy_to_user(p, d, len) ? NULL : p;
}

static char __user *sh_user_path(void)
{
	static const char sh_path[] = "/system/bin/sh";

	return userspace_stack_buffer(sh_path, sizeof(sh_path));
}

static const char sh_path[] = SH_PATH;
static const char su_path[] = SU_PATH;
static const char ksud_path[] = KSUD_PATH;

extern bool ksu_kernel_umount_enabled;

// the call from execve_handler_pre won't provided correct value for
// __never_use_argument, use them after fix execve_handler_pre, keeping them for
// consistence for manually patched code
int ksu_handle_execveat_sucompat(int *fd, struct filename **filename_ptr,
			       void *__never_use_argv, void *__never_use_envp,
			       int *__never_use_flags)
{
	struct filename *filename;
	bool is_allowed = ksu_is_allow_uid_for_current(current_uid().val);

	if (!ksu_su_compat_enabled) {
		return 0;
	}

	if (unlikely(!filename_ptr))
		return 0;

	if (!is_allowed)
		return 0;

	filename = *filename_ptr;
	if (IS_ERR(filename)) {
		return 0;
	}

	if (likely(memcmp(filename->name, su_path, sizeof(su_path))))
		return 0;

#if __SULOG_GATE
	ksu_sulog_report_syscall(current_uid().val, NULL, "execve", su_path);
	ksu_sulog_report_su_attempt(current_uid().val, NULL, su_path,
				    is_allowed);
#endif

	pr_info("do_execveat_common su found\n");
	memcpy((void *)filename->name, ksud_path, sizeof(ksud_path));

	escape_with_root_profile();

	return 0;
}

int ksu_handle_faccessat(int *dfd, const char __user **filename_user, int *mode,
			 int *__unused_flags)
{
	char path[sizeof(su_path) + 1] = {0};

	if (!ksu_su_compat_enabled) {
		return 0;
	}

	if (!ksu_is_allow_uid_for_current(current_uid().val))
		return 0;

	ksu_strncpy_from_user_nofault(path, *filename_user, sizeof(path));

	if (unlikely(!memcmp(path, su_path, sizeof(su_path)))) {
#if __SULOG_GATE
		ksu_sulog_report_syscall(current_uid().val, NULL, "faccessat",
					 path);
#endif
		pr_info("faccessat su->sh!\n");
		*filename_user = sh_user_path();
	}

	return 0;
}

int ksu_handle_stat(int *dfd, const char __user **filename_user, int *flags)
{
	char path[sizeof(su_path) + 1] = {0};

	if (!ksu_su_compat_enabled) {
		return 0;
	}

	if (unlikely(!filename_user)) {
		return 0;
	}

	if (!ksu_is_allow_uid_for_current(current_uid().val))
		return 0;

	ksu_strncpy_from_user_nofault(path, *filename_user, sizeof(path));

	if (unlikely(!memcmp(path, su_path, sizeof(su_path)))) {
#if __SULOG_GATE
		ksu_sulog_report_syscall(current_uid().val, NULL, "newfstatat",
					 path);
#endif
		pr_info("ksu_handle_stat: su->sh!\n");
		*filename_user = sh_user_path();
	}

	return 0;
}

// sucompat: permitted process can execute 'su' to gain root access.
void ksu_sucompat_init()
{
	if (ksu_register_feature_handler(&su_compat_handler)) {
		pr_err("Failed to register su_compat feature handler\n");
	}
}

void ksu_sucompat_exit()
{
	ksu_unregister_feature_handler(KSU_FEATURE_SU_COMPAT);
}
