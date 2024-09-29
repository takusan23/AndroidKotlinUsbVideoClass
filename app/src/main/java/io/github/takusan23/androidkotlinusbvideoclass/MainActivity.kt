package io.github.takusan23.androidkotlinusbvideoclass

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.core.content.contentValuesOf
import androidx.lifecycle.lifecycleScope
import io.github.takusan23.androidkotlinusbvideoclass.ui.theme.AndroidKotlinUsbVideoClassTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val usbManager by lazy { getSystemService(Context.USB_SERVICE) as UsbManager }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
                synchronized(this) {
                    val device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE) as? UsbDevice
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if (device != null) {
                            lifecycleScope.launch(Dispatchers.Default) {
                                connectUvc(device)
                            }
                        }
                    } else {
                        Toast.makeText(context, "permission denied for device $device", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        setContent {
            AndroidKotlinUsbVideoClassTheme {
                MainScreen(
                    onCapture = { requestUvcPermission() }
                )
            }
        }
    }

    private fun requestUvcPermission() {
        val deviceList = usbManager.getDeviceList()
        val uvcDevice = deviceList.values.first()

        println("uvcDevice.deviceId = ${uvcDevice.productName}")
        println("uvcDevice.deviceName = ${uvcDevice.deviceName}")
        println("uvcDevice.deviceClass = ${uvcDevice.deviceClass}")
        println("uvcDevice.deviceSubclass = ${uvcDevice.deviceSubclass}")

        // Android 14 都合でなんか色々必要
        val permissionIntent = PendingIntent.getBroadcast(
            this,
            0,
            Intent(ACTION_USB_PERMISSION).apply { `package` = applicationContext.packageName },
            PendingIntent.FLAG_MUTABLE
        )
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        // requestPermission()、UVC デバイスの場合はカメラ権限が追加で必要
        // ドキュメント、ちゃんと読もう！
        // https://developer.android.com/reference/android/hardware/usb/UsbManager#requestPermission(android.hardware.usb.UsbDevice,%20android.app.PendingIntent)
        usbManager.requestPermission(uvcDevice, permissionIntent)
    }


    private suspend fun connectUvc(usbDevice: UsbDevice) {
        println("usbDevice.interfaceCount = ${usbDevice.interfaceCount}")

        // 多分 VideoStreaming Interface が 1 番目
        val intf = usbDevice.getInterface(1)
        println("intf.endpointCount = ${intf.endpointCount}")
        val endpoint = intf.getEndpoint(0)

        // デバイスを専有しますよ
        val usbDeviceConnection = usbManager.openDevice(usbDevice)
        usbDeviceConnection.claimInterface(intf, true)
        // USB 接続時に一度だけもらえる？。基本的には UsbDevice で deviceName 等が取得できるが、元になっているバイト配列がこれ。
        println("rawDescriptors")
        println(usbDeviceConnection.rawDescriptors.to0xHexString())

        // thx !!!!
        // https://voidcomputing.hu/blog/android-uvc/
        // https://github.com/badicsalex/ar-drivers-rs/blob/bbfb2aa01696a790c190ed9d848a32d92f726848/src/nreal_light.rs#L604
        val UVC_SET_CUR = 0x01
        val UVC_VS_COMMIT_CONTROL = 0x02
        val ENABLE_STREAMING = byteArrayOf(
            0x1, 0x0, // bmHint
            0x1, // bFormatIndex
            0x1, // bFrameIndex
            0x15, 0x16, 0x5, 0x0, // bFrameInterval (333333)
            0x0, 0x0, // wKeyFrameRate
            0x0, 0x0, // wPFrameRate
            0x0, 0x0, // wCompQuality
            0x0, 0x0, // wCompWindowSize
            0x65, 0x0, // wDelay
            0x0, 0x65, 0x9, 0x0, // dwMaxVideoFrameSize (615680)
            0x0, 0x80.toByte(), 0x0, 0x0, // dwMaxPayloadTransferSize
            0x80.toByte(), 0xd1.toByte(), 0xf0.toByte(), 0x8,  // dwClockFrequency
            0x8,  // bmFramingInfo
            0xf0.toByte(), // bPreferredVersion
            0xa9.toByte(), // bMinVersion
            0x18, // bMaxVersion
        )
        usbDeviceConnection.controlTransfer(
            0x21,
            UVC_SET_CUR,
            UVC_VS_COMMIT_CONTROL shl 8,
            1,
            ENABLE_STREAMING,
            ENABLE_STREAMING.size,
            TIMEOUT
        )

        // ??????????
        val size = 0x8000 * 80
        val frameBuffer = ByteArray(size)
        // 失敗することがあるので、成功するまで呼び出す
        while (true) {
            val bulkResult = usbDeviceConnection.bulkTransfer(endpoint, frameBuffer, frameBuffer.size, TIMEOUT)
            println("bulkResult = $bulkResult")
            if (bulkResult != -1) break
        }

        // YUV to RGB
        val width = 1280
        val height = 720
        val yuvImage = YuvImage(frameBuffer, ImageFormat.YUY2, width, height, null)

        // MediaStore に保存
        val contentResolver = contentResolver
        val contentValues = contentValuesOf(
            MediaStore.Images.Media.DISPLAY_NAME to "android_kotlin_usb_video_class_${System.currentTimeMillis()}.jpg",
            MediaStore.Images.Media.RELATIVE_PATH to "${Environment.DIRECTORY_PICTURES}/AndroidKotlinUsbVideoClass"
        )
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues) ?: return
        withContext(Dispatchers.IO) {
            contentResolver.openOutputStream(uri)?.use { outputStream ->
                yuvImage.compressToJpeg(Rect(0, 0, width, height), 100, outputStream)
            }
        }

        println("yuvImage.compressToJpeg")
        withContext(Dispatchers.Main) {
            Toast.makeText(this@MainActivity, "Save $uri", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "com.android.example.USB_PERMISSION"
        private const val TIMEOUT = 1000
    }

    private fun ByteArray.to0xHexString() = this.joinToString { "0x%02x".format(it) }

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(onCapture: () -> Unit) {

    val context = LocalContext.current
    val isGranted = remember { mutableStateOf(ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val permissionRequest = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted.value = it }
    )

    // 権限がない場合は
    LaunchedEffect(key1 = Unit) {
        if (!isGranted.value) {
            permissionRequest.launch(android.Manifest.permission.CAMERA)
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(text = stringResource(id = R.string.app_name)) }) },
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {

            Button(onClick = onCapture) {
                Text(text = "撮影")
            }
        }
    }
}