package com.anatdx.yukisu.ui.viewmodel

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.anatdx.yukisu.R
import com.anatdx.yukisu.ui.util.getKsud
import com.anatdx.yukisu.ui.util.getRootShell
import com.topjohnwu.superuser.Shell
import org.json.JSONObject
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class KernelManagerViewModel : ViewModel() {
    
    var isLoading by mutableStateOf(true)
        private set
    
    var currentSlot by mutableStateOf("")
        private set
    
    var otherSlot by mutableStateOf("")
        private set
    
    var hasOtherSlot by mutableStateOf(false)
        private set
    
    var currentKernelVersion by mutableStateOf("")
        private set
    
    var otherKernelVersion by mutableStateOf("")
        private set
    
    var avbStatus by mutableStateOf("")
        private set
    
    fun loadKernelInfo() {
        viewModelScope.launch {
            isLoading = true
            withContext(Dispatchers.IO) {
                try {
                    val shell = getRootShell()
                    val ksud = getKsud()
                    
                    // Get boot slot info
                    val bootInfoResult = mutableListOf<String>()
                    shell.newJob()
                        .add("$ksud flash boot-info")
                        .to(bootInfoResult)
                        .exec()
                    
                    val bootInfoJson = bootInfoResult.joinToString("")
                    Log.d("KernelManager", "boot-info: $bootInfoJson")
                    
                    if (bootInfoJson.isNotEmpty()) {
                        val bootInfo = JSONObject(bootInfoJson)
                        val isAb = bootInfo.optBoolean("is_ab", false)
                        hasOtherSlot = isAb
                        
                        if (isAb) {
                            currentSlot = bootInfo.optString("current_slot", "")
                            otherSlot = bootInfo.optString("other_slot", "")
                        }
                    }
                    
                    // Get current kernel version
                    val kernelResult = mutableListOf<String>()
                    shell.newJob()
                        .add("$ksud flash kernel")
                        .to(kernelResult)
                        .exec()
                    
                    val kernelOutput = kernelResult.joinToString("\n").trim()
                    Log.d("KernelManager", "kernel version: $kernelOutput")
                    // Parse "Kernel version: Linux version ..." -> "Linux version ..."
                    currentKernelVersion = if (kernelOutput.contains(":")) {
                        kernelOutput.substringAfter(":").trim()
                    } else {
                        kernelOutput
                    }
                    
                    // Get other slot kernel version if exists
                    if (hasOtherSlot && otherSlot.isNotEmpty()) {
                        val otherKernelResult = mutableListOf<String>()
                        shell.newJob()
                            .add("$ksud flash kernel --slot $otherSlot")
                            .to(otherKernelResult)
                            .exec()
                        
                        val otherKernelOutput = otherKernelResult.joinToString("\n").trim()
                        otherKernelVersion = if (otherKernelOutput.contains(":")) {
                            otherKernelOutput.substringAfter(":").trim()
                        } else {
                            otherKernelOutput
                        }
                    }
                    
                    // Get AVB status
                    val avbResult = mutableListOf<String>()
                    shell.newJob()
                        .add("$ksud flash avb")
                        .to(avbResult)
                        .exec()
                    
                    val avbOutput = avbResult.joinToString("\n").trim()
                    Log.d("KernelManager", "avb status: $avbOutput")
                    // Parse "AVB/dm-verity status: enabled" -> "enabled"
                    avbStatus = if (avbOutput.contains(":")) {
                        avbOutput.substringAfter(":").trim()
                    } else {
                        avbOutput
                    }
                    
                } catch (e: Exception) {
                    Log.e("KernelManager", "Failed to load kernel info", e)
                    e.printStackTrace()
                }
            }
            isLoading = false
        }
    }
    
    suspend fun flashAK3(context: Context, uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        try {
            // Copy AK3 zip to temp location
            val tempFile = File(context.cacheDir, "ak3_temp.zip")
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            
            val shell = getRootShell()
            val ksud = getKsud()
            val result = mutableListOf<String>()
            
            val execResult = shell.newJob()
                .add("$ksud flash ak3 ${tempFile.absolutePath}")
                .to(result)
                .exec()
            
            tempFile.delete()
            val output = result.joinToString("\n")
            
            if (execResult.isSuccess || output.contains("success", ignoreCase = true)) {
                Result.success(context.getString(R.string.kernel_flash_success))
            } else {
                Result.failure(Exception(output))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    suspend fun flashKernelImage(context: Context, uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        try {
            // Determine partition name (boot or init_boot)
            val bootPartition = detectBootPartition()
            
            // Flash using ksud flash image
            val tempFile = File(context.cacheDir, "kernel_temp.img")
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            
            val shell = getRootShell()
            val ksud = getKsud()
            val result = mutableListOf<String>()
            
            val execResult = shell.newJob()
                .add("$ksud flash image ${tempFile.absolutePath} $bootPartition")
                .to(result)
                .exec()
            
            tempFile.delete()
            val output = result.joinToString("\n")
            
            if (execResult.isSuccess) {
                Result.success(context.getString(R.string.kernel_flash_success))
            } else {
                Result.failure(Exception(output))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    suspend fun extractKernel(context: Context): Result<String> = withContext(Dispatchers.IO) {
        try {
            val bootPartition = detectBootPartition()
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "kernel_${bootPartition}${currentSlot}_$timestamp.img"
            val outputDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "KernelSU")
            outputDir.mkdirs()
            val outputFile = File(outputDir, fileName)
            
            val shell = getRootShell()
            val ksud = getKsud()
            val result = mutableListOf<String>()
            
            val execResult = shell.newJob()
                .add("$ksud flash backup $bootPartition ${outputFile.absolutePath}")
                .to(result)
                .exec()
            
            if (outputFile.exists() && execResult.isSuccess) {
                Result.success(outputFile.absolutePath)
            } else {
                Result.failure(Exception(result.joinToString("\n")))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    suspend fun flashModule(context: Context, uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        try {
            val tempFile = File(context.cacheDir, "module_temp.ko")
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            
            val shell = getRootShell()
            val ksud = getKsud()
            val result = mutableListOf<String>()
            
            val execResult = shell.newJob()
                .add("$ksud module install ${tempFile.absolutePath}")
                .to(result)
                .exec()
            
            tempFile.delete()
            val output = result.joinToString("\n")
            
            if (execResult.isSuccess || output.contains("success", ignoreCase = true)) {
                Result.success(context.getString(R.string.kernel_flash_success))
            } else {
                Result.failure(Exception(output))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    suspend fun disableAvb(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val shell = getRootShell()
            val ksud = getKsud()
            val result = mutableListOf<String>()
            
            val execResult = shell.newJob()
                .add("$ksud flash avb disable")
                .to(result)
                .exec()
            
            if (execResult.isSuccess) {
                Result.success(Unit)
            } else {
                Result.failure(Exception(result.joinToString("\n")))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    private fun detectBootPartition(): String {
        // Try to detect init_boot first (GKI 2.0), then fall back to boot
        val initBootExists = File("/dev/block/by-name/init_boot$currentSlot").exists() ||
                            File("/dev/block/by-name/init_boot").exists()
        return if (initBootExists) "init_boot" else "boot"
    }
}
