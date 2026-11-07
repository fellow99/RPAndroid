plugins {
    // AGP 9.x：builtInKotlin 默认开启，模块不再单独 apply org.jetbrains.kotlin.android。
    // （compose / serialization 插件在真正引入对应功能时再接入，见 TECH.md 标注。）
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
}
