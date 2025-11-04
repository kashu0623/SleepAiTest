package com.example.sleepaitest

import android.app.Application
import android.util.Log

class SleepApp : Application() {
    
    companion object {
        private const val TAG = "SleepApp"
    }
    
    override fun onCreate() {
        super.onCreate()
        
        Log.d(TAG, "앱 초기화 완료")
    }
}

