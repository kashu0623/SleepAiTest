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
import java.time.LocalDateTime
import java.time.ZoneId

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    // HealthConnectClient 인스턴스
    private lateinit var healthConnectClient: HealthConnectClient

    // 필요한 권한 Set (문자열로 정의)
    private val permissions = setOf(
        HealthPermission.getReadPermission(SleepSessionRecord::class)
    )

    // 권한 요청을 처리할 ActivityResultLauncher
    private lateinit var permissionLauncher: ActivityResultLauncher<Set<String>>

    // UI 요소
    private lateinit var btnOpenHealthConnect: Button
    private lateinit var btnFetchData: Button
    private lateinit var tvResult: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // UI 요소 찾기
        btnOpenHealthConnect = findViewById(R.id.btn_open_health_connect)
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

        // 버튼 클릭 리스너 설정
        btnFetchData.setOnClickListener {
            checkPermissionsAndFetchData()
        }
    }

    // Health Connect 설정 화면 열기
    private fun openHealthConnect() {
        try {
            Log.d(TAG, "Health Connect 설정 화면 열기 시도")
            
            // Android 14 이상에서는 Health Connect가 시스템에 내장되어 있음
            val intent = Intent("androidx.health.ACTION_HEALTH_CONNECT_SETTINGS")
            
            // Intent를 처리할 수 있는 앱이 있는지 확인
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
                tvResult.text = "Health Connect 설정을 열었습니다.\n\n설정에서 이 앱에 수면 데이터 읽기 권한을 수동으로 부여한 후 돌아와서 '수면 데이터 가져오기'를 눌러주세요."
            } else {
                // 대체 방법: 앱 설정 화면 열기
                Log.d(TAG, "Health Connect 설정을 찾을 수 없음, 앱 설정 화면 열기")
                val appSettingsIntent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                startActivity(appSettingsIntent)
                tvResult.text = "앱 설정 화면을 열었습니다.\n권한 설정을 확인해주세요."
            }
        } catch (e: Exception) {
            Log.e(TAG, "설정 화면 열기 실패", e)
            tvResult.text = "설정 화면을 열 수 없습니다: ${e.message}"
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

    // 수면 데이터 가져오기
    private suspend fun fetchSleepData() {
        lifecycleScope.launch {
            try {
                Log.d(TAG, "수면 데이터 가져오기 시작")
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
                        
                        tvResult.text = """
                            지난 7일간 ${sessionCount}개의 수면 세션 발견
                            
                            첫 번째 세션:
                            시작: $startTimeStr
                            종료: $endTimeStr
                        """.trimIndent()
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
