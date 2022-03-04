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
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import android.provider.MediaStore
import android.content.ContentValues
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import android.util.SparseIntArray
import android.view.Surface
import androidx.annotation.RequiresApi
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.lang.Exception

typealias LumaListener = (luma: Double) -> Unit

class MainActivity : AppCompatActivity() {
    private lateinit var viewBinding: ActivityMainBinding

    // ImageCapture　写真を撮るための基本的なクラス
    private var imageCapture: ImageCapture? = null

    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null

    private lateinit var cameraExecuter: ExecutorService

    // （バーコード、QRコード解析番外）
//    private val ORIENTATIONS = SparseIntArray()
//
//    init {
//        ORIENTATIONS.append(Surface.ROTATION_0, 0)
//        ORIENTATIONS.append(Surface.ROTATION_90, 90)
//        ORIENTATIONS.append(Surface.ROTATION_180, 180)
//        ORIENTATIONS.append(Surface.ROTATION_270, 270)
//    }

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

            // バーコード
            val barcodeAnalyzer = ImageAnalysis.Builder().build().also {
                it.setAnalyzer(cameraExecuter, BarcodeImageAnalyzer{

                })
            }

            // Select back camera as a default
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to camera
                // ここで紐付けしたものだけアプリで使用できる
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture, videoCapture, barcodeAnalyzer
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

        // （バーコード、QRコード解析番外）CameraXライブラリを使用しない場合、デバイスの回転角度とデバイス内のカメラセンサーの
        // 向きから画像を計算する。Android5未満の場合など。
        /**
         * Get the angle by which an image must be rotated given the device's current
         * orientation.
         */
//        @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
//        @Throws(CameraAccessException::class)
//        fun getRotationCompensation(cameraId: String, activity: Activity, isFrontFacing: Boolean): Int {
//            // Get the device's current rotation relative to its "native" orientation.
//            // Then, from the ORIENTATIONS table, look up the angle the image must be
//            // rotated to compensate for the device's rotation.
//            val deviceRotation = activity.windowManager.defaultDisplay.rotation
//            var rotationCompensation = ORIENTATIONS.get(deviceRotation)
//
//            // Get the device's sensor orientation.
//            val cameraManager = activity.getSystemService(CAMERA_SERVICE) as CameraManager
//            val sensorOrientation = cameraManager
//                .getCameraCharacteristics(cameraId)
//                .get(CameraCharacteristics.SENSOR_ORIENTATION)!!
//
//            if (isFrontFacing) {
//                rotationCompensation = (sensorOrientation + rotationCompensation) % 360
//            } else { // back-facing
//                rotationCompensation = (sensorOrientation - rotationCompensation + 360) % 360
//            }
//            return rotationCompensation
//        }
    }

    // 画像解析（光度）用クラス
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

    // 画像解析（バーコード、QRコード
    // デバイスのカメラから画像をキャプチャする場合など、InputImageオブジェクトからmedia.Imageオブジェクトを
    // 作成するには、オブジェクトと画像の回転をInputImage.fromMediaImage()に渡す
    // CameraXライブラリを使用する場合、OnImageCapturedListener/ImageAnalysis.Analyzerクラスは回転値を計算する
    private class BarcodeImageAnalyzer(private val listener: LumaListener) : ImageAnalysis.Analyzer {
        @SuppressLint("UnsafeOptInUsageError")
        override fun analyze(imageProxy: ImageProxy) {
            val mediaImage = imageProxy.image
            if (mediaImage != null) {
                val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                // Pass image to an ML Kit Vision API
                scanBarcode(image)

                imageProxy.close()
            }
        }

        private fun scanBarcode(image: InputImage) {
            // Aztecコード（バーコード形式の一種）を検出する
            val options = BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                    Barcode.FORMAT_AZTEC
                )
                .build()

            val scanner = BarcodeScanning.getClient()

            val result = scanner.process(image)
                .addOnSuccessListener { barcodes ->
                    // バーコード認識操作成功
                    // barcodes: 画像で検出されたバーコード。バーコードごとに
                    // 　　　　　　入力画像の境界座標とバーコードによってエンコード
                    //           された生データを取得可能
                    for (barcode in barcodes) {
                        val bounds = barcode.boundingBox
                        val corner = barcode.cornerPoints

                        val rawValues = barcode.rawValue

                        val valueType = barcode.valueType
                        // See API reference for complete list of supported types
                        when (valueType) {
                            Barcode.TYPE_WIFI -> {
                                val ssid = barcode.wifi!!.ssid
                                val password = barcode.wifi!!.password
                                val type = barcode.wifi!!.encryptionType

                                Log.d(TAG, "ssid: ${ssid}")
                            }
                            Barcode.TYPE_URL -> {
                                val title = barcode.url!!.title
                                val url = barcode.url!!.url

                                Log.d(TAG, "ssid: ${title}")
                            }
                        }
                    }
                }
                .addOnFailureListener {

                }
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
