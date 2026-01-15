package com.oralvis.oralviscamera

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Utility class for collecting and sharing app logs for debugging purposes.
 * Creates a ZIP file containing:
 * - App-specific debug logs
 * - Android system logs (logcat)
 * - Device information
 * - App version information
 */
class LogCollector(private val context: Context) {

    companion object {
        private const val TAG = "LogCollector"
        private const val LOG_BUFFER_SIZE = 8192
        private const val MAX_LOG_SIZE_MB = 10 // Maximum log size to prevent huge files
        private const val MAX_LOG_SIZE_BYTES = MAX_LOG_SIZE_MB * 1024 * 1024
    }

    private val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
    private val deviceId = Build.MODEL.replace(" ", "_").replace("/", "_")
    private val zipFileName = "OralVis_Logs_${deviceId}_${timestamp}.zip"

    /**
     * Collects all logs and creates a ZIP file
     * @return File object pointing to the created ZIP file, or null if failed
     */
    suspend fun collectAndZipLogs(): File? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting comprehensive log collection process")
            Log.d(TAG, "Timestamp: $timestamp")
            Log.d(TAG, "Device: ${Build.MODEL} (${Build.MANUFACTURER})")
            Log.d(TAG, "Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")

            // Create temporary directory for log files
            val tempDir = File(context.cacheDir, "log_collection_$timestamp").apply {
                mkdirs()
            }

            // Collect different types of logs
            val logFiles = mutableListOf<File>()

            // 1. Collect app debug logs
            collectAppDebugLogs(tempDir)?.let { logFiles.add(it) }

            // 2. Collect Android system logs (logcat)
            collectSystemLogs(tempDir)?.let { logFiles.add(it) }

            // 3. Collect device and app information
            collectDeviceInfo(tempDir)?.let { logFiles.add(it) }

            // 4. Collect additional diagnostic information
            collectAdditionalDiagnostics(tempDir)?.let { logFiles.add(it) }

            if (logFiles.isEmpty()) {
                Log.w(TAG, "No logs collected")
                return@withContext null
            }

            // Create ZIP file
            val zipFile = createZipFile(logFiles, tempDir)

            // Clean up temporary files
            cleanupTempFiles(tempDir)

