# YukiSU - HymoFS Branch

这是 YukiSU 的 HymoFS 集成分支，将 KernelSU 和 HymoFS 合并为一个统一的解决方案。

## 特性

- **KernelSU 核心功能**: root 授权、模块管理、SuperKey 支持
- **HymoFS 文件系统操作**: 
  - 路径重定向 (open redirect)
  - 目录项隐藏 (directory hiding)
  - 目录项注入 (directory injection)
  - stat/kstat 伪装 (stat spoofing)
  - xattr 过滤 (xattr filtering)
  - uname 伪装 (uname spoofing)
  - cmdline/bootconfig 伪装

## 编译配置

在 `make menuconfig` 中：

```
-> KernelSU
   -> [*] KernelSU function support
   -> KernelSU - HymoFS
      -> [*] KernelSU addon - HymoFS
      -> [ ] Enable HymoFS debug logging (可选)
```

或在 defconfig 中添加：
```
CONFIG_KSU=y
CONFIG_KSU_HYMOFS=y
# CONFIG_KSU_HYMOFS_DEBUG is not set
```

## 内核补丁使用

### 方式一：使用统一超级补丁 (推荐)

只需打一个补丁：

```bash
cd /path/to/kernel
patch -p1 < /path/to/YukiSU/kernel_patches/50_add_ksu_hymofs_gki-6.6.patch
```

这个补丁包含：
- KSU inline hooks (input, vfs_read, execve, faccessat, stat)
- HymoFS inline hooks (全部功能)

### 方式二：手动集成

1. 复制 `kernel/` 目录到内核的 `drivers/kernelsu/`
2. 在内核 Makefile 中添加 KernelSU
3. 手动修改需要 hook 的文件

## 目录结构

```
YukiSU/
├── kernel/                    # KernelSU + HymoFS 核心代码
│   ├── ksu.c                  # 主入口
│   ├── hymofs.c               # HymoFS 实现
│   ├── hymofs.h               # HymoFS 头文件
│   ├── hymo_magic.h           # HymoFS 协议定义
│   └── ...                    # 其他 KSU 文件
├── kernel_patches/            # 内核补丁
│   └── 50_add_ksu_hymofs_gki-6.6.patch  # 统一补丁
└── userspace/                 # 用户空间工具 (ksud)
```

## 与 SUSFS 的区别

| 功能 | HymoFS | SUSFS |
|------|--------|-------|
| 路径重定向 | ✅ | ✅ |
| 目录隐藏 | ✅ | ✅ |
| stat 伪装 | ✅ 完整 | ✅ |
| uname 伪装 | ✅ | ✅ |
| cmdline 伪装 | ✅ | ✅ |
| 目录注入 | ✅ | ❌ |
| xattr 过滤 | ✅ | ❌ |
| d_path 反向查找 | ✅ | ❌ |
| 配置接口 | /dev/hymo (fd) + reboot syscall | 专用 syscall |


## 注意事项

1. `CONFIG_KSU_HYMOFS` 和 `CONFIG_KSU_SUSFS` 互斥，不能同时启用
2. HymoFS 使用 inline hook 模式，不需要 kprobes
3. 建议在 GKI 6.6+ 内核上使用
