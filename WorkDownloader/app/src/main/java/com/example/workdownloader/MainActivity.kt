package com.example.workdownloader

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.LiveData
import androidx.work.*
import com.example.workdownloader.ui.theme.WorkDownloaderTheme
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.URL
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.livedata.observeAsState
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Constants for WorkManager and notifications
private const val DOWNLOAD_WORK_TAG = "download_work"
private const val DOWNLOAD_WORK_URL = "download_url"
private const val DOWNLOAD_NOTIFICATION_CHANNEL_ID = "download_channel"
private const val DOWNLOAD_NOTIFICATION_ID = 1
private val MAX_FILENAME_LENGTH = 50

class MainActivity : ComponentActivity() {

    // 注册权限请求
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                // Permission granted. Continue with the app flow.
            } else {
                Toast.makeText(this, "需要通知权限才能显示下载进度", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 请求存储权限 (适用于 Android 10 以下版本)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R){
            val permission = arrayOf(
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.READ_EXTERNAL_STORAGE
            )
            val requestPermissionLauncher =
                this.registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->

                }
            permission.forEach {
                if(! (this.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED)){
                    requestPermissionLauncher.launch(it)
                }
            }
        }
        else{
            if(!Environment.isExternalStorageManager()){
                val builder = android.app.AlertDialog.Builder(this)
                    .setMessage("需要获取文件读写权限")
                    .setPositiveButton("ok") { _, _ ->
                        val packageName = this.packageName
                        val intent = Intent()
                        intent.action = Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
                        intent.data = Uri.fromParts("package", packageName, null)
                        ContextCompat.startActivity( this, intent, null)
                    }
                    .setNeutralButton("稍后再问"){ _, _ ->

                    }
                builder.show()
            }
        }


        // 请求通知权限 (适用于 Android 13 及以上版本)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // 创建通知渠道
        createNotificationChannel()

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    DownloadAppScreen()
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "下载通知"
            val descriptionText = "显示下载文件的进度和状态"
            val importance = NotificationManagerCompat.IMPORTANCE_LOW
            val channel = android.app.NotificationChannel(DOWNLOAD_NOTIFICATION_CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: android.app.NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    WorkDownloaderTheme {
        Greeting("Android")
    }
}


@Composable
fun DownloadAppScreen() {
    val context = LocalContext.current
    val workManager = WorkManager.getInstance(context)
    var downloadUrl by remember { mutableStateOf("") }
    val workInfos: LiveData<List<WorkInfo>> = remember {
        workManager.getWorkInfosByTagLiveData(DOWNLOAD_WORK_TAG)
    }
    val downloadTasks by workInfos.observeAsState(initial = emptyList())

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "WorkManager 下载器",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        // URL input and download button
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = downloadUrl,
                onValueChange = { downloadUrl = it },
                label = { Text("输入下载链接") },
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    if (downloadUrl.isNotBlank()) {
                        startDownload(context, downloadUrl)
                        downloadUrl = "" // Clear the input field
                    } else {
                        Toast.makeText(context, "下载链接不能为空", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.height(56.dp)
            ) {
                Text("开始下载")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            "下载任务列表",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.align(Alignment.Start)
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Download task list
        LazyColumn(
            modifier = Modifier.fillMaxSize()
        ) {
            items(downloadTasks) { workInfo ->
                DownloadTaskItem(workInfo)
            }
        }
    }
}

@Composable
fun DownloadTaskItem(workInfo: WorkInfo) {
    val progress = workInfo.progress.getInt("progress", 0)
    val status = workInfo.state.name
    val fileName = workInfo.outputData.getString("fileName") ?: "未知文件"
    val context= LocalContext.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "文件: $fileName",
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "状态: $status",
                style = MaterialTheme.typography.bodySmall,
                color = when (workInfo.state) {
                    WorkInfo.State.SUCCEEDED -> Color.Green
                    WorkInfo.State.FAILED -> Color.Red
                    WorkInfo.State.RUNNING -> Color.Blue
                    else -> Color.Gray
                }
            )
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = if (progress > 0) progress / 100f else 0f,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "进度: $progress%",
                style = MaterialTheme.typography.bodySmall
            )
            // 为每个任务添加取消按钮
            if (workInfo.state.isFinished.not()) {
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        WorkManager.getInstance(context).cancelWorkById(workInfo.id)
                        Toast.makeText(context, "已取消任务: $fileName", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("取消")
                }
            }
        }
    }
}