            Log.d(TAG, "Log collection completed: ${zipFile.absolutePath}")
            return@withContext zipFile

        } catch (e: Exception) {
            Log.e(TAG, "Failed to collect logs", e)
            return@withContext null
        }
    }

    /**
     * Test method to verify log collection is working
     * Logs collection status to Android logcat for debugging
     */
    fun testLogCollection() {
        Log.d(TAG, "=== LogCollector Test Started ===")
        Log.d(TAG, "Testing log collection functionality")

        // Test 1: Check file system access
        try {
            val dataDir = context.dataDir
            val filesDir = context.filesDir
            val cacheDir = context.cacheDir
            Log.d(TAG, "Data Dir: $dataDir (exists: ${dataDir?.exists()})")
            Log.d(TAG, "Files Dir: $filesDir (exists: ${filesDir?.exists()})")
            Log.d(TAG, "Cache Dir: $cacheDir (exists: ${cacheDir?.exists()})")
        } catch (e: Exception) {
            Log.e(TAG, "File system test failed", e)
        }

        // Test 2: Check logcat command
        try {
            Log.d(TAG, "Testing logcat command...")
            val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "brief", "-t", "5"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var lineCount = 0
            var line: String?
            while (reader.readLine().also { line = it } != null && lineCount < 3) {
                Log.d(TAG, "Sample logcat line: $line")
                lineCount++
            }
            reader.close()
            process.waitFor()
            Log.d(TAG, "Logcat test completed: $lineCount lines read")
        } catch (e: Exception) {
            Log.e(TAG, "Logcat test failed", e)
        }

        // Test 3: Check debug log locations
        try {
            val possibleLocations = listOf(
                File("c:\\Users\\Chaitany Kakde\\StudioProjects\\Oralvis-Orignal\\.cursor\\debug.log"),
                File(context.getExternalFilesDir(null), "debug.log"),
                File(context.filesDir, "debug.log")
            )

            Log.d(TAG, "Checking debug log locations:")
            possibleLocations.forEach { location ->
                Log.d(TAG, "  ${location.absolutePath}: exists=${location.exists()}, size=${location.length()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Debug log location test failed", e)
        }

        Log.d(TAG, "=== LogCollector Test Completed ===")
    }

    /**
     * Shares the log ZIP file using Android's share intent
     */
    fun shareLogZip(zipFile: File) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                zipFile
            )

            val shareIntent = Intent().apply {
                action = Intent.ACTION_SEND
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "OralVis App Logs - $timestamp")
                putExtra(Intent.EXTRA_TEXT, "OralVis app logs collected on ${Build.MODEL} (${Build.VERSION.RELEASE})")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val chooserIntent = Intent.createChooser(shareIntent, "Share OralVis Logs").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(chooserIntent)
            Log.d(TAG, "Share intent started for log file: ${zipFile.name}")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to share log file", e)
        }
    }

    private fun collectAppDebugLogs(tempDir: File): File? {
        return try {
            val appLogFile = File(tempDir, "app_debug_logs.txt")

            // Try multiple locations for app debug logs
            val possibleLogLocations = listOf(
                // Development workspace logs
                File("c:\\Users\\Chaitany Kakde\\StudioProjects\\Oralvis-Orignal\\.cursor\\debug.log"),
                // App external files
                File(context.getExternalFilesDir(null), "debug.log"),
                // App internal files
                File(context.filesDir, "debug.log"),
                // Cache directory
                File(context.cacheDir, "debug.log"),
                // External cache
                context.externalCacheDir?.let { File(it, "debug.log") }
            ).filterNotNull()

            var logsFound = false
            val writer = BufferedWriter(FileWriter(appLogFile))

            writer.write("=== App Debug Logs Collection ===\n")
            writer.write("Collection Time: $timestamp\n")
            writer.write("App Package: ${context.packageName}\n")
            writer.write("=================================\n\n")

            // Try each possible log location
            for ((index, logFile) in possibleLogLocations.withIndex()) {
                try {
                    if (logFile.exists() && logFile.canRead()) {
                        writer.write("--- Log Location ${index + 1}: ${logFile.absolutePath} ---\n")

                        val fileSize = logFile.length()
                        if (fileSize > MAX_LOG_SIZE_BYTES / 4) {
                            writer.write("Warning: Log file is large (${fileSize} bytes), truncating...\n")
                        }

                        logFile.bufferedReader().use { reader ->
                            var lineCount = 0
                            var totalSize = appLogFile.length()
                            var line: String?

                            while (reader.readLine().also { line = it } != null &&
                                   totalSize < MAX_LOG_SIZE_BYTES &&
                                   lineCount < 10000) {
                                writer.write(line)
                                writer.newLine()
                                totalSize += (line?.length ?: 0) + 1
                                lineCount++
                            }

                            writer.write("--- End Location ${index + 1} (${lineCount} lines, ${fileSize} bytes) ---\n\n")
                        }

                        logsFound = true
                        Log.d(TAG, "Collected logs from: ${logFile.absolutePath} (${fileSize} bytes)")
                    }
                } catch (e: Exception) {
                    writer.write("--- Location ${index + 1} Failed ---\n")
                    writer.write("Path: ${logFile.absolutePath}\n")
                    writer.write("Error: ${e.message}\n\n")
                    Log.w(TAG, "Failed to read logs from ${logFile.absolutePath}", e)
                }
            }

            // If no debug logs found, create comprehensive diagnostic info
            if (!logsFound) {
                writer.write("--- No Debug Log Files Found ---\n")
                writer.write("Searched locations:\n")
                possibleLogLocations.forEach { location ->
                    writer.write("- ${location.absolutePath} (exists: ${location.exists()})\n")
                }
                writer.write("\n")
                writer.write("This might mean:\n")
                writer.write("1. App hasn't generated debug logs yet\n")
                writer.write("2. Logs are stored in a different location\n")
                writer.write("3. File permissions prevent access\n")
                writer.write("4. Logs were cleared\n\n")

                // Add some basic app diagnostic info
                writer.write("--- App Diagnostic Info ---\n")
                try {
                    val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                    writer.write("Version: ${packageInfo.versionName} (${packageInfo.longVersionCode})\n")
                    writer.write("First Install: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(packageInfo.firstInstallTime))}\n")
                    writer.write("Last Update: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(packageInfo.lastUpdateTime))}\n")
                } catch (e: Exception) {
                    writer.write("Failed to get package info: ${e.message}\n")
                }
                writer.write("\n")
            }

            writer.close()

            Log.d(TAG, "App debug logs collection completed: ${appLogFile.length()} bytes, logsFound: $logsFound")
            appLogFile

        } catch (e: Exception) {
            Log.e(TAG, "Failed to collect app debug logs", e)
            null
        }
    }

    private fun collectSystemLogs(tempDir: File): File? {
        return try {
            val systemLogFile = File(tempDir, "system_logcat.txt")

            // Multiple fallback strategies for collecting system logs
            var logsCollected = false
            val writer = BufferedWriter(FileWriter(systemLogFile))

            writer.write("=== Android System Logs Collection ===\n")
            writer.write("Collection Time: $timestamp\n")
            writer.write("Device: ${Build.MODEL} (${Build.MANUFACTURER})\n")
            writer.write("Android Version: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
            writer.write("========================================\n\n")

            // Strategy 1: Try to get comprehensive logs with multiple commands
            val logCommands = listOf(
                // Full buffer dump
                arrayOf("logcat", "-d", "-v", "threadtime", "-b", "main", "-b", "system", "-b", "crash"),
                // Recent logs with time filter
                arrayOf("logcat", "-d", "-v", "threadtime", "-t", "10000"),
                // All buffers
                arrayOf("logcat", "-d", "-v", "threadtime", "-b", "all"),
                // Basic dump
                arrayOf("logcat", "-d")
            )

            for ((index, command) in logCommands.withIndex()) {
                if (logsCollected) break

                try {
                    Log.d(TAG, "Trying logcat command ${index + 1}: ${command.joinToString(" ")}")
                    writer.write("--- Logcat Command ${index + 1}: ${command.joinToString(" ")} ---\n")

                    val process = Runtime.getRuntime().exec(command)
                    val reader = BufferedReader(InputStreamReader(process.inputStream))
                    val errorReader = BufferedReader(InputStreamReader(process.errorStream))

                    var lineCount = 0
                    var totalSize = systemLogFile.length()
                    var line: String?

                    // Read stdout
                    while (reader.readLine().also { line = it } != null &&
                           totalSize < MAX_LOG_SIZE_BYTES &&
                           lineCount < 25000) {
                        writer.write(line)
                        writer.newLine()
                        totalSize += (line?.length ?: 0) ?: 0 + 1
                        lineCount++

                        // Filter for app-specific logs to prioritize them
                        if (line?.contains("com.oralvis.oralviscamera") == true) {
                            logsCollected = true
                        }
                    }

                    // Read stderr for any error messages
                    val errorBuilder = StringBuilder()
                    while (errorReader.readLine().also { line = it } != null) {
                        errorBuilder.append(line).append("\n")
                    }

                    reader.close()
                    errorReader.close()
                    process.waitFor()

                    writer.write("--- End Command ${index + 1} (${lineCount} lines) ---\n\n")

                    if (errorBuilder.isNotEmpty()) {
                        writer.write("--- Errors from Command ${index + 1} ---\n")
                        writer.write(errorBuilder.toString())
                        writer.write("\n")
                    }

                    Log.d(TAG, "Command ${index + 1} completed: $lineCount lines")

                    // If we got some logs, mark as collected
                    if (lineCount > 100) {
                        logsCollected = true
                    }

                } catch (e: Exception) {
                    Log.w(TAG, "Command ${index + 1} failed", e)
                    writer.write("--- Command ${index + 1} Failed ---\n")
                    writer.write("Error: ${e.message}\n\n")
                }
            }

            // Strategy 2: If no logs collected, try alternative method
            if (!logsCollected) {
                try {
                    writer.write("--- Alternative Log Collection Method ---\n")

                    // Try using dumpsys for additional system info
                    val dumpsysProcess = Runtime.getRuntime().exec(arrayOf("dumpsys", "package", "com.oralvis.oralviscamera"))
                    val dumpsysReader = BufferedReader(InputStreamReader(dumpsysProcess.inputStream))

                    var line: String?
                    var dumpsysLines = 0
                    while (dumpsysReader.readLine().also { line = it } != null && dumpsysLines < 1000) {
                        writer.write("[DUMPSYS] $line\n")
                        dumpsysLines++
                    }

                    dumpsysReader.close()
                    dumpsysProcess.waitFor()

                    writer.write("--- End Dumpsys (${dumpsysLines} lines) ---\n\n")

                } catch (e: Exception) {
                    Log.w(TAG, "Dumpsys collection failed", e)
                    writer.write("--- Dumpsys Failed ---\n")
                    writer.write("Error: ${e.message}\n\n")
                }
            }

            writer.close()

            // Check if we actually collected meaningful logs
            val finalSize = systemLogFile.length()
            Log.d(TAG, "System logs collection completed: ${finalSize} bytes, logsCollected: $logsCollected")

            if (finalSize < 1000) {
                // Very small file, probably just headers
                systemLogFile.appendText("\n--- LOG COLLECTION NOTES ---\n")
                systemLogFile.appendText("Warning: Very few system logs were collected.\n")
                systemLogFile.appendText("This might be due to:\n")
                systemLogFile.appendText("1. Limited permissions on the device\n")
                systemLogFile.appendText("2. Logcat buffer cleared recently\n")
                systemLogFile.appendText("3. Device security restrictions\n")
                systemLogFile.appendText("4. App running in restricted environment\n\n")
            }

            systemLogFile

        } catch (e: Exception) {
            Log.e(TAG, "Failed to collect system logs", e)
            null
        }
    }

    private fun collectAdditionalDiagnostics(tempDir: File): File? {
        return try {
            val diagnosticsFile = File(tempDir, "additional_diagnostics.txt")

            val writer = BufferedWriter(FileWriter(diagnosticsFile))

            writer.write("=== Additional Diagnostics ===\n")
            writer.write("Collection Time: $timestamp\n")
            writer.write("==============================\n\n")

            // Shared Preferences
            try {
                writer.write("--- Shared Preferences ---\n")
                val prefs = context.getSharedPreferences("oralvis_prefs", Context.MODE_PRIVATE)
                val allPrefs = prefs.all
                if (allPrefs.isEmpty()) {
                    writer.write("No shared preferences found\n")
                } else {
                    for ((key, value) in allPrefs) {
                        writer.write("$key = $value\n")
                    }
                }
                writer.write("\n")
            } catch (e: Exception) {
                writer.write("Failed to read shared preferences: ${e.message}\n\n")
            }

            // Login Manager state
            try {
                writer.write("--- Login Manager State ---\n")
                val loginManager = com.oralvis.oralviscamera.LoginManager(context)
                writer.write("Is logged in: ${loginManager.isLoggedIn()}\n")
                writer.write("Client ID: ${loginManager.getClientId() ?: "null"}\n")
                writer.write("\n")
            } catch (e: Exception) {
                writer.write("Failed to read login state: ${e.message}\n\n")
            }

            // File system information
            try {
                writer.write("--- File System Information ---\n")
                val dataDir = context.dataDir
                val filesDir = context.filesDir
                val cacheDir = context.cacheDir
                val externalFilesDir = context.getExternalFilesDir(null)
                val externalCacheDir = context.externalCacheDir

                writer.write("Data Directory: $dataDir\n")
                writer.write("  Exists: ${dataDir?.exists()}\n")
                writer.write("  Readable: ${dataDir?.canRead()}\n")
                writer.write("  Writable: ${dataDir?.canWrite()}\n")
                writer.write("  Free Space: ${dataDir?.freeSpace} bytes\n")
                writer.write("\n")

                writer.write("Files Directory: $filesDir\n")
                writer.write("  Free Space: ${filesDir?.freeSpace} bytes\n")
                writer.write("\n")

                writer.write("Cache Directory: $cacheDir\n")
                writer.write("  Free Space: ${cacheDir?.freeSpace} bytes\n")
                writer.write("\n")

                writer.write("External Files Directory: $externalFilesDir\n")
                writer.write("  Free Space: ${externalFilesDir?.freeSpace} bytes\n")
                writer.write("\n")

                writer.write("External Cache Directory: $externalCacheDir\n")
                writer.write("  Free Space: ${externalCacheDir?.freeSpace} bytes\n")
                writer.write("\n")

                // List files in key directories
                writer.write("--- Files in Data Directory ---\n")
                dataDir?.listFiles()?.forEach { file ->
                    writer.write("${file.name} (${file.length()} bytes, ${if (file.isDirectory) "dir" else "file"})\n")
                }
                writer.write("\n")

            } catch (e: Exception) {
                writer.write("Failed to read file system info: ${e.message}\n\n")
            }

            // Recent app launches/crashes (if available)
            try {
                writer.write("--- Recent Activity ---\n")
                val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                val runningTasks = activityManager.getRunningTasks(1)
                if (runningTasks.isNotEmpty()) {
                    val task = runningTasks[0]
                    writer.write("Top Activity: ${task.topActivity?.className}\n")
                    writer.write("Task Description: ${task.description}\n")
                    writer.write("Num Activities: ${task.numActivities}\n")
                }
                writer.write("\n")
            } catch (e: Exception) {
                writer.write("Failed to read activity info: ${e.message}\n\n")
            }

            // Memory information
            try {
                writer.write("--- Memory Information ---\n")
                val runtime = Runtime.getRuntime()
                writer.write("Max Memory: ${runtime.maxMemory() / 1024 / 1024} MB\n")
                writer.write("Total Memory: ${runtime.totalMemory() / 1024 / 1024} MB\n")
                writer.write("Free Memory: ${runtime.freeMemory() / 1024 / 1024} MB\n")
                writer.write("Used Memory: ${(runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024} MB\n")
                writer.write("\n")
            } catch (e: Exception) {
                writer.write("Failed to read memory info: ${e.message}\n\n")
            }

            writer.close()

            Log.d(TAG, "Additional diagnostics collected: ${diagnosticsFile.length()} bytes")
            diagnosticsFile

        } catch (e: Exception) {
            Log.e(TAG, "Failed to collect additional diagnostics", e)
            null
        }
    }

    private fun collectDeviceInfo(tempDir: File): File? {
        return try {
            val deviceInfoFile = File(tempDir, "device_info.txt")

            val info = StringBuilder().apply {
                appendLine("=== ORALVIS APP LOG COLLECTION ===")
                appendLine("Collection Time: $timestamp")
                appendLine("Device: ${Build.MODEL} (${Build.BRAND})")
                appendLine("Android Version: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                appendLine("Device ID: ${Build.ID}")
                appendLine("Manufacturer: ${Build.MANUFACTURER}")
                appendLine("Hardware: ${Build.HARDWARE}")
                appendLine("CPU ABI: ${Build.CPU_ABI}")
                appendLine("")

                // App information
                try {
                    val packageInfo: PackageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                    appendLine("=== APP INFORMATION ===")
                    appendLine("Package: ${context.packageName}")
                    appendLine("Version Name: ${packageInfo.versionName}")
                    appendLine("Version Code: ${packageInfo.longVersionCode}")
                    appendLine("First Install: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(packageInfo.firstInstallTime))}")
                    appendLine("Last Update: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(packageInfo.lastUpdateTime))}")
                } catch (e: PackageManager.NameNotFoundException) {
                    appendLine("Failed to get app package info: ${e.message}")
                }

                appendLine("")
                appendLine("=== SYSTEM MEMORY INFO ===")
                val runtime = Runtime.getRuntime()
                val totalMemory = runtime.totalMemory()
                val freeMemory = runtime.freeMemory()
                val maxMemory = runtime.maxMemory()
                appendLine("Total Memory: ${totalMemory / 1024 / 1024} MB")
                appendLine("Free Memory: ${freeMemory / 1024 / 1024} MB")
                appendLine("Max Memory: ${maxMemory / 1024 / 1024} MB")
                appendLine("Used Memory: ${(totalMemory - freeMemory) / 1024 / 1024} MB")

                appendLine("")
                appendLine("=== STORAGE INFO ===")
                val dataDir = context.dataDir
                val externalDir = context.getExternalFilesDir(null)
                appendLine("Data Directory: ${dataDir?.absolutePath}")
                appendLine("External Files Dir: ${externalDir?.absolutePath}")
                dataDir?.let {
                    appendLine("Data Dir Free Space: ${it.freeSpace / 1024 / 1024} MB")
                }
            }

            deviceInfoFile.writeText(info.toString())
            Log.d(TAG, "Device info collected: ${deviceInfoFile.length()} bytes")
            deviceInfoFile

        } catch (e: Exception) {
            Log.e(TAG, "Failed to collect device info", e)
            null
        }
    }

    private fun createZipFile(logFiles: List<File>, tempDir: File): File {
        val zipFile = File(context.cacheDir, zipFileName)

        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zipOut ->
            logFiles.forEach { file ->
                FileInputStream(file).use { fileIn ->
                    BufferedInputStream(fileIn).use { bufferedIn ->
                        val entry = ZipEntry(file.name)
                        zipOut.putNextEntry(entry)

                        val buffer = ByteArray(LOG_BUFFER_SIZE)
                        var len: Int
                        while (bufferedIn.read(buffer).also { len = it } > 0) {
                            zipOut.write(buffer, 0, len)
                        }

                        zipOut.closeEntry()
                        Log.d(TAG, "Added ${file.name} to ZIP (${file.length()} bytes)")
                    }
                }
            }
        }

        Log.d(TAG, "ZIP file created: ${zipFile.absolutePath} (${zipFile.length()} bytes)")
        return zipFile
    }

    private fun cleanupTempFiles(tempDir: File) {
        try {
            tempDir.deleteRecursively()
            Log.d(TAG, "Temporary files cleaned up")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cleanup temp files", e)
        }
    }
}

