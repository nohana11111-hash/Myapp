package com.example.videocomposer

import android.content.Context
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFmpegSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class VideoProcessor(private val context: Context) {

    companion object {
        private const val OUTPUT_WIDTH = 1080
        private const val OUTPUT_HEIGHT = 1920
        private const val IMAGE_DURATION = 5      // seconds each image is shown
        private const val FPS = 30
        private const val OUTPUT_FOLDER = "vidz"
    }

    suspend fun compose(
        inputUri: Uri,
        onProgress: (Int) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            // 1. Copy input video to internal cache
            val inputVideo = copyUriToCache(inputUri, "input.mp4")

            // 2. Extract prompt.jpg and end.jpg from assets
            val promptImg = copyAssetToCache("prompt.jpg")
            val endImg = copyAssetToCache("end.jpg")

            // 3. Prepare output folder
            val outputDir = getOutputDir()
            val outputFile = File(outputDir, "composed_${System.currentTimeMillis()}.mp4")

            // 4. Build FFmpeg command
            //    - Scale input to 1080x1920 (pad to fit)
            //    - Loop each image for IMAGE_DURATION seconds
            //    - Concatenate: [input][prompt][end]
            //    - Encode with libx264 (H.264/AVC)
            val cmd = buildCommand(
                inputVideo.absolutePath,
                promptImg.absolutePath,
                endImg.absolutePath,
                outputFile.absolutePath
            )

            // 5. Execute FFmpeg
            val session: FFmpegSession = FFmpegKitConfig.init(context)
            val ffmpegSession = com.arthenica.ffmpegkit.FFmpegKit.executeAsync(
                cmd,
                { /* complete */ },
                { /* log */ },
            )
            // Use synchronous execute with progress callback
            val syncSession = com.arthenica.ffmpegkit.FFmpegKit.execute(cmd)

            if (!syncSession.returnCode.isValueSuccess) {
                val logs = syncSession.allLogsAsString
                return@withContext Result.failure(
                    RuntimeException("FFmpeg failed: ${syncSession.returnCode}\n$logs")
                )
            }

            onProgress(100)
            Result.success(outputFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun buildCommand(input: String, prompt: String, end: String, output: String): String {
        // Filter graph:
        // [v0] = scaled input video (1080x1920, padded)
        // [v1] = prompt.jpg looped for IMAGE_DURATION seconds
        // [v2] = end.jpg looped for IMAGE_DURATION seconds
        // concat them, encode with libx264 (AVC)
        return "-y " +
            "-i \"$input\" " +
            "-loop 1 -t $IMAGE_DURATION -i \"$prompt\" " +
            "-loop 1 -t $IMAGE_DURATION -i \"$end\" " +
            "-filter_complex \"" +
                "[0:v]scale=$OUTPUT_WIDTH:$OUTPUT_HEIGHT:" +
                    "force_original_aspect_ratio=decrease," +
                    "pad=$OUTPUT_WIDTH:$OUTPUT_HEIGHT:(ow-iw)/2:(oh-ih)/2," +
                    "setsar=1,fps=$FPS,format=yuv420p[v0];" +
                "[1:v]scale=$OUTPUT_WIDTH:$OUTPUT_HEIGHT," +
                    "setsar=1,fps=$FPS,format=yuv420p[v1];" +
                "[2:v]scale=$OUTPUT_WIDTH:$OUTPUT_HEIGHT," +
                    "setsar=1,fps=$FPS,format=yuv420p[v2];" +
                "[v0][v1][v2]concat=n=3:v=1:a=0[outv]\" " +
            "-map \"[outv]\" " +
            "-c:v libx264 -preset ultrafast -crf 23 -pix_fmt yuv420p " +
            "-movflags +faststart " +
            "\"$output\""
    }

    private fun copyUriToCache(uri: Uri, name: String): File {
        val file = File(context.cacheDir, name)
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(file).use { out -> input.copyTo(out) }
        } ?: throw IllegalStateException("Cannot read input video")
        return file
    }

    private fun copyAssetToCache(assetName: String): File {
        val file = File(context.cacheDir, assetName)
        if (!file.exists()) {
            context.assets.open(assetName).use { input ->
                FileOutputStream(file).use { out -> input.copyTo(out) }
            }
        }
        return file
    }

    private fun getOutputDir(): File {
        // Save to /storage/emulated/0/vidz
        val dir = File(Environment.getExternalStorageDirectory(), OUTPUT_FOLDER)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }
}
