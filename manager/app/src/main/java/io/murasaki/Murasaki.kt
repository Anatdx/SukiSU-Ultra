package io.murasaki

import android.util.Log

/**
 * Murasaki - KernelSU 内核级 API
 * 
 * 向下兼容 Shizuku/Sui，同时提供内核直通能力。
 * 
 * 权限等级体系：
 * - LEVEL_SHELL (0): Shizuku 兼容层，shell 权限
 * - LEVEL_ROOT (1): Sui 兼容层，root 权限
 * - LEVEL_KERNEL (2): Murasaki 专有，内核级权限 (需要 KernelSU)
 */
object Murasaki {
    
    private const val TAG = "Murasaki"
    
    // 权限等级常量
    const val LEVEL_SHELL = 0
    const val LEVEL_ROOT = 1
    const val LEVEL_KERNEL = 2
    
    private var initialized = false
    private var privilegeLevel = -1
    
    /**
     * 初始化 Murasaki
     * @return 成功获取到的权限等级，-1 表示失败
     */
    fun init(): Int {
        if (initialized) {
            return privilegeLevel
        }
        
        try {
            privilegeLevel = MurasakiNative.nativeGetPrivilegeLevel()
            if (privilegeLevel >= 0) {
                initialized = true
                Log.i(TAG, "Initialized with level $privilegeLevel")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize", e)
            privilegeLevel = -1
        }
        
        return privilegeLevel
    }
    
    /**
     * 获取当前权限等级
     */
    fun getPrivilegeLevel(): Int {
        if (!initialized) init()
        return privilegeLevel
    }
    
    /**
     * 检查内核模式是否可用
     */
    fun isKernelModeAvailable(): Boolean {
        return try {
            MurasakiNative.nativeIsKernelModeAvailable()
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * 获取 Murasaki 服务版本
     */
    fun getVersion(): Int {
        return try {
            MurasakiNative.nativeGetVersion()
        } catch (e: Exception) {
            -1
        }
    }
    
    /**
     * 获取 KernelSU 版本
     */
    fun getKsuVersion(): Int {
        return try {
            MurasakiNative.nativeGetKsuVersion()
        } catch (e: Exception) {
            -1
        }
    }
    
    /**
     * 获取指定进程的 SELinux 上下文
     * @param pid 进程 ID，0 表示当前进程
     */
    fun getSelinuxContext(pid: Int = 0): String? {
        return try {
            MurasakiNative.nativeGetSelinuxContext(pid)
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * 检查是否已初始化
     */
    fun isInitialized() = initialized
    
    /**
     * 断开连接
     */
    fun disconnect() {
        try {
            MurasakiNative.nativeDisconnect()
        } catch (e: Exception) {
            // Ignore
        }
        initialized = false
        privilegeLevel = -1
    }
}

/**
 * HymoFS 服务接口
 */
object HymoFs {
    
    /**
     * 规则类型
     */
    object RuleType {
        const val FILE = 0
        const val DIRECTORY = 1
        const val SYMLINK = 2
    }
    
    /**
     * 添加隐藏/重定向规则
     */
    fun addRule(src: String, target: String, type: Int = RuleType.FILE): Boolean {
        return MurasakiNative.nativeHymoAddRule(src, target, type) == 0
    }
    
    /**
     * 清除所有规则
     */
    fun clearRules(): Boolean {
        return MurasakiNative.nativeHymoClearRules() == 0
    }
    
    /**
     * 设置隐身模式
     */
    fun setStealth(enable: Boolean): Boolean {
        return MurasakiNative.nativeHymoSetStealth(enable) == 0
    }
    
    /**
     * 设置调试模式
     */
    fun setDebug(enable: Boolean): Boolean {
        return MurasakiNative.nativeHymoSetDebug(enable) == 0
    }
    
    /**
     * 设置镜像路径
     */
    fun setMirrorPath(path: String): Boolean {
        return MurasakiNative.nativeHymoSetMirrorPath(path) == 0
    }
    
    /**
     * 修复挂载命名空间
     */
    fun fixMounts(): Boolean {
        return MurasakiNative.nativeHymoFixMounts() == 0
    }
    
    /**
     * 获取当前活跃规则
     */
    fun getActiveRules(): String? {
        return MurasakiNative.nativeHymoGetActiveRules()
    }
}

/**
 * KernelSU 服务接口
 */
object KsuService {
    
    /**
     * 检查 UID 是否被授予 Root
     */
    fun isUidGrantedRoot(uid: Int): Boolean {
        return MurasakiNative.nativeIsUidGrantedRoot(uid)
    }
    
    /**
     * 清除 ext4 sysfs 痕迹 (Paw Pad)
     */
    fun nukeExt4Sysfs(): Boolean {
        return MurasakiNative.nativeNukeExt4Sysfs() == 0
    }
}
