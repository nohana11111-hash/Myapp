package com.example.videocomposer

import android.content.Context
import android.net.Uri
import android.os.Environment
import com.arthenica.ffmpegkit.FFmpegKit
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

    /**
     * Composes a video by combining:
     * 1. Input video (scaled/padded to 1080x1920)
     * 2. prompt.jpg (middle, shown for 5 seconds)
     * 3. end.jpg (end, shown for 5 seconds)
     *
     * @param inputUri URI of the selected video
     * @param onProgress Progress callback (0-100)
     * @return Result containing the output file or error
     */
    suspend fun compose(
        inputUri: Uri,
        onProgress: (Int) -> Unit
    ): Result<File> {
        return try {
            onProgress(5)

            // 1. Copy input video to internal cache
            val inputVideo = copyUriToCache(inputUri, "input.mp4")
            onProgress(10)

            // 2. Extract prompt.jpg and end.jpg from assets
            val promptImg = copyAssetToCache("prompt.jpg")
            val endImg = copyAssetToCache("end.jpg")
            onProgress(15)

            // 3. Prepare output folder
            val outputDir = getOutputDir()
            val outputFile = File(outputDir, "composed_${System.currentTimeMillis()}.mp4")

            // 4. Build FFmpeg command
            val cmd = buildCommand(
                inputVideo.absolutePath,
                promptImg.absolutePath,
                endImg.absolutePath,
                outputFile.absolutePath
            )

            onProgress(20)

            // 5. Execute FFmpeg synchronously
            val session = FFmpegKit.execute(cmd)

            // 6. Check result
            if (session.returnCode == null || !session.returnCode.isValueSuccess) {
                val logs = session.allLogsAsString
                return Result.failure(
                    RuntimeException("FFmpeg failed: ${session.returnCode}\n$logs")
                )
            }

            onProgress(100)
            Result.success(outputFile)

        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Builds FFmpeg command to:
     * - Scale input video to 1080x1920 (maintain aspect ratio, pad if needed)
     * - Loop prompt.jpg for IMAGE_DURATION seconds
     * - Loop end.jpg for IMAGE_DURATION seconds
     * - Concatenate all three videos
     * - Encode with H.264 (libx264)
     */
    private fun buildCommand(input: String, prompt: String, end: String, output: String): String {
        return "-y " +
            "-i \"$input\" " +
            "-loop 1 -t $IMAGE_DURATION -i \"$prompt\" " +
            "-loop 1 -t $IMAGE_DURATION -i \"$end\" " +
            "-filter_complex \"" +
                // Scale input video
                "[0:v]scale=$OUTPUT_WIDTH:$OUTPUT_HEIGHT:" +
                    "force_original_aspect_ratio=decrease," +
                    "pad=$OUTPUT_WIDTH:$OUTPUT_HEIGHT:(ow-iw)/2:(oh-ih)/2," +
                    "setsar=1,fps=$FPS,format=yuv420p[v0];" +
                // Scale prompt image
                "[1:v]scale=$OUTPUT_WIDTH:$OUTPUT_HEIGHT," +
                    "setsar=1,fps=$FPS,format=yuv420p[v1];" +
                // Scale end image
                "[2:v]scale=$OUTPUT_WIDTH:$OUTPUT_HEIGHT," +
                    "setsar=1,fps=$FPS,format=yuv420p[v2];" +
                // Concatenate all three
                "[v0][v1][v2]concat=n=3:v=1:a=0[outv]\" " +
            "-map \"[outv]\" " +
            "-c:v libx264 -preset ultrafast -crf 23 -pix_fmt yuv420p " +
            "-movflags +faststart " +
            "\"$output\""
    }

    /**
     * Copies video from URI to cache directory
     */
    private fun copyUriToCache(uri: Uri, name: String): File {
        val file = File(context.cacheDir, name)
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(file).use { out -> input.copyTo(out) }
        } ?: throw IllegalStateException("Cannot read input video")
        return file
    }

    /**
     * Copies asset file to cache directory
     */
    private fun copyAssetToCache(assetName: String): File {
        val file = File(context.cacheDir, assetName)
        if (!file.exists()) {
            context.assets.open(assetName).use { input ->
                FileOutputStream(file).use { out -> input.copyTo(out) }
            }
        }
        return file
    }

    /**
     * Gets or creates output directory in internal storage
     * Path: /storage/emulated/0/vidz/
     */
    private fun getOutputDir(): File {
        val dir = File(Environment.getExternalStorageDirectory(), OUTPUT_FOLDER)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }
}
