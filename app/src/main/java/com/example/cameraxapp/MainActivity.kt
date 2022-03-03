package com.example.cameraxapp

import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.video.VideoCapture
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import com.example.cameraxapp.databinding.ActivityMainBinding
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import android.Manifest
import android.content.pm.PackageManager
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import android.provider.MediaStore
import android.content.ContentValues
import android.util.Log
import java.lang.Exception

typealias LumaListener = (luma: Double) -> Unit

class MainActivity : AppCompatActivity() {
    private lateinit var viewBinding: ActivityMainBinding

    // ImageCapture　写真を撮るための基本的なクラス
    private var imageCapture: ImageCapture? = null

    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null

    private lateinit var cameraExecuter: ExecutorService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        // Request camera permissions
        if (allPermissionGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        // Set up the listeners for take photo and video capture buttons
        viewBinding.imageCaptureButton.setOnClickListener{takePhoto()}
        viewBinding.videoCaptureButton.setOnClickListener { captureVideo() }

        cameraExecuter = Executors.newSingleThreadExecutor()
    }

    private fun takePhoto() {
        // Get a stable reference of the modifiable image capture use case
        val imageCapture = imageCapture ?: return

        // Create time stamped name and MediaStore entry.
        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.JAPAN).format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if(Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraX-Image")
            }
        }

        // Create output options object which contains file + metadata
        val outputOptions = ImageCapture.OutputFileOptions.Builder(contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues).build()

        // Set up image capture listener, which is triggered after photo has been taken
        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                // 画像キャプチャ失敗
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                }

                override  fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val msg = "Photo capture succeed: ${output.savedUri}"
                    Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                    Log.d(TAG, msg)
                }
            }
        )
    }

    // Implements VideoCapture use case, including start and stop capturing.
    private fun captureVideo() {
        val videoCapture = this.videoCapture ?: return

        // CameraXによるリクエストアクションが完了するまでUIを無効にする
        viewBinding.videoCaptureButton.isEnabled = false

        val curRecording = recording
        if (curRecording != null) {
            // Stop the current recording session.
            curRecording.stop()
            recording = null
            return
        }

        // create and start a new recording session
        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.JAPAN).format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CameraX-Video")
            }
        }

        val mediaStoreOutputOptions = MediaStoreOutputOptions
            .Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(contentValues)
            .build()
        recording = videoCapture.output.prepareRecording(this, mediaStoreOutputOptions)
            .apply { // オーディオを有効にする
                if (PermissionChecker.checkSelfPermission(this@MainActivity,
                    Manifest.permission.RECORD_AUDIO) == PermissionChecker.PERMISSION_GRANTED) {
                    withAudioEnabled()
                }
            }
            .start(ContextCompat.getMainExecutor(this)) { recordEvent ->
                // 記録開始
                //lambda event listener
                when(recordEvent) {
                    is VideoRecordEvent.Start -> {
                        viewBinding.videoCaptureButton.apply {
                            // レコード開始ボタンを停止ボタンに変更
                            text = getString(R.string.stop_capture)
                            isEnabled = true
                        }
                    }
                    //　記録完了
                    is VideoRecordEvent.Finalize -> {
                        if (!recordEvent.hasError()) {
                            val msg = "Video capture succeeded: " + "${recordEvent.outputResults.outputUri}"
                            Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                            Log.d(TAG, msg)
                        } else {
                            recording?.close()
                            recording = null
                            Log.e(TAG, "Video capture ends with error: " + "${recordEvent.error}")
                        }
                        viewBinding.videoCaptureButton.apply {
                            text = getString(R.string.start_capture)
                            isEnabled = true
                        }
                    }
                }
            }
    }

    private fun startCamera() {
        // カメラのライフサイクルをMainActivityに設定
        // CameraXはライフサイクルに対応しているため、これによりカメラを開閉するタスクが不要になる
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        // Runnableの引数としてリスナーをcameraProviderFutureに追加
        cameraProviderFuture.addListener({
            // Used to bind the lifecycle of cameras to the lifecycle owner
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Preview　オブジェクトを初期化し、ビルド
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(viewBinding.viewFinder.surfaceProvider)
                }

            // CameraXがサポートされている解像度を取得
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HIGHEST,
                    FallbackStrategy.higherQualityOrLowerThan(
                    Quality.SD)))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)
            imageCapture = ImageCapture.Builder().build()

            // imageCaptureする
//            val imageAnalyzer = ImageAnalysis.Builder().build().also {
//                it.setAnalyzer(cameraExecuter, LuminosityAnalyzer{ luma ->
//                    // 平均光度をログ出力
//                    Log.d(TAG, "Average luminosity: $luma")
//                })
//            }

            // Select back camera as a default
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to camera
                // ここで紐付けしたものだけアプリで使用できる
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture, videoCapture
                )
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun allPermissionGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecuter.shutdown()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, "Permissions not granted by the user.",
                Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    // 画像解析用クラス
    private class LuminosityAnalyzer(private val listener: LumaListener) : ImageAnalysis.Analyzer {

        private fun ByteBuffer.toByteArray(): ByteArray {
            rewind() // Rewind the buffer to zero
            val data = ByteArray(remaining())
            get(data) // Copy the buffer into a byte array
            return data // Return the byte array
        }

        override fun analyze(image: ImageProxy) {
            val buffer = image.planes[0].buffer
            val data = buffer.toByteArray()
            val pixels = data.map { it.toInt() and 0xFF }
            val luma = pixels.average()

            listener(luma)

            image.close()
        }
    }

    companion object {
        private const val TAG = "CameraXApp"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS =
            mutableListOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            ).apply {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }.toTypedArray()
    }
}
