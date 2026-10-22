import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * 版本号约定：`MAJOR.MINOR.PATCH`（语义化版本）。
 *
 * `versionCode` 由 `versionName` **推导**而不是手填两处——两者不同步是更新失效的
 * 经典原因（系统只看 versionCode，填小了用户装不上新包，且不会有任何提示）。
 * 0.x 表示实验性阶段；发布前只需改这一行。
 *
 * CI（`.github/workflows/android-ci.yml`）每次推送会传
 * `-Pcardeck.buildNumber=<github.run_number>`，得到 `0.1.13.<N>` / `versionCode = base*10000+N`
 * 的**正式签名**包（见下方 [buildNumber]），不需要往仓库写提交。
 */
val appVersionName = "0.1.13"
val appVersionCode =
    appVersionName
        .split(".")
        .let { (major, minor, patch) -> major.toInt() * 10_000 + minor.toInt() * 100 + patch.toInt() }

/**
 * 预发布构建号（CI 每次推送传 `-Pcardeck.buildNumber=<run_number>`）。
 *
 *  - `versionName` 追加 `.N`：`0.1.13` → `0.1.13.42`；
 *  - `versionCode` 把低 4 位留给构建号：`113` → `1_130_042`。
 *
 * 效果：同一 base 下每次推送**单调递增**，能直接覆盖安装上一版；base 升到下一个
 * patch（`0.1.14` → `1_140_000`）时自动反超本 patch 的所有构建（要求 N < 10000）。
 * 不传时退回纯 base（`1_130_000`），本地/分叉构建仍然可用。
 *
 * **传了构建号就必须有正式 keystore**：否则这个包会被当成「正式版」流出去，
 * 而 debug 证书签的包日后换正式证书时所有用户都得卸载重装。
 */
val buildNumberText =
    providers.gradleProperty("cardeck.buildNumber").orNull?.takeIf { it.isNotBlank() }
val buildNumber = buildNumberText?.toIntOrNull()
check(buildNumberText == null || buildNumber != null) {
    "cardeck.buildNumber 必须是整数（收到 \"$buildNumberText\"）"
}
check(buildNumber == null || buildNumber in 1..9_999) {
    "cardeck.buildNumber 必须在 1..9999（versionCode 的低 4 位留给构建号），当前=$buildNumber"
}
val effectiveVersionName = if (buildNumber == null) appVersionName else "$appVersionName.$buildNumber"
val effectiveVersionCode =
    if (buildNumber == null) appVersionCode * 10_000 else appVersionCode * 10_000 + buildNumber

/**
 * 发布签名配置：从仓库外的 `keystore.properties` 读取（已在 .gitignore 排除）。
 * 文件缺失时 release 退回 **debug 签名**——只给 fork 者/本地验证用；
 * CI 预发布构建（带 `-Pcardeck.buildNumber`）会直接失败，不会出 debug 签名的「正式版」。
 *
 * 密钥泄露 = 任何人可签发你的更新，因此 keystore 与密码**绝不入库**。
 */
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps =
    Properties().apply {
        if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
    }
val hasReleaseKeystore = keystoreProps.getProperty("storeFile") != null

// 预发布/CI 构建（带构建号）禁止落到 debug 签名：宁可构建失败，也不要把一个
// 日后无法被正式包覆盖安装的「正式版」发出去。
check(buildNumber == null || hasReleaseKeystore) {
    "传了 -Pcardeck.buildNumber 但没有 keystore.properties —— 预发布构建禁止使用 debug 签名"
}

android {
    namespace = "io.github.shaonialife.cardeck"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.shaonialife.cardeck"
        minSdk = 31
        targetSdk = 34
        versionCode = effectiveVersionCode
        versionName = effectiveVersionName
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // **不做代码混淆**：本项目依赖反射（SystemProperties、Shizuku API）与
            // 自定义 AIDL，R8 需要额外 keep 规则；首版先保证可运行，混淆留待后续。
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName(if (hasReleaseKeystore) "release" else "debug")
        }
        debug {
            // **不要加 applicationIdSuffix**：本项目 debug 包就是日常使用的包——
            // Shizuku 授权是按包名授予的，改包名会要求重新授权；AGENTS.md 里的
            // `run-as io.github.shaonialife.cardeck` 调试链路也会一起失效。
            versionNameSuffix = "-debug"
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
