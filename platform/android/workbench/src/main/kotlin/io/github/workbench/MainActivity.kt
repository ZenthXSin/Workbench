package io.github.workbench

import io.github.zenthxsin.rustwright.WorkbenchModules
import io.github.zenthxsin.rustwright.RustwrightApp
import io.github.zenthxsin.rustwright.platform.android.RwAndroidActivity
import io.github.zenthxsin.rustwright.ui.designsystem.FontFile
import io.github.zenthxsin.rustwright.ui.designsystem.RwArcApp

/** Android host for the generic workbench; business features are supplied by plugins. */
class MainActivity : RwAndroidActivity() {
    override fun createApp(fonts: List<FontFile>): RwArcApp =
        RustwrightApp(fonts, platform, linux, secrets, WorkbenchModules.all() + io.github.zenthxsin.rustwright.platform.android.AndroidSvgModule)
}
