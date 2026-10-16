import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// ============================================================================
//  构建期凭据注入（FR-10.1 / FR-10.10）
//  明文口令只允许来自：环境变量 / local.properties / 本机未入库的 m0/.secrets/easynvr.json
//  产物中只出现 KDF 参数与 AES-GCM 密文，绝不出现明文地址、账号、口令。
// ============================================================================
val secretsJson = rootProject.file("m0/.secrets/easynvr.json")

fun readLocalProperties(): Map<String, String> {
    val f = rootProject.file("local.properties")
    if (!f.exists()) return emptyMap()
    return f.readLines().mapNotNull { raw ->
        val t = raw.trim()
        if (t.isEmpty() || t.startsWith("#") || !t.contains("=")) null
        else t.substringBefore("=").trim() to t.substringAfter("=").trim()
    }.toMap()
}

@Suppress("UNCHECKED_CAST")
fun readSecretsJson(): Map<String, String> {
    if (!secretsJson.exists()) return emptyMap()
    val parsed = groovy.json.JsonSlurper().parse(secretsJson) as? Map<String, Any?> ?: return emptyMap()
    return parsed.entries.filter { it.value is String }.associate { it.key to (it.value as String) }
}

fun secretOf(envKey: String, propKey: String, jsonKey: String): String {
    System.getenv(envKey)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    readLocalProperties()[propKey]?.takeIf { it.isNotEmpty() }?.let { return it }
    readSecretsJson()[jsonKey]?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    return ""
}

val blobDirProvider = layout.buildDirectory.dir("generated/jishi/assets")

val generateCredentialBlob = tasks.register("generateCredentialBlob") {
    // 不声明含口令的输入，避免口令状态进入构建缓存；每次构建重新生成密文。
    outputs.dir(blobDirProvider)
    outputs.upToDateWhen { false }
    doLast {
        val dir = blobDirProvider.get().asFile
        dir.mkdirs()
        val target = File(dir, "credential_blob.json")
        val host = secretOf("JISHI_HOST", "jishi.host", "host")
        val lanHost = secretOf("JISHI_LAN_HOST", "jishi.lanHost", "lan_host")
        val user = secretOf("JISHI_USERNAME", "jishi.username", "username")
        val pass = secretOf("JISHI_PASSWORD", "jishi.password", "password")
        val viewPass = secretOf("JISHI_VIEW_PASSWORD", "jishi.viewPassword", "view_password")
        if (host.isEmpty() || user.isEmpty() || pass.isEmpty() || viewPass.isEmpty()) {
            target.writeText("{\"v\":1,\"configured\":false}")
            logger.lifecycle("[jishi] 凭据未配置：已生成空凭据包（激活页会提示未配置）。请填写 m0/.secrets/easynvr.json")
            return@doLast
        }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val iterations = 200_000
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(viewPass.toCharArray(), salt, iterations, 256)).encoded
        val plain = "{\"host\":\"" + host + "\",\"lanHost\":\"" + lanHost +
            "\",\"username\":\"" + user + "\",\"password\":\"" + pass + "\"}"
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val b64 = Base64.getEncoder()
        val json = "{\"v\":1,\"kdf\":\"PBKDF2WithHmacSHA256\",\"iter\":" + iterations +
            ",\"salt\":\"" + b64.encodeToString(salt) + "\",\"iv\":\"" + b64.encodeToString(iv) +
            "\",\"ct\":\"" + b64.encodeToString(ct) + "\",\"configured\":true}"
        target.writeText(json)
        logger.lifecycle("[jishi] 凭据包已生成：AES-256-GCM / PBKDF2-HMAC-SHA256 / " + iterations + " 次（日志不输出口令）")
    }
}

// ---------------------------------------------------------------------------
//  网络明文白名单（构建期生成，安全审计 M-1/R14 整改）
//  产物里只出现「本次部署实际使用的域名」；模拟器/回环等调试地址只进 debug 源集。
// ---------------------------------------------------------------------------
fun domainOf(url: String): String =
    url.trim().removePrefix("http://").removePrefix("https://").substringBefore(':').substringBefore('/')

val nscMainDir = layout.buildDirectory.dir("generated/jishi/res/main")
val nscDebugDir = layout.buildDirectory.dir("generated/jishi/res/debug")

val generateNetworkSecurityConfig = tasks.register("generateNetworkSecurityConfig") {
    outputs.dir(nscMainDir)
    outputs.dir(nscDebugDir)
    outputs.upToDateWhen { false }
    doLast {
        fun write(dirProvider: Provider<Directory>, domains: List<String>) {
            val dir = File(dirProvider.get().asFile, "xml")
            dir.mkdirs()
            val items = domains.filter { it.isNotBlank() }.distinct()
                .joinToString("\n") { "        <domain includeSubdomains=\"false\">" + it + "</domain>" }
            val xml = """<?xml version="1.0" encoding="utf-8"?>
<!-- 构建期生成：不开启全局明文，只对本次部署实际使用的域名放行（安全审计 M-1/R14） -->
<network-security-config>
    <base-config cleartextTrafficPermitted="false">
        <trust-anchors>
            <certificates src="system" />
        </trust-anchors>
    </base-config>
    <domain-config cleartextTrafficPermitted="true">
$items
    </domain-config>
</network-security-config>
"""
            File(dir, "network_security_config.xml").writeText(xml)
        }
        val host = secretOf("JISHI_HOST", "jishi.host", "host")
        val lan = secretOf("JISHI_LAN_HOST", "jishi.lanHost", "lan_host")
        val prod = listOf(domainOf(host), domainOf(lan))
        write(nscMainDir, prod)
        // 调试地址只在 debug 变体里叠加（资源覆盖）
        write(nscDebugDir, prod + listOf("10.0.2.2", "127.0.0.1", "localhost"))
        logger.lifecycle("[jishi] 网络白名单已生成（生产 " + prod.filter { it.isNotBlank() }.joinToString(",") + "）")
    }
}

