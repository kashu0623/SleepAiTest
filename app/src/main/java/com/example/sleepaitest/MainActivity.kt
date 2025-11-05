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

    // 수면 데이터를 모델 입력 Tensor로 변환
    private fun preprocessSleepData(sleepSessions: List<SleepSessionRecord>): Tensor {
        // TODO: 실제 모델의 입력 형식에 맞게 수정 필요
        // 예시: 각 수면 세션의 시작 시간, 종료 시간, 지속 시간 등을 특징으로 사용
        
        val features = mutableListOf<Float>()
        
        for (session in sleepSessions) {
            // 수면 시작 시간 (시간 단위, 0-23)
            val startHour = session.startTime.atZone(ZoneId.systemDefault()).hour.toFloat()
            features.add(startHour)
            
            // 수면 종료 시간 (시간 단위, 0-23)
            val endHour = session.endTime.atZone(ZoneId.systemDefault()).hour.toFloat()
            features.add(endHour)
            
            // 수면 지속 시간 (시간 단위)
            val duration = Duration.between(session.startTime, session.endTime).toMinutes() / 60.0f
            features.add(duration)
            
            Log.d(TAG, "세션 특징 - 시작: $startHour, 종료: $endHour, 지속: ${duration}시간")
        }
        
        // FloatArray로 변환
        val inputArray = features.toFloatArray()
        
        // Tensor로 변환 (배치 크기 1, 특징 개수는 features.size)
        // 모델의 입력 형태에 맞게 shape 조정 필요
        val inputTensor = Tensor.fromBlob(inputArray, longArrayOf(1, features.size.toLong()))
        
        Log.d(TAG, "입력 Tensor 생성 완료 - Shape: [1, ${features.size}]")
        return inputTensor
    }

    // 모델 추론 실행
    private fun runInference(inputTensor: Tensor): String {
        if (sleepModel == null) {
            return "모델이 로드되지 않았습니다."
        }
        
        try {
            Log.d(TAG, "모델 추론 시작")
            
            // 모델 추론
            val outputTensor = sleepModel!!.forward(IValue.from(inputTensor)).toTensor()
            
            // 출력 데이터 추출
            val scores = outputTensor.dataAsFloatArray
            
            Log.d(TAG, "추론 결과: ${scores.contentToString()}")
            
            // TODO: 실제 모델의 출력 형식에 맞게 해석
            // 예시: 수면 단계 분류 (0: 깊은 수면, 1: 얕은 수면, 2: REM, 3: 각성)
            val sleepStages = arrayOf("깊은 수면", "얕은 수면", "REM 수면", "각성 상태")
            
            val resultText = StringBuilder()
            resultText.append("=== 수면 단계 분석 결과 ===\n\n")
            
            if (scores.size == sleepStages.size) {
                // 분류 확률로 해석
                val maxIndex = scores.indices.maxByOrNull { scores[it] } ?: 0
                val maxProbability = scores[maxIndex]
                
                resultText.append("예측된 수면 단계: ${sleepStages[maxIndex]}\n")
                resultText.append("확률: ${String.format("%.2f", maxProbability * 100)}%\n\n")
                resultText.append("각 단계별 확률:\n")
                
                for (i in scores.indices) {
                    resultText.append("${sleepStages[i]}: ${String.format("%.2f", scores[i] * 100)}%\n")
                }
            } else {
                // 다른 형식의 출력
                resultText.append("모델 출력:\n")
                scores.forEachIndexed { index, score ->
                    resultText.append("Output[$index]: ${String.format("%.4f", score)}\n")
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
                        val firstSession = response.records.first()
                        val startTimeStr = firstSession.startTime.atZone(ZoneId.systemDefault()).toLocalDateTime()
                        val endTimeStr = firstSession.endTime.atZone(ZoneId.systemDefault()).toLocalDateTime()
                        
                        var resultText = """
                            지난 7일간 ${sessionCount}개의 수면 세션 발견
                            
                            첫 번째 세션:
                            시작: $startTimeStr
                            종료: $endTimeStr
                        """.trimIndent()
                        
                        // 모델이 로드되어 있으면 추론 실행
                        if (sleepModel != null) {
                            tvResult.text = "$resultText\n\n수면 단계를 분석하는 중..."
                            
                            // 백그라운드 스레드에서 추론 실행
                            withContext(Dispatchers.Default) {
                                try {
                                    // 데이터 전처리
                                    val inputTensor = preprocessSleepData(response.records)
                                    
                                    // 모델 추론
                                    val inferenceResult = runInference(inputTensor)
                                    
                                    // 결과 표시
                                    withContext(Dispatchers.Main) {
                                        tvResult.text = """
                                            $resultText
                                            
                                            ════════════════════════
                                            
                                            $inferenceResult
                                        """.trimIndent()
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "추론 중 오류", e)
                                    withContext(Dispatchers.Main) {
                                        tvResult.text = """
                                            $resultText
                                            
                                            ════════════════════════
                                            추론 오류: ${e.message}
                                        """.trimIndent()
                                    }
                                }
                            }
                        } else {
                            resultText += "\n\n⚠️ 모델이 로드되지 않아 수면 단계 분석을 수행할 수 없습니다."
                            tvResult.text = resultText
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
