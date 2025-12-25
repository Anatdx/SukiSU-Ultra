# YukiSU SuperKey 认证系统使用指南

## 概述

SuperKey 认证系统是 YukiSU 的 APatch 风格认证方式，允许用户通过设置超级密码来认证管理器，替代复杂的 APK 签名验证。

## 工作原理

1. **修补时**：用户在安装 LKM 时输入 SuperKey → ksud 计算 hash 并写入到 LKM
2. **运行时**：管理器 APP 保存用户输入的 SuperKey 到本地
3. **认证时**：管理器发送 SuperKey → 内核计算 hash 并比对

## 使用方法

### 1. 安装 LKM 时设置 SuperKey

在管理器的 **安装** 页面：
1. 选择安装方式（直接安装、选择文件等）
2. 在 **SuperKey（可选）** 卡片中输入您的超级密码
3. 点击下一步开始修补

SuperKey 将被嵌入到 LKM 中。

### 2. 认证管理器

安装完成重启后：
1. 打开管理器 APP
2. 如果检测到 KSU 驱动但未认证，会显示 "需要超级密钥认证" 卡片
3. 点击卡片，输入您设置的 SuperKey
4. 认证成功后，APP 将获得管理器权限

**注意**：认证成功后 SuperKey 会自动保存，下次启动自动认证。

### 3. 命令行使用

```bash
# 使用 ksud 修补 boot 并设置 SuperKey
ksud boot-patch -b boot.img --superkey "your_secret_key"

# 直接安装到设备（需 root）
ksud boot-patch -f --superkey "your_secret_key"
```

## 技术细节

### SuperKey Hash 算法

```
hash = 1000000007
for each char c in key:
    hash = hash * 31 + c
return hash
```

与 APatch 兼容的简单乘法哈希。

### LKM 中的存储结构

```c
struct superkey_data {
    u64 magic;      // 0x5355504552 ("SUPER")
    u64 hash;       // SuperKey hash
    u64 reserved;   // 保留
};
```

ksud 会搜索 SUPERKEY_MAGIC 并修改紧随其后的 hash 值。

## 安全注意事项

1. **SuperKey 应该足够复杂**：建议使用 16+ 字符，包含字母数字和特殊字符
2. **不要泄露 SuperKey**：SuperKey 等同于 root 密码
3. **定期更换**：如果怀疑 SuperKey 泄露，应重新修补 boot 并更换
4. **本地存储**：SuperKey 保存在管理器的 SharedPreferences 中（加密存储）

## 文件结构

### 内核侧

```
YukiSU/kernel/
├── superkey.h          # SuperKey 定义和 hash 函数
├── superkey.c          # SuperKey 认证实现
├── supercalls.h        # IOCTL 定义
└── supercalls.c        # IOCTL 处理
```

### ksud 侧

```
YukiSU/userspace/ksud/src/
└── boot_patch.rs       # 包含 --superkey 参数和注入逻辑
```

### 管理器侧

```
YukiSU/manager/app/src/main/
├── cpp/
│   ├── ksu.h           # IOCTL 定义
│   ├── ksu.c           # authenticate_superkey()
│   └── jni.c           # JNI 桥接
├── java/com/sukisu/ultra/
│   ├── Natives.kt      # authenticateSuperKey()
│   └── ui/
│       ├── component/
│       │   └── SuperKeyDialog.kt   # SuperKey 输入对话框
│       └── screen/
│           ├── Home.kt             # 认证卡片 + 自动认证
│           ├── Install.kt          # SuperKey 输入框
│           └── Flash.kt            # FlashIt.FlashBoot
└── res/values/
    └── strings.xml
```

## 故障排除

### 认证失败
- 确认输入的 SuperKey 与修补时设置的完全一致
- 检查 dmesg 日志：`dmesg | grep -i superkey`

### ksud 提示 SUPERKEY_MAGIC not found
- 确认 LKM 编译时包含了 superkey.c
- 检查 .ko 文件中是否有 superkey_store 结构

### 自动认证不工作
- 清除管理器数据重新认证
- 检查 SharedPreferences 是否被清除
