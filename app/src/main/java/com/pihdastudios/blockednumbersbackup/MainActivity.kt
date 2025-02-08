/*
 * Copyright (c) 2025. Pihdastudios
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.pihdastudios.blockednumbersbackup

import android.app.Activity
import android.app.role.RoleManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.BlockedNumberContract
import android.telecom.TelecomManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.io.File

// Result data classes for our operations.
data class ImportResult(
    val success: Boolean,
    val totalLines: Int,
    val imported: Int,
    val error: String? = null
)

data class ExportResult(
    val success: Boolean,
    val filePath: String? = null,
    val error: String? = null
)

data class BulkInsertResult(
    val success: Boolean,
    val total: Int,
    val imported: Int,
    val error: String? = null
)

/**
 * A native Kotlin manager for system blocked numbers.
 *
 * NOTE: Some operations (like becoming the default dialer or managing
 * blocked numbers) require starting an Activity for a result.
 */
class BlockedNumbersManager(private val activity: Activity) {

    private val context: Context = activity.applicationContext
    private val telecomManager: TelecomManager =
        context.getSystemService(TelecomManager::class.java)
            ?: throw IllegalStateException("TelecomManager not available")

    /**
     * Query the system’s blocked numbers database and return a list of numbers.
     */
    private fun getBlockedNumbers(): List<String> {
        val numbers = mutableListOf<String>()
        val projection = arrayOf("_id", "original_number", "e164_number")
        val cursor: Cursor? = context.contentResolver.query(
            BlockedNumberContract.BlockedNumbers.CONTENT_URI,
            projection,
            null,
            null,
            null
        )
        cursor?.use {
            while (it.moveToNext()) {
                // Column 1 is assumed to be the "original_number"
                val number = it.getString(1)
                if (number != null) {
                    numbers.add(number)
                }
            }
        }
        return numbers
    }

    /**
     * Return the total number of blocked numbers.
     */
    fun totalBlockedNumbers(): Int {
        if (isDefaultDialer()) {
            return getBlockedNumbers().size
        }
        return 0
    }


