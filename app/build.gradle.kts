import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationParameters
import com.android.build.api.instrumentation.InstrumentationScope
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

abstract class StripKafkaJmxTransform
    : AsmClassVisitorFactory<InstrumentationParameters.None> {

    override fun createClassVisitor(
        classContext: ClassContext,
        nextClassVisitor: ClassVisitor
    ): ClassVisitor = object : ClassVisitor(Opcodes.ASM9, nextClassVisitor) {

        override fun visitMethod(
            access: Int, name: String, descriptor: String,
            signature: String?, exceptions: Array<String>?
        ): MethodVisitor {
            val next = super.visitMethod(access, name, descriptor, signature, exceptions)
            return if (name == "registerAppInfo" || name == "unregisterAppInfo") {
                object : MethodVisitor(Opcodes.ASM9) {
                    override fun visitCode() {
                        next.visitCode()
                        next.visitInsn(Opcodes.RETURN)
                        next.visitMaxs(0, 0)
                        next.visitEnd()
                    }
                }
            } else next
        }
    }

    override fun isInstrumentable(classData: ClassData): Boolean =
        classData.className == "org.apache.kafka.common.utils.AppInfoParser"
}


android {
    namespace   = "com.traintracker"
    compileSdk  = 35

    defaultConfig {
        applicationId = "com.traintracker"
        minSdk        = 26
        targetSdk     = 35
        versionCode   = 3
        versionName   = "3.0"
    }

    buildTypes {
        release {
            isMinifyEnabled   = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            // Minification disabled for faster debug builds
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        viewBinding = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "**/Metadata.kotlin_module"
            )
        }
    }
}

androidComponents {
    onVariants {
        it.instrumentation.transformClassesWith(
            StripKafkaJmxTransform::class.java,
            InstrumentationScope.ALL
        ) {}
        it.instrumentation.setAsmFramesComputationMode(
            FramesComputationMode.COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.kafka.clients) {
        exclude(group = "com.github.luben", module = "zstd-jni")
        exclude(group = "org.xerial.snappy", module = "snappy-java")
        exclude(group = "net.jpountz.lz4")
    }
    implementation(libs.slf4j.nop)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(files("libs/android-stubs.jar"))
}

tasks.register("testClasses") {
    dependsOn("compileDebugUnitTestKotlin")
}
