plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.sleepaitest"
    compileSdk = 36
    
    // PyTorch 라이브러리 강제 해제
    configurations.all {
        resolutionStrategy {
            force("org.pytorch:pytorch_android:1.13.1")
            force("org.pytorch:pytorch_android_torchvision:1.13.1")
        }
    }

    defaultConfig {
        applicationId = "com.example.sleepaitest"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        // PyTorch를 위한 ABI 필터
        ndk {
            abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64"))
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    
    packaging {
        jniLibs {
            pickFirsts.add("lib/*/libpytorch_jni.so")
            pickFirsts.add("lib/*/libc10.so")
            pickFirsts.add("lib/*/libtorch_cpu.so")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    implementation("androidx.health.connect:connect-client:1.1.0-alpha07")
    
    // PyTorch Mobile (Full 버전)
    implementation("org.pytorch:pytorch_android:1.13.1")
    implementation("org.pytorch:pytorch_android_torchvision:1.13.1")
}