fun startDownload(context: Context, url: String) {
    // 声明并初始化变量，确保它们在任何情况下都可以被访问
    val fileName = try {
        val url2 = URL(url)
        val rawFileName = url2.path.substringAfterLast('/')

        // 提取文件扩展名
        val fileExtension = rawFileName.substringAfterLast('.', "")
        val baseName = if (fileExtension.isNotEmpty()) {
            rawFileName.substringBeforeLast('.')
        } else {
            rawFileName
        }

        // 清理文件名并限制长度
        val sanitizedBaseName = baseName.replace(Regex("[^a-zA-Z0-9.\\-_]"), "_")
        val finalBaseName = if (sanitizedBaseName.length > MAX_FILENAME_LENGTH) {
            sanitizedBaseName.substring(0, MAX_FILENAME_LENGTH)
        } else {
            sanitizedBaseName
        }

        // 组合最终文件名
        if (finalBaseName.isNotEmpty()) {
            if (fileExtension.isNotEmpty()) {
                "$finalBaseName.$fileExtension"
            } else {
                finalBaseName
            }
        } else {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            "download_$timestamp.bin"
        }
    } catch (e: Exception) {
        e.printStackTrace()
        // 如果解析文件名失败，则返回一个安全的备用文件名
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        "download_$timestamp.bin"
    }
    val data = workDataOf(DOWNLOAD_WORK_URL to url,"fileName" to fileName)
    // Set constraints to only download when connected to a network
    val constraints = Constraints.Builder()
        //.setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    // Create a OneTimeWorkRequest for the download
    val downloadRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
        .setConstraints(constraints)
        .setInputData(data)
        .addTag(DOWNLOAD_WORK_TAG)
        .build()
    // Enqueue the work request
    WorkManager.getInstance(context).enqueue(downloadRequest)
}

class DownloadWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val notificationManager = NotificationManagerCompat.from(appContext)
    private val notificationBuilder = NotificationCompat.Builder(applicationContext, DOWNLOAD_NOTIFICATION_CHANNEL_ID)
        .setContentTitle("下载中")
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setOngoing(true)

    // 最大文件名长度
    private val MAX_FILENAME_LENGTH = 50

    // 重写 getForegroundInfo() 以提供前台服务通知信息
    override suspend fun getForegroundInfo(): ForegroundInfo {
        return ForegroundInfo(
            DOWNLOAD_NOTIFICATION_ID,
            notificationBuilder.setContentText("开始下载...").build()
        )
    }

    override suspend fun doWork(): Result {
        val urlString = inputData.getString(DOWNLOAD_WORK_URL)
        if (urlString.isNullOrBlank()) {
            return Result.failure()
        }
        val  fileName=inputData.getString("fileName")?: "未知文件"

        // 设置前台服务，并立即显示通知
        try {
            setForeground(getForegroundInfo())
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 更改下载目录为应用程序私有的外部存储，无需特殊权限
        //val downloadDir = applicationContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return withContext(Dispatchers.IO) {
            try {
                val file = File(downloadDir, fileName)

                val url = URL(urlString)
                val connection = url.openConnection()
                connection.connect()
                val totalBytes = connection.contentLength.toLong()

                val inputStream: InputStream = connection.getInputStream()
                val outputStream = FileOutputStream(file)

                val buffer = ByteArray(4096)
                var bytesRead: Int
                var totalBytesRead: Long = 0

                // Start downloading
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    totalBytesRead += bytesRead

                    val progress = ((totalBytesRead * 100) / totalBytes).toInt()
                    setProgress(progress)

                    // Update notification progress
                    notificationBuilder.setProgress(100, progress, false)
                    notificationBuilder.setContentText("正在下载: $fileName ($progress%)")
                    if (ActivityCompat.checkSelfPermission(
                            applicationContext,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationManager.notify(DOWNLOAD_NOTIFICATION_ID, notificationBuilder.build())
                    }
                }

                outputStream.close()
                inputStream.close()

                // Task succeeded, set output data and update notification
                val outputData = workDataOf("fileName" to fileName)
                notificationBuilder.setContentText("下载完成: $fileName").setProgress(0, 0, false)
                if (ActivityCompat.checkSelfPermission(
                        applicationContext,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    notificationManager.notify(DOWNLOAD_NOTIFICATION_ID, notificationBuilder.build())
                }
                return@withContext Result.success(outputData)

            } catch (e: Exception) {
                e.printStackTrace()
                // Task failed, update notification
                notificationBuilder.setContentText("下载失败: $fileName").setProgress(0, 0, false)
                if (ActivityCompat.checkSelfPermission(
                        applicationContext,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    notificationManager.notify(DOWNLOAD_NOTIFICATION_ID, notificationBuilder.build())
                }
                return@withContext Result.failure()
            } finally {
                // Ensure notification is cancelled
                if (ActivityCompat.checkSelfPermission(
                        applicationContext,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    notificationManager.cancel(DOWNLOAD_NOTIFICATION_ID)
                }
            }
        }
    }

    // Helper function to set progress for the UI
    private fun setProgress(progress: Int) {
        val progressData = workDataOf("progress" to progress)
        setProgressAsync(progressData)
    }
}
