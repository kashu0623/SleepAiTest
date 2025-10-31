package com.example.sleepaitest

import android.content.Intent
import android.net.Uri
import android.os.Bundle
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

    // HealthConnectClient 인스턴스
    private lateinit var healthConnectClient: HealthConnectClient

    // 필요한 권한 Set (문자열로 정의)
    private val permissions = setOf(
        HealthPermission.getReadPermission(SleepSessionRecord::class)
    )

    // 권한 요청을 처리할 ActivityResultLauncher
    private lateinit var permissionLauncher: ActivityResultLauncher<Set<String>>

    // UI 요소
    private lateinit var btnFetchData: Button
    private lateinit var tvResult: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // UI 요소 찾기
        btnFetchData = findViewById(R.id.btn_fetch_data)
        tvResult = findViewById(R.id.tv_result)

        // Health Connect 설치 확인
        val availability = HealthConnectClient.getSdkStatus(this)
        if (availability != HealthConnectClient.SDK_AVAILABLE) {
            tvResult.text = "Health Connect가 설치되어 있지 않습니다."
            btnFetchData.text = "Play Store에서 설치하기"
            btnFetchData.setOnClickListener {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata")
                }
                startActivity(intent)
            }
            return
        }

        // HealthConnectClient 초기화
        healthConnectClient = HealthConnectClient.getOrCreate(this)

        // 권한 요청 launcher 생성 (onCreate에서만 호출)
        val requestPermissionActivityContract = PermissionController.createRequestPermissionResultContract()
        permissionLauncher = registerForActivityResult(requestPermissionActivityContract) { granted ->
            lifecycleScope.launch {
                if (granted.containsAll(permissions)) {
                    fetchSleepData()
                } else {
                    withContext(Dispatchers.Main) {
                        tvResult.text = "권한이 거부되었습니다"
                    }
                }
            }
        }

        // 버튼 클릭 리스너 설정
        btnFetchData.setOnClickListener {
            checkPermissionsAndFetchData()
        }
    }

    // 권한 확인 및 데이터 가져오기
    private fun checkPermissionsAndFetchData() {
        lifecycleScope.launch {
            try {
                // 현재 권한 확인
                val granted = healthConnectClient.permissionController.getGrantedPermissions()
                
                // 필요한 권한이 모두 부여되었는지 확인
                val hasAllPermissions = permissions.all { permission ->
                    granted.contains(permission)
                }

                if (!hasAllPermissions) {
                    // 이미 onCreate에서 등록한 permissionLauncher 사용
                    permissionLauncher.launch(permissions)
                } else {
                    // 권한이 이미 부여됨
                    fetchSleepData()
                }
            } catch (e: Exception) {
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
                // 지난 7일간의 시간 범위 정의
                val endTime = LocalDateTime.now()
                val startTime = endTime.minusDays(7)
                
                val timeRangeFilter = TimeRangeFilter.between(
                    startTime.atZone(ZoneId.systemDefault()).toInstant(),
                    endTime.atZone(ZoneId.systemDefault()).toInstant()
                )

                // ReadRecordsRequest 생성
                val request = ReadRecordsRequest(
                    recordType = SleepSessionRecord::class,
                    timeRangeFilter = timeRangeFilter
                )

                // 수면 세션 데이터 가져오기
                val response = healthConnectClient.readRecords(request)

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
                withContext(Dispatchers.Main) {
                    tvResult.text = "데이터 가져오기 오류: ${e.message}"
                }
            }
        }
    }
}
