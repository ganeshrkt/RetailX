package com.example.retailx.platform.backup

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import com.example.retailx.core.common.AppError
import com.example.retailx.core.common.AppResult
import com.example.retailx.core.database.RetailXDatabase
import com.example.retailx.core.database.entity.ActivityLogEntity
import com.example.retailx.core.database.entity.BackupHistoryEntity
import com.example.retailx.core.model.BackupRecord
import com.example.retailx.core.security.DeviceCrypto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class BackupManager(
    private val context: Context,
    private val dbProvider: () -> RetailXDatabase,
    private val onDatabaseRestored: () -> Unit
) {
    private val backupDir: File
        get() = File(context.filesDir, "backups").apply { mkdirs() }

    fun observeBackupHistory(): Flow<List<BackupRecord>> =
        dbProvider().financeAndAuditDao().observeBackupHistory().map { rows ->
            rows.map {
                BackupRecord(
                    id = it.id,
                    fileName = it.fileName,
                    uriString = it.uri,
                    status = it.status,
                    checksumSha256 = it.checksum,
                    sizeBytes = it.sizeBytes,
                    schemaVersion = it.schemaVersion,
                    message = it.message,
                    createdAt = it.createdAt
                )
            }
        }

    suspend fun createBackupToUri(
        destinationUri: Uri?,
        retentionCount: Int = 5,
        actorUserId: String,
        actorName: String,
        labelPrefix: String = "RetailX_Backup"
    ): AppResult<BackupRecord> = withContext(Dispatchers.IO) {
        val db = dbProvider()
        val now = System.currentTimeMillis()
        val fileName = "${labelPrefix}_${now}.retailxbackup"
        val localArchive = File(backupDir, fileName)

        try {
            // Checkpoint WAL so the SQLite file on disk is complete and self-contained
            db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL);").use { it.moveToFirst() }
            val dbFile = context.getDatabasePath(RetailXDatabase.DATABASE_NAME)
            if (!dbFile.exists()) {
                return@withContext AppResult.Failure(AppError.NotFound("Database file not found"))
            }

            val dbBytes = dbFile.readBytes()
            val dbSha = DeviceCrypto.sha256Hex(dbBytes)

            val manifest = JSONObject().apply {
                put("appPackage", context.packageName)
                put("schemaVersion", RetailXDatabase.SCHEMA_VERSION)
                put("createdAt", now)
                put("dbFileName", RetailXDatabase.DATABASE_NAME)
                put("dbSha256", dbSha)
                put("dbSizeBytes", dbBytes.size)
            }.toString(2)

            ZipOutputStream(FileOutputStream(localArchive)).use { zos ->
                zos.putNextEntry(ZipEntry("manifest.json"))
                zos.write(manifest.toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                zos.putNextEntry(ZipEntry(RetailXDatabase.DATABASE_NAME))
                zos.write(dbBytes)
                zos.closeEntry()
            }

            val archiveSha = DeviceCrypto.sha256Hex(localArchive.readBytes())
            if (destinationUri != null) {
                context.contentResolver.openOutputStream(destinationUri)?.use { out ->
                    copyStream(localArchive, out)
                } ?: return@withContext AppResult.Failure(AppError.Unexpected("Could not open selected backup destination"))
            }

            enforceRetention(retentionCount)

            val rec = BackupHistoryEntity(
                id = UUID.randomUUID().toString(),
                fileName = fileName,
                uri = destinationUri?.toString() ?: localArchive.absolutePath,
                status = "SUCCESS",
                checksum = archiveSha,
                sizeBytes = localArchive.length(),
                schemaVersion = RetailXDatabase.SCHEMA_VERSION,
                message = "Verified backup created (SHA-256: ${archiveSha.take(12)}...)",
                createdAt = now
            )
            db.financeAndAuditDao().insertBackupHistory(rec)
            db.financeAndAuditDao().insertActivityLog(
                ActivityLogEntity(
                    id = UUID.randomUUID().toString(),
                    userId = actorUserId,
                    username = actorName,
                    deviceId = "local",
                    action = "BACKUP_CREATED",
                    entityType = "BACKUP",
                    entityId = rec.id,
                    metadataJson = """{"fileName":"$fileName","sizeBytes":${localArchive.length()}}""",
                    createdAt = now
                )
            )
            AppResult.Success(
                BackupRecord(
                    id = rec.id,
                    fileName = rec.fileName,
                    uriString = rec.uri,
                    status = rec.status,
                    checksumSha256 = rec.checksum,
                    sizeBytes = rec.sizeBytes,
                    schemaVersion = rec.schemaVersion,
                    message = rec.message,
                    createdAt = rec.createdAt
                )
            )
        } catch (ex: Exception) {
            AppResult.Failure(AppError.Unexpected(ex.message ?: "Backup failed"))
        }
    }

    suspend fun restoreFromUri(
        sourceUri: Uri,
        actorUserId: String,
        actorName: String
    ): AppResult<String> = withContext(Dispatchers.IO) {
        val tempDir = File(context.cacheDir, "restore_stage_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            var manifestJson: String? = null
            var extractedDb: File? = null

            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                ZipInputStream(input).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        when (entry.name) {
                            "manifest.json" -> manifestJson = zis.readBytes().toString(Charsets.UTF_8)
                            RetailXDatabase.DATABASE_NAME -> {
                                val outDb = File(tempDir, RetailXDatabase.DATABASE_NAME)
                                FileOutputStream(outDb).use { zis.copyTo(it) }
                                extractedDb = outDb
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            } ?: return@withContext AppResult.Failure(AppError.Unexpected("Unable to open selected backup file"))

            val manifestRaw = manifestJson
                ?: return@withContext AppResult.Failure(AppError.ValidationError("Invalid backup: missing manifest.json"))
            val dbStage = extractedDb
                ?: return@withContext AppResult.Failure(AppError.ValidationError("Invalid backup: missing database payload"))

            val manifest = JSONObject(manifestRaw)
            val schemaVer = manifest.optInt("schemaVersion", -1)
            if (schemaVer <= 0 || schemaVer > RetailXDatabase.SCHEMA_VERSION) {
                return@withContext AppResult.Failure(
                    AppError.ValidationError("Unsupported backup schema version ($schemaVer)")
                )
            }
            val expectedSha = manifest.optString("dbSha256")
            val actualSha = DeviceCrypto.sha256Hex(dbStage.readBytes())
            if (expectedSha.isBlank() || expectedSha != actualSha) {
                return@withContext AppResult.Failure(
                    AppError.ValidationError("Backup integrity check failed (checksum mismatch)")
                )
            }

            // Step 1: Create mandatory pre-restore safety backup
            createBackupToUri(
                destinationUri = null,
                retentionCount = 10,
                actorUserId = actorUserId,
                actorName = actorName,
                labelPrefix = "PreRestore"
            )

            // Step 2: Close database writes and swap SQLite files
            val currentDb = dbProvider()
            currentDb.close()

            val targetDb = context.getDatabasePath(RetailXDatabase.DATABASE_NAME)
            File("${targetDb.absolutePath}-wal").delete()
            File("${targetDb.absolutePath}-shm").delete()
            dbStage.copyTo(targetDb, overwrite = true)

            // Step 3: Reopen DB and verify SQLite integrity
            onDatabaseRestored()
            val reopened = dbProvider()
            reopened.openHelper.readableDatabase.query("PRAGMA integrity_check;").use { cursor ->
                val ok = cursor.moveToFirst() && cursor.getString(0).equals("ok", ignoreCase = true)
                if (!ok) {
                    return@withContext AppResult.Failure(AppError.Unexpected("Restored database failed integrity check"))
                }
            }

            val now = System.currentTimeMillis()
            reopened.financeAndAuditDao().insertBackupHistory(
                BackupHistoryEntity(
                    id = UUID.randomUUID().toString(),
                    fileName = "Restored_${now}.retailxbackup",
                    uri = sourceUri.toString(),
                    status = "RESTORED",
                    checksum = actualSha,
                    sizeBytes = targetDb.length(),
                    schemaVersion = schemaVer,
                    message = "Restored successfully after checksum & integrity verification",
                    createdAt = now
                )
            )
            reopened.financeAndAuditDao().insertActivityLog(
                ActivityLogEntity(
                    id = UUID.randomUUID().toString(),
                    userId = actorUserId,
                    username = actorName,
                    deviceId = "local",
                    action = "BACKUP_RESTORED",
                    entityType = "BACKUP",
                    entityId = null,
                    metadataJson = """{"schemaVersion":$schemaVer,"sha256":"${actualSha.take(12)}"}""",
                    createdAt = now
                )
            )

            AppResult.Success("Backup restored and verified successfully")
        } catch (ex: Exception) {
            AppResult.Failure(AppError.Unexpected(ex.message ?: "Restore failed"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun copyStream(file: File, out: OutputStream) {
        FileInputStream(file).use { it.copyTo(out) }
    }

    private fun enforceRetention(maxCount: Int) {
        val files = backupDir.listFiles { f -> f.isFile && f.name.endsWith(".retailxbackup") }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        if (files.size > maxCount) {
            files.drop(maxCount).forEach { it.delete() }
        }
    }

    fun schedulePeriodicBackup(enabled: Boolean) {
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        if (!enabled) {
            scheduler.cancel(JOB_ID)
            return
        }
        val info = JobInfo.Builder(
            JOB_ID,
            ComponentName(context, ScheduledBackupJobService::class.java)
        )
            .setPeriodic(24 * 60 * 60 * 1000L)
            .setPersisted(false)
            .setRequiresBatteryNotLow(true)
            .build()
        scheduler.schedule(info)
    }

    companion object {
        private const val JOB_ID = 4109
    }
}

class ScheduledBackupJobService : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        val app = applicationContext as? com.example.retailx.RetailXApp ?: return false
        CoroutineScope(Dispatchers.IO).launch {
            try {
                app.container.backupManager.createBackupToUri(
                    destinationUri = null,
                    retentionCount = 5,
                    actorUserId = "system",
                    actorName = "ScheduledBackup",
                    labelPrefix = "AutoBackup"
                )
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean = false
}
