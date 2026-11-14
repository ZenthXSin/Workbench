package io.github.zenthxsin.rustwright.ui.designsystem

/** 一个系统字体文件；ttc 集合需要 [index] 指定子字体。由平台层按优先级提供候选。 */
data class FontFile(val path: String, val index: Int = 0)