    /**
     * Insert a blocked number if it isn’t already blocked.
     *
     * @return true if the number was inserted, false if already blocked or on error.
     */
    fun insert(number: String): Boolean {
        return try {
            if (!BlockedNumberContract.isBlocked(context, number)) {
                val cv = ContentValues().apply {
                    put("original_number", number)
                }
                context.contentResolver.insert(
                    BlockedNumberContract.BlockedNumbers.CONTENT_URI,
                    cv
                )
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Bulk insert a list of numbers.
     *
     * @return a BulkInsertResult containing totals and any error message.
     */
    fun bulkInsert(numbers: List<String>): BulkInsertResult {
        var importedCount = 0
        val total = numbers.size
        try {
            numbers.forEach { num ->
                if (!BlockedNumberContract.isBlocked(context, num)) {
                    val cv = ContentValues().apply {
                        put("original_number", num)
                    }
                    context.contentResolver.insert(
                        BlockedNumberContract.BlockedNumbers.CONTENT_URI,
                        cv
                    )
                    importedCount++
                }
            }
            return BulkInsertResult(success = true, total = total, imported = importedCount)
        } catch (e: Exception) {
            return BulkInsertResult(
                success = false,
                total = total,
                imported = importedCount,
                error = e.message
            )
        }
    }

    /**
     * Delete a blocked number.
     *
     * Note: The system API does not offer a direct delete method.
     * One workaround is to insert the number (if needed) to get its URI and then delete it.
     *
     * @return true if deletion succeeded.
     */
    fun delete(number: String): Boolean {
        return try {
            val cv = ContentValues().apply {
                put("original_number", number)
            }
            // Insert to get the URI of the blocked number row.
            val uri: Uri? = context.contentResolver.insert(
                BlockedNumberContract.BlockedNumbers.CONTENT_URI,
                cv
            )
            if (uri != null) {
                val rows = context.contentResolver.delete(uri, null, null)
                rows > 0
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Delete all blocked numbers.
     *
     * @return the number of rows deleted, or -1 if an error occurred.
     */
    fun deleteAll(): Int {
        return try {
            context.contentResolver.delete(
                BlockedNumberContract.BlockedNumbers.CONTENT_URI,
                null,
                null
            )
        } catch (e: Exception) {
            e.printStackTrace()
            -1
        }
    }

    /**
     * Import blocked numbers from a CSV file.
     *
     * CSV format: each line contains one or more blocked numbers. If a line contains
     * commas, each comma‐separated token is treated as a number.
     */
    fun importFromCsv(file: File): ImportResult {
        try {
            if (!file.exists()) {
                return ImportResult(
                    success = false,
                    totalLines = 0,
                    imported = 0,
                    error = "File does not exist"
                )
            }
            // Read the file line by line.
            val lines = file.readLines().filter { it.isNotBlank() }
            if (lines.isEmpty()) {
                return ImportResult(
                    success = false,
                    totalLines = 0,
                    imported = 0,
                    error = "CSV is empty"
                )
            }
            var importedCount = 0
            // For each line, split on commas (in case multiple numbers are on one line)
            lines.forEach { line ->
                val numbersInLine = line.split(",")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                numbersInLine.forEach { number ->
                    if (!BlockedNumberContract.isBlocked(context, number)) {
                        val cv = ContentValues().apply {
                            put("original_number", number)
                        }
                        context.contentResolver.insert(
                            BlockedNumberContract.BlockedNumbers.CONTENT_URI,
                            cv
                        )
                        importedCount++
                    }
                }
            }
            return ImportResult(
                success = true,
                totalLines = lines.size,
                imported = importedCount
            )
        } catch (e: Exception) {
            return ImportResult(success = false, totalLines = 0, imported = 0, error = e.message)
        }
    }

    /**
     * Export the current blocked numbers to a CSV file.
     *
     * The CSV file will contain one blocked number per line.
     */
    fun exportToCsv(file: File): ExportResult {
        return try {
            val numbers = getBlockedNumbers()
            if (numbers.isEmpty()) {
                return ExportResult(success = false, error = "No blocked numbers to export")
            }
            // Write each number on a separate line.
            file.writeText(numbers.joinToString(separator = "\n"))
            ExportResult(success = true, filePath = file.absolutePath)
        } catch (e: Exception) {
            ExportResult(success = false, error = e.message)
        }
    }

    /**
     * Check whether the current app is set as the default dialer.
     */
    fun isDefaultDialer(): Boolean =
        context.packageName == telecomManager.defaultDialerPackage

    /**
     * Request permission to become the default dialer.
     *
     * This method starts an activity for result. Use [DEFAULT_DIALER_REQUEST]
     * to filter the result in your activity’s onActivityResult.
     */
    fun requestDefaultDialerPermission() {
        val roleManager = activity.getSystemService(Context.ROLE_SERVICE) as RoleManager
        if (roleManager.isRoleAvailable(RoleManager.ROLE_DIALER) &&
            !roleManager.isRoleHeld(RoleManager.ROLE_DIALER)
        ) {
            val roleIntent = roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER)
            activity.startActivityForResult(roleIntent, DEFAULT_DIALER_REQUEST)
        }
    }


    companion object {
        const val DEFAULT_DIALER_REQUEST = 123
    }
}

class MainActivity : ComponentActivity() {

    private lateinit var blockedNumbersManager: BlockedNumbersManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize blocked numbers manager
        blockedNumbersManager = BlockedNumbersManager(this)

        // Using mutableStateOf to track totalBlocked in Compose state
        val totalBlockedState = mutableIntStateOf(blockedNumbersManager.totalBlockedNumbers())

        // Register file picker launcher for importing files
        val filePickerLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == RESULT_OK) {
                    result.data?.data?.let { uri ->
                        val file = createTempFileFromUri(uri)
                        if (file != null) {
                            val resultData = blockedNumbersManager.importFromCsv(file)
                            val message = if (resultData.success)
                                "Imported ${blockedNumbersManager.totalBlockedNumbers()} numbers"
                            else
                                "Import failed: ${resultData.error}"
                            Toast.makeText(this, message, Toast.LENGTH_LONG).show()

                            // Update totalBlocked after importing
                            totalBlockedState.intValue = blockedNumbersManager.totalBlockedNumbers()
                        } else {
                            Toast.makeText(this, "Unable to read file", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }

        setContent {
            MainScreen(
                totalBlocked = totalBlockedState.intValue,
                onExport = { exportBlockedNumbers() },
                onImport = {
                    // Launch file picker
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "text/*"
                    }
                    filePickerLauncher.launch(intent)
                },
                onSetDefault = {
                    setDefaultPhoneApp()
                    // TODO: Update number after setting default phone app
                    totalBlockedState.intValue = blockedNumbersManager.totalBlockedNumbers()
                },
                onDeleteAll = {
                    val rowsDeleted = blockedNumbersManager.deleteAll()
                    if (rowsDeleted > 0) {
                        Toast.makeText(
                            this,
                            "Deleted $rowsDeleted blocked numbers",
                            Toast.LENGTH_LONG
                        ).show()
                    } else if (rowsDeleted == 0) {
                        Toast.makeText(this, "No blocked numbers found", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, "Error deleting blocked numbers", Toast.LENGTH_LONG)
                            .show()
                    }

                    // Update totalBlocked after deletion
                    totalBlockedState.intValue = blockedNumbersManager.totalBlockedNumbers()
                },
            )
        }
    }

    /**
     * Export blocked numbers to a CSV file.
     */
    private fun exportBlockedNumbers() {
        val file = File(getExternalFilesDir(null), "blocked_numbers.csv")
        val result = blockedNumbersManager.exportToCsv(file)
        if (result.success) {
            Toast.makeText(this, "Exported to ${result.filePath}", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "Export failed: ${result.error}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Request to be set as the default dialer (phone app).
     */
    private fun setDefaultPhoneApp() {
        blockedNumbersManager.requestDefaultDialerPermission()
    }

    /**
     * Helper method that creates a temporary File from a content URI.
     */
    private fun createTempFileFromUri(uri: Uri): File? {
        return try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val tempFile = File.createTempFile("import", ".csv", cacheDir)
                tempFile.outputStream().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
                tempFile
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}

@Composable
fun MainScreen(
    totalBlocked: Int,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onSetDefault: () -> Unit,
    onDeleteAll: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "Total Blocked Numbers: $totalBlocked")
        Button(
            onClick = onExport,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = "Export Blocked Numbers")
        }
        Button(
            onClick = onImport,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = "Import Blocked Numbers")
        }
        Button(
            onClick = onSetDefault,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = "Set Default Phone App")
        }

        Button(
            onClick = onDeleteAll,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = "Delete All Blocked Numbers")
        }
    }
}
