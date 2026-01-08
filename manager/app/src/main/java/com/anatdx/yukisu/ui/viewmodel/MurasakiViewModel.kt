package com.anatdx.yukisu.ui.viewmodel

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.murasaki.Murasaki
import io.murasaki.server.IHymoFsService
import io.murasaki.server.IKernelService
import io.murasaki.server.IMurasakiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Murasaki 服务状态 ViewModel
 * 
 * 用于管理 Murasaki API 的连接状态和数据展示
 */
class MurasakiViewModel : ViewModel() {

    /**
     * Murasaki 服务状态
     */
    data class MurasakiStatus(
        val isConnected: Boolean = false,
        val serviceVersion: Int = -1,
        val ksuVersion: Int = -1,
        val privilegeLevel: Int = -1,
        val privilegeLevelName: String = "Unknown",
        val isKernelModeAvailable: Boolean = false,
        val selinuxContext: String? = null,
        val error: String? = null
    )

    /**
     * HymoFS 状态
     */
    data class HymoFsStatus(
        val isAvailable: Boolean = false,
        val version: Int = -1,
        val stealthEnabled: Boolean = false,
        val hideRulesCount: Int = 0,
        val redirectRulesCount: Int = 0
    )

    // 状态
    var murasakiStatus by mutableStateOf(MurasakiStatus())
        private set

    var hymoFsStatus by mutableStateOf(HymoFsStatus())
        private set

    var isLoading by mutableStateOf(false)
        private set

    // 服务引用
    private var murasakiService: IMurasakiService? = null
    private var hymoFsService: IHymoFsService? = null
    private var kernelService: IKernelService? = null

    /**
     * 初始化连接
     */
    fun connect(packageName: String) {
        viewModelScope.launch {
            isLoading = true
            try {
                val result = withContext(Dispatchers.IO) {
                    val level = Murasaki.init(packageName)
                    if (level < 0) {
                        return@withContext MurasakiStatus(
                            isConnected = false,
                            error = "Failed to connect to Murasaki service"
                        )
                    }

                    // 获取服务引用
                    murasakiService = Murasaki.getMurasakiService()
                    hymoFsService = Murasaki.getHymoFsService()
                    kernelService = Murasaki.getKernelService()

                    MurasakiStatus(
                        isConnected = true,
                        serviceVersion = murasakiService?.version ?: -1,
                        ksuVersion = Murasaki.getKernelSuVersion(),
                        privilegeLevel = level,
                        privilegeLevelName = getPrivilegeLevelName(level),
                        isKernelModeAvailable = Murasaki.isKernelModeAvailable(),
                        selinuxContext = Murasaki.getSELinuxContext()
                    )
                }
                murasakiStatus = result
                
                // 连接成功后刷新 HymoFS 状态
                if (result.isConnected) {
                    refreshHymoFsStatus()
                }
            } catch (e: Exception) {
                murasakiStatus = MurasakiStatus(
                    isConnected = false,
                    error = e.message ?: "Unknown error"
                )
            } finally {
                isLoading = false
            }
        }
    }

    /**
     * 刷新 HymoFS 状态
     */
    fun refreshHymoFsStatus() {
        viewModelScope.launch {
            try {
                val status = withContext(Dispatchers.IO) {
                    val service = hymoFsService ?: return@withContext HymoFsStatus()
                    
                    HymoFsStatus(
                        isAvailable = service.isAvailable,
                        version = service.version,
                        stealthEnabled = service.isStealthMode,
                        hideRulesCount = service.hideRules?.size ?: 0,
                        redirectRulesCount = service.redirectRules?.size ?: 0
                    )
                }
                hymoFsStatus = status
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    /**
     * 设置隐身模式
     */
    fun setStealthMode(enabled: Boolean) {
        viewModelScope.launch {
            try {
                val success = withContext(Dispatchers.IO) {
                    hymoFsService?.setStealthMode(enabled) ?: false
                }
                if (success) {
                    hymoFsStatus = hymoFsStatus.copy(stealthEnabled = enabled)
                }
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    /**
     * 添加隐藏规则
     */
    fun addHideRule(path: String): Int {
        var ruleId = -1
        viewModelScope.launch {
            try {
                ruleId = withContext(Dispatchers.IO) {
                    hymoFsService?.addHideRule(path) ?: -1
                }
                if (ruleId >= 0) {
                    refreshHymoFsStatus()
                }
            } catch (e: Exception) {
                // Ignore
            }
        }
        return ruleId
    }

    /**
     * 添加重定向规则
     */
    fun addRedirectRule(sourcePath: String, targetPath: String, flags: Int = 0): Int {
        var ruleId = -1
        viewModelScope.launch {
            try {
                ruleId = withContext(Dispatchers.IO) {
                    hymoFsService?.addRedirectRule(sourcePath, targetPath, flags) ?: -1
                }
                if (ruleId >= 0) {
                    refreshHymoFsStatus()
                }
            } catch (e: Exception) {
                // Ignore
            }
        }
        return ruleId
    }

    /**
     * 清除所有隐藏规则
     */
    fun clearHideRules() {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    hymoFsService?.clearHideRules()
                }
                refreshHymoFsStatus()
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    /**
     * 清除所有重定向规则
     */
    fun clearRedirectRules() {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    hymoFsService?.clearRedirectRules()
                }
                refreshHymoFsStatus()
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    /**
     * 检查 UID 是否有 root 权限
     */
    suspend fun checkUidHasRoot(uid: Int): Boolean {
        return withContext(Dispatchers.IO) {
            murasakiService?.isUidGrantedRoot(uid) ?: false
        }
    }

    /**
     * 授予 UID root 权限
     */
    suspend fun grantRoot(uid: Int): Boolean {
        return withContext(Dispatchers.IO) {
            murasakiService?.grantRoot(uid) ?: false
        }
    }

    /**
     * 撤销 UID root 权限
     */
    suspend fun revokeRoot(uid: Int): Boolean {
        return withContext(Dispatchers.IO) {
            murasakiService?.revokeRoot(uid) ?: false
        }
    }

    /**
     * 注入 SEPolicy 规则
     */
    suspend fun injectSepolicy(rule: String): Boolean {
        return withContext(Dispatchers.IO) {
            kernelService?.injectSepolicy(rule) ?: false
        }
    }

    /**
     * 执行 root 命令
     */
    suspend fun execCommand(command: String): String? {
        return withContext(Dispatchers.IO) {
            kernelService?.execCommand(command)
        }
    }

    /**
     * 重置状态
     */
    fun reset() {
        murasakiStatus = MurasakiStatus()
        hymoFsStatus = HymoFsStatus()
        murasakiService = null
        hymoFsService = null
        kernelService = null
    }

    override fun onCleared() {
        super.onCleared()
        reset()
    }

    private fun getPrivilegeLevelName(level: Int): String {
        return when (level) {
            Murasaki.LEVEL_SHELL -> "SHELL (Shizuku Compatible)"
            Murasaki.LEVEL_ROOT -> "ROOT (Sui Compatible)"
            Murasaki.LEVEL_KERNEL -> "KERNEL (Murasaki Exclusive)"
            else -> "Unknown ($level)"
        }
    }
}
