package io.murasaki

/**
 * Murasaki Native 接口
 * 
 * 与 ksud 的 Murasaki 服务通信的 JNI 层
 */
internal object MurasakiNative {
    
    init {
        System.loadLibrary("kernelsu")
    }
    
    // 基础信息
    external fun nativeGetVersion(): Int
    external fun nativeGetKsuVersion(): Int
    external fun nativeGetPrivilegeLevel(): Int
    external fun nativeIsKernelModeAvailable(): Boolean
    
    // SELinux
    external fun nativeGetSelinuxContext(pid: Int): String?
    
    // HymoFS
    external fun nativeHymoAddRule(src: String, target: String, type: Int): Int
    external fun nativeHymoClearRules(): Int
    external fun nativeHymoSetStealth(enable: Boolean): Int
    external fun nativeHymoSetDebug(enable: Boolean): Int
    external fun nativeHymoSetMirrorPath(path: String): Int
    external fun nativeHymoFixMounts(): Int
    external fun nativeHymoGetActiveRules(): String?
    
    // KSU
    external fun nativeIsUidGrantedRoot(uid: Int): Boolean
    external fun nativeNukeExt4Sysfs(): Int
    
    // 连接管理
    external fun nativeDisconnect()
}
