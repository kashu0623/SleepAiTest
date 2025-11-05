package com.example.sleepaitest

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AppCompatActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.pytorch.IValue
import org.pytorch.Module
import org.pytorch.Tensor
import java.io.File
import java.io.FileOutputStream
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val MODEL_NAME = "model_android.ptl"
    }

    // HealthConnectClient 인스턴스
    private lateinit var healthConnectClient: HealthConnectClient

    // PyTorch 모델
    private var sleepModel: Module? = null

    // 필요한 권한 Set (문자열로 정의)
    private val permissions = setOf(
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getWritePermission(SleepSessionRecord::class)
    )

    // 권한 요청을 처리할 ActivityResultLauncher
    private lateinit var permissionLauncher: ActivityResultLauncher<Set<String>>

    // UI 요소
    private lateinit var btnOpenHealthConnect: Button
    private lateinit var btnCreateTestData: Button
    private lateinit var btnFetchData: Button
    private lateinit var tvResult: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // UI 요소 찾기
        btnOpenHealthConnect = findViewById(R.id.btn_open_health_connect)
        btnCreateTestData = findViewById(R.id.btn_create_test_data)
        btnFetchData = findViewById(R.id.btn_fetch_data)
        tvResult = findViewById(R.id.tv_result)

        // Health Connect 설치 확인
        val availability = HealthConnectClient.getSdkStatus(this)
        Log.d(TAG, "Health Connect 상태: $availability")
        
        when (availability) {
            HealthConnectClient.SDK_UNAVAILABLE -> {
                Log.d(TAG, "Health Connect SDK를 사용할 수 없음")
                tvResult.text = "이 기기에서는 Health Connect를 사용할 수 없습니다."
                btnFetchData.isEnabled = false
                return
            }
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                Log.d(TAG, "Health Connect 업데이트 필요")
                tvResult.text = "Health Connect 업데이트가 필요합니다."
                btnFetchData.text = "업데이트하기"
                btnFetchData.setOnClickListener {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        data = Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata")
                    }
                    startActivity(intent)
                }
                return
            }
            HealthConnectClient.SDK_AVAILABLE -> {
                Log.d(TAG, "Health Connect 사용 가능")
            }
            else -> {
                Log.d(TAG, "알 수 없는 상태: $availability")
                tvResult.text = "Health Connect 상태를 확인할 수 없습니다."
                btnFetchData.isEnabled = false
                return
            }
        }

        // HealthConnectClient 초기화
        healthConnectClient = HealthConnectClient.getOrCreate(this)

        // PyTorch 모델 로드 (백그라운드 스레드에서)
        lifecycleScope.launch(Dispatchers.IO) {
            loadModel()
        }

        // 권한 요청 launcher 생성 (onCreate에서만 호출)
        val requestPermissionActivityContract = PermissionController.createRequestPermissionResultContract()
        permissionLauncher = registerForActivityResult(requestPermissionActivityContract) { granted ->
            Log.d(TAG, "권한 결과 받음: $granted")
            lifecycleScope.launch {
                if (granted.containsAll(permissions)) {
                    Log.d(TAG, "모든 권한 승인됨")
                    fetchSleepData()
                } else {
                    Log.d(TAG, "권한 거부됨 또는 다이얼로그 미표시")
                    withContext(Dispatchers.Main) {
                        // 권한 다이얼로그가 표시되지 않았거나 거부된 경우
                        if (granted.isEmpty()) {
                            tvResult.text = "권한 다이얼로그가 표시되지 않았습니다.\n\n" +
                                    "'Health Connect 설정 열기' 버튼을 눌러 수동으로 권한을 부여해주세요.\n\n" +
                                    "설정에서: 앱 및 기기 > ${packageManager.getApplicationLabel(applicationInfo)} > 권한 허용"
                        } else {
                            tvResult.text = "권한이 거부되었습니다.\n\n" +
                                    "'Health Connect 설정 열기' 버튼을 눌러 권한을 부여해주세요."
                        }
                    }
                }
            }
        }

        // Health Connect 열기 버튼 설정
        btnOpenHealthConnect.setOnClickListener {
            openHealthConnect()
        }

        // 테스트 데이터 생성 버튼 설정
        btnCreateTestData.setOnClickListener {
            createTestSleepData()
        }

        // 버튼 클릭 리스너 설정
        btnFetchData.setOnClickListener {
            checkPermissionsAndFetchData()
        }
    }

    // Health Connect 설정 화면 열기
    private fun openHealthConnect() {
        try {
            Log.d(TAG, "Health Connect 설정 화면 열기 시도")
            
            // 방법 1: Health Connect 메인 설정 화면
            var intent = Intent("android.health.connect.action.HEALTH_HOME_SETTINGS")
            if (intent.resolveActivity(packageManager) != null) {
                Log.d(TAG, "HEALTH_HOME_SETTINGS로 열기")
                startActivity(intent)
                tvResult.text = "Health Connect 설정을 열었습니다.\n\n" +
                        "설정에서:\n" +
                        "1. '앱 권한' 또는 '데이터 및 액세스' 메뉴 찾기\n" +
                        "2. 'SleepAiTest' 앱 찾기\n" +
                        "3. '수면' 권한을 '읽기' 허용\n" +
                        "4. 앱으로 돌아오기"
                return
            }
            
            // 방법 2: Health Connect 권한 설정 화면
            intent = Intent("androidx.health.ACTION_MANAGE_HEALTH_PERMISSIONS")
            intent.putExtra("android.intent.extra.PACKAGE_NAME", packageName)
            if (intent.resolveActivity(packageManager) != null) {
                Log.d(TAG, "MANAGE_HEALTH_PERMISSIONS로 열기")
                startActivity(intent)
                tvResult.text = "Health Connect 권한 설정을 열었습니다.\n\n수면 데이터 읽기 권한을 허용한 후 돌아와주세요."
                return
            }
            
            // 방법 3: androidx.health 설정
            intent = Intent("androidx.health.ACTION_HEALTH_CONNECT_SETTINGS")
            if (intent.resolveActivity(packageManager) != null) {
                Log.d(TAG, "HEALTH_CONNECT_SETTINGS로 열기")
                startActivity(intent)
                tvResult.text = "Health Connect 설정을 열었습니다.\n\n권한 설정에서 이 앱의 수면 데이터 읽기 권한을 허용해주세요."
                return
            }
            
            // 방법 4: 시스템 설정에서 Health Connect 찾기
            Log.d(TAG, "Health Connect 설정을 찾을 수 없음, 시스템 설정 열기")
            intent = Intent(android.provider.Settings.ACTION_SETTINGS)
            startActivity(intent)
            tvResult.text = "시스템 설정을 열었습니다.\n\n" +
                    "다음 경로로 이동하세요:\n" +
                    "설정 > 앱 > Health Connect (또는 건강) > 앱 권한 > SleepAiTest > 수면 읽기 허용"
            
        } catch (e: Exception) {
            Log.e(TAG, "설정 화면 열기 실패", e)
            tvResult.text = "설정 화면을 열 수 없습니다: ${e.message}\n\n" +
                    "수동으로 설정 > 앱 > Health Connect로 이동해서 권한을 부여해주세요."
        }
    }

    // 테스트 수면 데이터 생성
    private fun createTestSleepData() {
        lifecycleScope.launch {
            try {
                Log.d(TAG, "테스트 수면 데이터 생성 시작")
                
                withContext(Dispatchers.Main) {
                    tvResult.text = "테스트 수면 데이터를 생성하는 중..."
                }
                
                // 현재 권한 확인
                val granted = healthConnectClient.permissionController.getGrantedPermissions()
                val hasWritePermission = permissions.all { granted.contains(it) }
                
                if (!hasWritePermission) {
                    withContext(Dispatchers.Main) {
                        tvResult.text = "쓰기 권한이 필요합니다.\n먼저 '수면 데이터 가져오기'를 눌러 권한을 부여하세요."
                    }
                    return@launch
                }
                
                // 지난 3일간 테스트 수면 세션 생성
                val records = mutableListOf<SleepSessionRecord>()
                for (daysAgo in 1..3) {
                    val endTime = LocalDateTime.now().minusDays(daysAgo.toLong()).withHour(7).withMinute(30).withSecond(0)
                    val startTime = endTime.minusHours(8)
                    
                    val sleepSession = SleepSessionRecord(
                        startTime = startTime.atZone(ZoneId.systemDefault()).toInstant(),
                        endTime = endTime.atZone(ZoneId.systemDefault()).toInstant(),
                        startZoneOffset = ZoneId.systemDefault().rules.getOffset(startTime),
                        endZoneOffset = ZoneId.systemDefault().rules.getOffset(endTime)
                    )
                    records.add(sleepSession)
                    
                    Log.d(TAG, "생성: ${daysAgo}일 전 수면 세션 - $startTime ~ $endTime")
                }
                
                // Health Connect에 저장
                healthConnectClient.insertRecords(records)
                Log.d(TAG, "테스트 데이터 저장 완료: ${records.size}개")
                
                withContext(Dispatchers.Main) {
                    tvResult.text = "✅ 테스트 수면 데이터 생성 완료!\n\n" +
                            "${records.size}개의 수면 세션을 생성했습니다.\n\n" +
                            "이제 '수면 데이터 가져오기' 버튼을 눌러\nAI 모델 분석을 확인하세요!"
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "테스트 데이터 생성 실패", e)
                withContext(Dispatchers.Main) {
                    tvResult.text = "테스트 데이터 생성 실패: ${e.message}"
                }
            }
        }
    }

    // 권한 확인 및 데이터 가져오기
    private fun checkPermissionsAndFetchData() {
        lifecycleScope.launch {
            try {
                Log.d(TAG, "권한 확인 시작")
                // 현재 권한 확인
                val granted = healthConnectClient.permissionController.getGrantedPermissions()
                Log.d(TAG, "현재 부여된 권한: $granted")
                Log.d(TAG, "필요한 권한: $permissions")
                
                // 필요한 권한이 모두 부여되었는지 확인
                val hasAllPermissions = permissions.all { permission ->
                    granted.contains(permission)
                }

                Log.d(TAG, "모든 권한 보유: $hasAllPermissions")

                if (!hasAllPermissions) {
                    // permissionLauncher는 메인 스레드에서 호출되어야 함
                    withContext(Dispatchers.Main) {
                        Log.d(TAG, "권한 요청 시작")
                        tvResult.text = "권한 요청 중...\n\n만약 권한 화면이 나타나지 않으면, Health Connect 앱을 직접 열어서 초기 설정을 완료해주세요."
                        
                        try {
                            permissionLauncher.launch(permissions)
                        } catch (e: Exception) {
                            Log.e(TAG, "권한 요청 실패", e)
                            tvResult.text = "권한 요청 실패: ${e.message}\n\nHealth Connect 앱을 직접 열어보세요."
                        }
                    }
                } else {
                    // 권한이 이미 부여됨
                    Log.d(TAG, "권한이 이미 있음, 데이터 가져오기")
                    fetchSleepData()
                }
            } catch (e: Exception) {
                Log.e(TAG, "권한 확인 중 오류", e)
                withContext(Dispatchers.Main) {
                    tvResult.text = "권한 확인 중 오류: ${e.message}"
                }
            }
        }
    }

    // PyTorch 모델 로드 (백그라운드 스레드에서 실행)
    private suspend fun loadModel() {
        try {
            Log.d(TAG, "모델 로드 시작")
            
            // assets 폴더에서 모델 파일을 임시 파일로 복사
            val modelFile = assetFilePath(MODEL_NAME)
            
            // 모델 로드 (IO 스레드에서 실행)
            sleepModel = Module.load(modelFile)
            Log.d(TAG, "모델 로드 성공: $MODEL_NAME")
            
            // UI 업데이트는 메인 스레드에서
            withContext(Dispatchers.Main) {
                tvResult.text = "✅ AI 모델 로드 완료\n\n수면 데이터를 가져올 준비가 되었습니다."
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "모델 로드 실패", e)
            sleepModel = null
            
            // 사용자에게 알림 (메인 스레드에서)
            withContext(Dispatchers.Main) {
                tvResult.text = "⚠️ 모델 로드 실패: ${e.message}\n\n" +
                        "assets 폴더에 $MODEL_NAME 파일이 있는지 확인해주세요.\n\n" +
                        "수면 데이터는 가져올 수 있지만 AI 분석은 불가능합니다."
            }
        }
    }

    // assets 파일을 앱 내부 저장소로 복사
    private fun assetFilePath(assetName: String): String {
        val file = File(filesDir, assetName)
        
        if (file.exists()) {
            Log.d(TAG, "모델 파일이 이미 존재함: ${file.absolutePath}")
            return file.absolutePath
        }
        
        try {
            assets.open(assetName).use { inputStream ->
                FileOutputStream(file).use { outputStream ->
                    val buffer = ByteArray(4 * 1024)
                    var read: Int
                    while (inputStream.read(buffer).also { read = it } != -1) {
                        outputStream.write(buffer, 0, read)
                    }
                    outputStream.flush()
                }
            }
            Log.d(TAG, "모델 파일 복사 완료: ${file.absolutePath}")
            return file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "모델 파일 복사 실패", e)
            throw e
        }
    }

    // 단일 수면 세션을 모델 입력 Tensor로 변환 (Dual Input)
    private fun preprocessSingleSession(session: SleepSessionRecord): Pair<Tensor, Tensor> {
        // === x_raw: 원시 데이터 (시간별) ===
        val rawData = mutableListOf<Float>()
        
        // 수면 시작 시간 (시간 단위, 0-23)
        val startHour = session.startTime.atZone(ZoneId.systemDefault()).hour.toFloat()
        rawData.add(startHour)
        
        // 수면 종료 시간 (시간 단위, 0-23)
        val endHour = session.endTime.atZone(ZoneId.systemDefault()).hour.toFloat()
        rawData.add(endHour)
        
        // === x_features: 추출된 특징 ===
        val features = mutableListOf<Float>()
        
        // 수면 지속 시간 (시간 단위)
        val duration = Duration.between(session.startTime, session.endTime).toMinutes() / 60.0f
        features.add(duration)
        
        // 시작 시간의 분 단위
        val startMinute = session.startTime.atZone(ZoneId.systemDefault()).minute.toFloat()
        features.add(startMinute)
        
        // 요일 (0=월요일, 6=일요일)
        val dayOfWeek = session.startTime.atZone(ZoneId.systemDefault()).dayOfWeek.value.toFloat()
        features.add(dayOfWeek)
        
        Log.d(TAG, "세션 - 시작: $startHour:${startMinute.toInt()}, 종료: $endHour, 지속: ${duration}h, 요일: ${dayOfWeek.toInt()}")
        
        // Tensor로 변환
        val rawArray = rawData.toFloatArray()
        val featuresArray = features.toFloatArray()
        
        // Shape: [1, 2] for x_raw, [1, 3] for x_features
        val xRaw = Tensor.fromBlob(rawArray, longArrayOf(1, rawData.size.toLong()))
        val xFeatures = Tensor.fromBlob(featuresArray, longArrayOf(1, features.size.toLong()))
        
        Log.d(TAG, "입력 Tensor 생성 - x_raw: [1, ${rawData.size}], x_features: [1, ${features.size}]")
        
        return Pair(xRaw, xFeatures)
    }

    // Softmax 함수 (로짓 → 확률)
    private fun softmax(logits: FloatArray): FloatArray {
        val maxLogit = logits.maxOrNull() ?: 0f
        val exps = logits.map { kotlin.math.exp((it - maxLogit).toDouble()).toFloat() }.toFloatArray()
        val sumExps = exps.sum()
        return exps.map { it / sumExps }.toFloatArray()
    }

    // 모델 추론 실행 (Dual Input)
    private fun runInference(inputTensors: Pair<Tensor, Tensor>): String {
        if (sleepModel == null) {
            return "모델이 로드되지 않았습니다."
        }
        
        try {
            Log.d(TAG, "모델 추론 시작")
            
            val (xRaw, xFeatures) = inputTensors
            
            // 모델 추론 (2개의 입력)
            val outputTensor = sleepModel!!.forward(
                IValue.from(xRaw),
                IValue.from(xFeatures)
            ).toTensor()
            
            // 출력 데이터 추출 (로짓)
            val logits = outputTensor.dataAsFloatArray
            
            Log.d(TAG, "추론 결과 (로짓): ${logits.contentToString()}")
            
            // Softmax 적용하여 확률로 변환
            val probabilities = softmax(logits)
            
            Log.d(TAG, "확률 변환 후: ${probabilities.contentToString()}")
            
            // 수면 단계 분류
            val sleepStages = arrayOf("깊은 수면", "얕은 수면", "REM 수면", "각성 상태")
            
            val resultText = StringBuilder()
            
            if (probabilities.size == sleepStages.size) {
                // 가장 높은 확률의 단계 찾기
                val maxIndex = probabilities.indices.maxByOrNull { probabilities[it] } ?: 0
                val maxProbability = probabilities[maxIndex]
                
                resultText.append("🌙 예측 결과: ${sleepStages[maxIndex]}\n")
                resultText.append("📊 신뢰도: ${String.format("%.1f", maxProbability * 100)}%\n\n")
                resultText.append("각 단계별 확률:\n")
                
                for (i in probabilities.indices) {
                    val emoji = when(i) {
                        0 -> "😴" // 깊은 수면
                        1 -> "😌" // 얕은 수면
                        2 -> "💭" // REM
                        3 -> "👀" // 각성
                        else -> "•"
                    }
                    resultText.append("$emoji ${sleepStages[i]}: ${String.format("%.1f", probabilities[i] * 100)}%\n")
                }
            } else {
                // 다른 형식의 출력
                resultText.append("⚠️ 예상치 못한 출력 형식\n")
                resultText.append("출력 크기: ${probabilities.size}개\n\n")
                probabilities.forEachIndexed { index, prob ->
                    resultText.append("Output[$index]: ${String.format("%.4f", prob)}\n")
                }
            }
            
            return resultText.toString()
            
        } catch (e: Exception) {
            Log.e(TAG, "모델 추론 실패", e)
            return "추론 실패: ${e.message}"
        }
    }

    // 수면 데이터 가져오기
    private suspend fun fetchSleepData() {
        lifecycleScope.launch {
            try {
                Log.d(TAG, "수면 데이터 가져오기 시작")
                
                withContext(Dispatchers.Main) {
                    tvResult.text = "수면 데이터를 가져오는 중..."
                }
                
                // 지난 7일간의 시간 범위 정의
                val endTime = LocalDateTime.now()
                val startTime = endTime.minusDays(7)
                
                val timeRangeFilter = TimeRangeFilter.between(
                    startTime.atZone(ZoneId.systemDefault()).toInstant(),
                    endTime.atZone(ZoneId.systemDefault()).toInstant()
                )

                Log.d(TAG, "시간 범위: $startTime ~ $endTime")

                // ReadRecordsRequest 생성
                val request = ReadRecordsRequest(
                    recordType = SleepSessionRecord::class,
                    timeRangeFilter = timeRangeFilter
                )

                // 수면 세션 데이터 가져오기
                val response = healthConnectClient.readRecords(request)
                Log.d(TAG, "수면 세션 개수: ${response.records.size}")

                // 결과 표시
                withContext(Dispatchers.Main) {
                    if (response.records.isNotEmpty()) {
                        val sessionCount = response.records.size
                        
                        val resultText = StringBuilder()
                        resultText.append("📅 지난 7일간 ${sessionCount}개의 수면 세션 발견\n\n")
                        
                        // 모든 세션 정보 표시
                        response.records.forEachIndexed { index, session ->
                            val startTimeStr = session.startTime.atZone(ZoneId.systemDefault()).toLocalDateTime()
                            val endTimeStr = session.endTime.atZone(ZoneId.systemDefault()).toLocalDateTime()
                            val duration = Duration.between(session.startTime, session.endTime).toHours()
                            
                            resultText.append("━━━ ${index + 1}번째 세션 ━━━\n")
                            resultText.append("⏰ 시작: ${startTimeStr.toLocalDate()} ${String.format("%02d:%02d", startTimeStr.hour, startTimeStr.minute)}\n")
                            resultText.append("⏰ 종료: ${endTimeStr.toLocalDate()} ${String.format("%02d:%02d", endTimeStr.hour, endTimeStr.minute)}\n")
                            resultText.append("⏱️  지속: ${duration}시간\n")
                            if (index < response.records.size - 1) resultText.append("\n")
                        }
                        
                        val finalResultText = resultText.toString()
                        
                        // 모델이 로드되어 있으면 각 세션별로 추론 실행
                        if (sleepModel != null) {
                            tvResult.text = "$finalResultText\n\n🔄 각 세션의 수면 단계를 분석하는 중..."
                            
                            // 백그라운드 스레드에서 추론 실행
                            withContext(Dispatchers.Default) {
                                try {
                                    val allPredictions = StringBuilder()
                                    allPredictions.append("\n════════════════════════\n")
                                    allPredictions.append("🤖 AI 수면 단계 분석 결과\n")
                                    allPredictions.append("════════════════════════\n\n")
                                    
                                    // 각 세션별로 추론
                                    response.records.forEachIndexed { index, session ->
                                        // 개별 세션 전처리
                                        val inputTensors = preprocessSingleSession(session)
                                        
                                        // 모델 추론
                                        val inferenceResult = runInference(inputTensors)
                                        
                                        // 세션 정보
                                        val startTimeStr = session.startTime.atZone(ZoneId.systemDefault()).toLocalDateTime()
                                        
                                        allPredictions.append("━━━ ${index + 1}번째 세션 분석 ━━━\n")
                                        allPredictions.append("📅 날짜: ${startTimeStr.toLocalDate()}\n")
                                        allPredictions.append("$inferenceResult\n")
                                        
                                        if (index < response.records.size - 1) {
                                            allPredictions.append("\n")
                                        }
                                    }
                                    
                                    // 결과 표시
                                    withContext(Dispatchers.Main) {
                                        tvResult.text = "$finalResultText${allPredictions.toString()}"
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "추론 중 오류", e)
                                    withContext(Dispatchers.Main) {
                                        tvResult.text = """
                                            $finalResultText
                                            
                                            ════════════════════════
                                            ❌ 추론 오류: ${e.message}
                                        """.trimIndent()
                                    }
                                }
                            }
                        } else {
                            tvResult.text = "$finalResultText\n\n⚠️ 모델이 로드되지 않아 수면 단계 분석을 수행할 수 없습니다."
                        }
                    } else {
                        tvResult.text = "지난 7일간 수면 세션이 없습니다."
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "데이터 가져오기 오류", e)
                withContext(Dispatchers.Main) {
                    tvResult.text = "데이터 가져오기 오류: ${e.message}"
                }
            }
        }
    }
}
