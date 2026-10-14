plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.lizhihua.carplaysender"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.lizhihua.carplaysender"
        minSdk = 31
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        aidl = true
    }

    lint {
        // 新代码引入 lint 问题（error 级）直接使构建失败；存量问题在基线文件中豁免。
        //  ./gradlew :app:lintDebug          —— 检查（pre-commit 钩子提交前自动执行）
        //  ./gradlew :app:updateLintBaseline —— 修复问题后重建基线（有意豁免时）
        abortOnError = true
        baseline = file("lint-baseline.xml")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // CarPlay 风格桌面：分页网格
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    // AirPlay 配对密码学：X25519/Ed25519/ChaCha20-Poly1305/HKDF-SHA512
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    // 隐形屏模式：shell 权限（无线调试激活，免 root）
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    testImplementation("junit:junit:4.13.2")
}
