// =====================================================
// 跨平台自动检测配置
// =====================================================
// 自动检测当前操作系统并配置对应的 JDK 路径
// =====================================================

val osName = System.getProperty("os.name").lowercase()
val osArch = System.getProperty("os.arch").lowercase()

// 检测操作系统类型
val isWindows = osName.startsWith("windows") || osName.contains("win")
val isMac = osName.startsWith("mac") || osName.contains("darwin") || osName.contains("macos")
val isLinux = osName.startsWith("linux") || osName.contains("unix")

// JDK 路径配置（根据操作系统自动选择）
val jdkHome = when {
    isWindows -> {
        val windowsPaths = listOf(
            "${System.getProperty("user.home")}/.gradle/jdks/eclipse_adoptium-21-amd64-windows.2",
            "${System.getProperty("user.home")}/.jdks/openjdk-24.0.1",
            "C:/Program Files (x86)/Eclipse Adoptium/jdk-17.0.15.6-hotspot",
            "C:/Program Files/Java/jdk-19",
            "C:/Program Files/Java/jdk-1.8",
            "C:/Program Files/Eclipse Adoptium/jdk-21.0.7.6-hotspot"
        )
        windowsPaths.firstOrNull { java.io.File(it).exists() }
            ?: "${System.getProperty("user.home")}/.gradle/jdks/eclipse_adoptium-21-amd64-windows.2"
    }
    isMac -> {
        val macPaths = listOf(
            "/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home",
            "/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home",
            "/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home",
            "/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home",
            "/Library/Java/JavaVirtualMachines/openjdk-21.jdk/Contents/Home",
            "/Library/Java/JavaVirtualMachines/openjdk-17.jdk/Contents/Home",
            "/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home",
            "/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
        )
        macPaths.firstOrNull { java.io.File(it).exists() }
            ?: "/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home"
    }
    isLinux -> {
        val linuxPaths = listOf(
            "/usr/lib/jvm/java-21-openjdk",
            "/usr/lib/jvm/java-17-openjdk",
            "/opt/java/openjdk"
        )
        linuxPaths.firstOrNull { java.io.File(it).exists() }
            ?: "/usr/lib/jvm/java-17-openjdk"
    }
    else -> null
}

// 打印检测结果
println(">>> Platform Detection:")
println("    OS: $osName ($osArch)")
println("    Is Windows: $isWindows")
println("    Is macOS: $isMac")
println("    Is Linux: $isLinux")
println("    JDK Home: $jdkHome")

// 诊断日志：写入项目根目录的 .cursor 目录
val projectRoot = System.getProperty("user.dir")
val diagnosticFile = java.io.File("$projectRoot/.cursor/debug-d1f364.log")
diagnosticFile.parentFile.mkdirs()
diagnosticFile.appendText(
    "{\"id\":\"log_${System.currentTimeMillis()}_platform_detect\",\"timestamp\":${System.currentTimeMillis()},\"sessionId\":\"d1f364\",\"runId\":\"diagnose\",\"hypothesisId\":\"JDK_RESOLVE\",\"location\":\"gradle-detect-platform.gradle.kts\",\"message\":\"platform_detect\",\"data\":{\"osName\":\"$osName\",\"osArch\":\"$osArch\",\"isWindows\":$isWindows,\"isMac\":$isMac,\"isLinux\":$isLinux,\"jdkHome\":\"$jdkHome\",\"gradleJavaHome\":\"${System.getProperty("org.gradle.java.home")}\",\"javaHome\":\"${System.getProperty("java.home")}\",\"envJdk17\":\"${System.getenv("JDK17_HOME")}\",\"envJdk21\":\"${System.getenv("JDK21_HOME")}\",\"envJavaHome\":\"${System.getenv("JAVA_HOME")}\"}}${System.lineSeparator()}",
    Charsets.UTF_8
)

// 强制设置 Gradle 使用检测到的 JDK
// 这会覆盖任何 IDE 或环境变量提供的 JDK 配置
jdkHome?.let { home ->
    System.setProperty("org.gradle.java.home", home)
    System.setProperty("java.home", home)
    
    // 确保 jlink 路径正确
    val jlinkPath = java.io.File(home, "bin/jlink")
    if (jlinkPath.exists()) {
        println("    jlink found: ${jlinkPath.absolutePath}")
    } else {
        println("    WARNING: jlink not found at ${jlinkPath.absolutePath}")
    }
}
