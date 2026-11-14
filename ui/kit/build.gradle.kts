plugins {
    alias(libs.plugins.rw.jvm.library)
}

// 融合版界面的组件库（照 Claude App 风格的原型）：令牌、按钮、列表组、底部弹层、侧边栏、通知横幅、上下文圆环
dependencies {
    api(project(":ui:designsystem"))
    implementation(libs.kotlinx.coroutines.core)
}
