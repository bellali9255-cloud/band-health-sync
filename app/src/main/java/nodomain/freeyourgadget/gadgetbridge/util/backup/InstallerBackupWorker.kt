package nodomain.freeyourgadget.gadgetbridge.util.backup

import android.content.Context
import androidx.core.net.toUri
import androidx.work.Worker
import androidx.work.WorkerParameters
import nodomain.freeyourgadget.gadgetbridge.externalevents.InstallerBackupReceiver
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Creates a short-lived, app-supported ZIP backup for the desktop installer.
 *
 * The files live under externalCacheDir so adb's shell user can pull them without relying on
 * android:debuggable / run-as. [InstallerBackupReceiver] is protected by android.permission.DUMP,
 * which keeps the trigger limited to adb shell and privileged system callers.
 */
class InstallerBackupWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : Worker(appContext, workerParams), ZipBackupCallback {
    private var exportSucceeded = false
    private var exportError: String? = null

    override fun doWork(): Result {
        val requestId = inputData.getString(INPUT_REQUEST_ID)
        if (!isValidRequestId(requestId)) {
            LOG.warn("Rejecting invalid installer backup request id")
            return Result.failure()
        }

        val validRequestId = requestId!!
        val directory = getBackupDirectory(applicationContext)
        if (directory == null) {
            LOG.error("External cache directory is unavailable for installer backup")
            return Result.failure()
        }

        val partialFile = File(directory, "$validRequestId.zip.partial")
        val finalFile = File(directory, "$validRequestId.zip")

        deleteIfPresent(partialFile)
        deleteIfPresent(finalFile)
        writeStatus(applicationContext, validRequestId, STATE_RUNNING)

        ZipBackupExportJob(applicationContext, this, partialFile.toUri()).run()

        if (!exportSucceeded || !partialFile.isFile || partialFile.length() < MIN_BACKUP_SIZE_BYTES) {
            deleteIfPresent(partialFile)
            writeStatus(
                applicationContext,
                validRequestId,
                STATE_FAILED,
                error = exportError ?: "ZIP export did not produce a usable file"
            )
            return Result.failure()
        }

        if (!partialFile.renameTo(finalFile)) {
            deleteIfPresent(partialFile)
            writeStatus(applicationContext, validRequestId, STATE_FAILED, error = "Unable to finalize ZIP backup")
            return Result.failure()
        }

        writeStatus(applicationContext, validRequestId, STATE_SUCCESS, size = finalFile.length())
        LOG.info("Installer backup {} completed with {} bytes", validRequestId, finalFile.length())
        return Result.success()
    }

    override fun onProgress(progress: Int, message: String?) {
        // The installer polls the status file. UI progress and notifications are intentionally omitted.
    }

    override fun onSuccess(warnings: String?) {
        exportSucceeded = true
        if (!warnings.isNullOrBlank()) {
            LOG.warn("Installer backup completed with warnings: {}", warnings)
        }
    }

    override fun onFailure(errorMessage: String?) {
        exportError = sanitizeStatusValue(errorMessage ?: "Unknown ZIP export error")
    }

    companion object {
        private val LOG: Logger = LoggerFactory.getLogger(InstallerBackupWorker::class.java)

        const val INPUT_REQUEST_ID = "request_id"
        const val UNIQUE_WORK_NAME = "installer_zip_backup"
        const val WORK_TAG = "installer_zip_backup"

        const val STATE_QUEUED = "queued"
        const val STATE_RUNNING = "running"
        const val STATE_SUCCESS = "success"
        const val STATE_FAILED = "failed"

        private const val DIRECTORY_NAME = "installer-backup"
        private const val MIN_BACKUP_SIZE_BYTES = 1024L
        private val REQUEST_ID_PATTERN = Regex("^[A-Za-z0-9_-]{1,64}$")

        @JvmStatic
        fun isValidRequestId(requestId: String?): Boolean =
            requestId != null && REQUEST_ID_PATTERN.matches(requestId)

        @JvmStatic
        fun prepareRequest(context: Context, requestId: String): Boolean {
            if (!isValidRequestId(requestId)) return false
            val directory = getBackupDirectory(context) ?: return false

            directory.listFiles()?.forEach { file ->
                if (isInstallerGeneratedFile(file.name)) {
                    deleteIfPresent(file)
                }
            }

            return writeStatus(context, requestId, STATE_QUEUED)
        }

        @JvmStatic
        fun cleanupRequest(context: Context, requestId: String): Boolean {
            if (!isValidRequestId(requestId)) return false
            val directory = getBackupDirectory(context) ?: return false
            var success = true
            for (suffix in arrayOf(".zip", ".zip.partial", ".status", ".status.tmp")) {
                val file = File(directory, requestId + suffix)
                if (file.exists() && !file.delete()) {
                    LOG.warn("Unable to delete installer backup temporary file {}", file)
                    success = false
                }
            }
            return success
        }

        private fun getBackupDirectory(context: Context): File? {
            val externalCacheDir = context.externalCacheDir ?: return null
            val directory = File(externalCacheDir, DIRECTORY_NAME)
            return if (directory.isDirectory || directory.mkdirs()) directory else null
        }

        private fun writeStatus(
            context: Context,
            requestId: String,
            state: String,
            size: Long? = null,
            error: String? = null
        ): Boolean {
            val directory = getBackupDirectory(context) ?: return false
            val statusFile = File(directory, "$requestId.status")
            val temporaryStatusFile = File(directory, "$requestId.status.tmp")
            val lines = mutableListOf(
                "version=1",
                "request_id=$requestId",
                "state=$state"
            )
            if (size != null) lines += "size=$size"
            if (error != null) lines += "error=${sanitizeStatusValue(error)}"

            return try {
                temporaryStatusFile.writeText(lines.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
                deleteIfPresent(statusFile)
                if (!temporaryStatusFile.renameTo(statusFile)) {
                    temporaryStatusFile.copyTo(statusFile, overwrite = true)
                    deleteIfPresent(temporaryStatusFile)
                }
                true
            } catch (e: Exception) {
                LOG.error("Unable to write installer backup status", e)
                false
            }
        }

        private fun sanitizeStatusValue(value: String): String =
            value.replace('\r', ' ').replace('\n', ' ').take(500)

        private fun isInstallerGeneratedFile(name: String): Boolean =
            name.matches(Regex("^[A-Za-z0-9_-]{1,64}\\.(zip|zip\\.partial|status|status\\.tmp)$"))

        private fun deleteIfPresent(file: File) {
            if (file.exists() && !file.delete()) {
                LOG.warn("Unable to delete installer backup temporary file {}", file)
            }
        }
    }
}
