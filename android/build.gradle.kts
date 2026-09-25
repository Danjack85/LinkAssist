// 项目放在含中文的路径时,Gradle 测试进程可能无法从中文目录加载类;
// 此时把构建产物整体迁移到纯 ASCII 路径。CI 等 ASCII 路径环境不受影响。
val rootPath = rootDir.absolutePath
if (rootPath.any { it.code > 127 }) {
    val asciiBuildRoot = File("C:/LinkAssistBuild")
    if (!asciiBuildRoot.isDirectory) asciiBuildRoot.mkdirs()
    allprojects {
        layout.buildDirectory.set(File(asciiBuildRoot, if (this@allprojects == rootProject) "root" else name))
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