// 更新包验签：发布签名证书指纹（SHA-256）在构建期固定写入，运行时用于比对下载的 APK（FR-0.8）
val pinnedCertSha256: String = secretOf("JISHI_CERT_SHA256", "jishi.certSha256", "cert_sha256")

android {
    namespace = "com.jishi.monitor"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jishi.monitor"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        resourceConfigurations += listOf("zh", "en")
        vectorDrawables { useSupportLibrary = true }
        buildConfigField("String", "PINNED_CERT_SHA256", "\"" + pinnedCertSha256 + "\"")
        // 注意：不得把服务地址明文写进产物（AC-25）。外置配置地址在运行时由解密后的凭据主机推导。
        buildConfigField("String", "CONFIG_URL", "\"\"")
        // 外置配置验签公钥（Base64 DER）。留空 = 一律不采信外置配置，回退内置（FR-0.5 失败关闭）
        buildConfigField("String", "CONFIG_PUBKEY", "\"" + secretOf("JISHI_CONFIG_PUBKEY", "jishi.configPubKey", "config_pubkey") + "\"")
    }

    signingConfigs {
        create("release") {
            val ksPath = secretOf("JISHI_KEYSTORE", "jishi.keystore", "keystore")
            if (ksPath.isNotEmpty() && File(ksPath).exists()) {
                storeFile = File(ksPath)
                storePassword = secretOf("JISHI_KEYSTORE_PASSWORD", "jishi.keystorePassword", "keystore_password")
                keyAlias = secretOf("JISHI_KEY_ALIAS", "jishi.keyAlias", "key_alias")
                keyPassword = secretOf("JISHI_KEY_PASSWORD", "jishi.keyPassword", "key_password")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val ksPath = secretOf("JISHI_KEYSTORE", "jishi.keystore", "keystore")
            val hasReleaseKey = ksPath.isNotEmpty() && File(ksPath).exists()
            // 安全审计 B-1：禁止用 debug 签名产出正式 release 包。
            // 但检查必须**延后到真正打 release 包时**——否则在配置阶段抛异常会让
            // 没有密钥库的环境（例如刚 clone 仓库）连 assembleDebug 都构建不了。
            if (!hasReleaseKey && !project.hasProperty("allowDebugSigning")) {
                tasks.matching { it.name in listOf("packageRelease", "assembleRelease", "bundleRelease") }
                    .configureEach {
                        doFirst {
                            throw GradleException(
                                "未配置发布签名（jishi.keystore）。禁止用 debug 签名产出 release 包：" +
                                    "debug 包可被 adb/JDWP 调试并 dump 内存取到明文凭据与 Token（安全审计 B-1）。" +
                                    "如仅本地调试，请显式加 -PallowDebugSigning=true。"
                            )
                        }
                    }
                logger.lifecycle("[jishi] 未配置发布签名：release 打包会被拒绝；assembleDebug 仍可用。")
            } else if (!hasReleaseKey) {
                logger.lifecycle("[jishi] 警告：allowDebugSigning 已开启，本包仅限本地调试，禁止交付")
            }
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ""
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        encoding = "UTF-8"
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-opt-in=kotlin.RequiresOptIn")
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
    defaultConfig {
        // release 只带 arm64-v8a；debug 额外带 x86_64 以便在模拟器上验证 VLC 内核
        ndk { abiFilters += listOf("arm64-v8a") }
        // 直播内核选择：hybrid（默认，视频=自研 / 音频=VLC）| vlc（纯 VLC）| self（纯自研）
        buildConfigField(
            "String",
            "LIVE_KERNEL",
            "\"" + secretOf("JISHI_LIVE_KERNEL", "jishi.liveKernel", "live_kernel").ifEmpty { "hybrid" } + "\"",
        )
        buildConfigField(
            "boolean",
            "PLAYER_KERNEL_VLC_LIVE",
            secretOf("JISHI_VLC_LIVE", "jishi.vlcLive", "vlc_live").ifEmpty { "true" },
        )
    }
    buildTypes {
        getByName("debug") {
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        }
    }
    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/DEPENDENCIES", "META-INF/*.kotlin_module")
        }
        // 压缩原生库，控制 APK 体积（TD-002：未压缩 57MB → 压缩后 ≈34MB，满足 NFR-5）
        jniLibs { useLegacyPackaging = true }
    }
    sourceSets["main"].assets.srcDir(blobDirProvider)
    sourceSets["main"].res.srcDir(nscMainDir)
    sourceSets["debug"].res.srcDir(nscDebugDir)
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    lint {
        abortOnError = false
    }
}

tasks.named("preBuild") { dependsOn(generateCredentialBlob, generateNetworkSecurityConfig) }

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")

    implementation(platform("androidx.compose:compose-bom:2024.02.02"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.datastore:datastore-preferences:1.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.5.0")

    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")
    // HLS：回放改用服务端连续流（/records/devices/.../index.m3u8），
    // 从根本上消除"每 50 秒一段、跨段重建音频"造成的断续/电流
    implementation("androidx.media3:media3-exoplayer-hls:1.3.1")

    // libvlc（VLC 内核）：TD-002 裁决——直播 FLV+HEVC 交给 VLC（音频/同步成熟），
    // 自研内核保留为备用实现，可用 PLAYER_KERNEL_VLC_LIVE=false 一键回滚。
    implementation("org.videolan.android:libvlc-all:3.7.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
