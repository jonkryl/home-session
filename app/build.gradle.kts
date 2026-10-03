plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseBannerId = providers.gradleProperty("homeSessionBannerId")
    .orElse(providers.environmentVariable("HOME_SESSION_YANDEX_BANNER_ID")).orElse("")
val keyStorePath = providers.environmentVariable("HOME_SESSION_KEYSTORE_FILE").orNull
val signingValues = listOf("HOME_SESSION_KEYSTORE_PASSWORD", "HOME_SESSION_KEY_ALIAS", "HOME_SESSION_KEY_PASSWORD")
    .associateWith { providers.environmentVariable(it).orNull }

android {
    namespace = "com.jonkryl.homesession"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.jonkryl.homesession"
        minSdk = 24
        targetSdk = 36
        versionCode = providers.gradleProperty("versionCode").orElse("1").get().toInt()
        versionName = providers.gradleProperty("versionName").orElse("1.0.0").get()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "PRIVACY_POLICY_URL", "\"https://jonkryl.github.io/home-session/privacy/\"")
    }
    signingConfigs {
        if (keyStorePath != null && signingValues.values.all { !it.isNullOrBlank() }) {
            create("release") {
                storeFile = file(keyStorePath)
                storePassword = signingValues["HOME_SESSION_KEYSTORE_PASSWORD"]
                keyAlias = signingValues["HOME_SESSION_KEY_ALIAS"]
                keyPassword = signingValues["HOME_SESSION_KEY_PASSWORD"]
            }
        }
    }
    buildTypes {
        debug {
            buildConfigField("String", "YANDEX_BANNER_ID", "\"demo-banner-yandex\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
            buildConfigField("String", "YANDEX_BANNER_ID", "\"${releaseBannerId.get()}\"")
        }
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = true; checkReleaseBuilds = true }
    packaging { resources.excludes += setOf("META-INF/LICENSE*", "META-INF/NOTICE*") }
}

val validateReleaseConfiguration = tasks.register("validateReleaseConfiguration") {
    doLast {
        check(Regex("R-M-\\d+-\\d+").matches(releaseBannerId.get())) {
            "Release requires a real R-M banner in HOME_SESSION_YANDEX_BANNER_ID / homeSessionBannerId."
        }
        check(keyStorePath != null && file(keyStorePath).isFile && signingValues.values.all { !it.isNullOrBlank() }) {
            "Release requires protected HOME_SESSION_KEYSTORE_FILE/PASSWORD, HOME_SESSION_KEY_ALIAS and HOME_SESSION_KEY_PASSWORD."
        }
    }
}
tasks.configureEach {
    if (name == "preReleaseBuild") dependsOn(validateReleaseConfiguration)
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation("androidx.activity:activity:1.6.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.yandex.android:mobileads:8.5.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    androidTestImplementation("androidx.test:core-ktx:1.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
