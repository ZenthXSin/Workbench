package io.github.zenthxsin.rustwright.core.agent

import io.github.zenthxsin.rustwright.core.runtime.Platform
import java.io.File

/** 一个技能（SKILL.md）：[body] 是 frontmatter 之后的正文，[group] 为所属分组目录名。 */
data class Skill(
    val name: String,
    val description: String,
    val body: String,
    val group: String = "",
    val source: String = "",
)

/**
 * 技能加载（沿用助手与 RustPic 的 SKILL.md 约定）：
 * - 随包技能：`assets/<root>/index.json` 列出目录名，或直接扫描子目录；
 * - 用户 / 项目技能：任意目录下 `<name>/SKILL.md`，含 `group.md` 的目录视为分组。
 * Frontmatter 至少要有 `description`；`name` 缺省用目录名。
 */
object Skills {

    private val installed = LinkedHashMap<String, Skill>()

    /** 已安装的技能（同名后装的覆盖先装的）。 */
    val all: List<Skill> @Synchronized get() = installed.values.toList()

    fun parse(dirName: String, text: String, group: String = "", source: String = ""): Skill? {
        val (meta, body) = frontmatter(text)
        val name = meta["name"]?.takeIf { it.isNotBlank() } ?: dirName
        val description = meta["description"]?.takeIf { it.isNotBlank() } ?: return null
        return Skill(name = name, description = description, body = body.trim(), group = group, source = source)
    }

    /** 读取随包技能。 */
    fun fromAssets(platform: Platform, root: String): List<Skill> {
        val names = platform.openAsset("$root/index.json")?.use { input ->
            Regex("\"([^\"]+)\"").findAll(input.readBytes().toString(Charsets.UTF_8).substringAfter('[').substringBefore(']'))
                .map { it.groupValues[1] }.toList()
        } ?: platform.listAssets(root)
        return names.mapNotNull { dir ->
            platform.openAsset("$root/$dir/SKILL.md")?.use { parse(dir, it.readBytes().toString(Charsets.UTF_8), source = "asset") }
        }
    }

    /** 读取目录下的技能（一层子目录；含 group.md 的子目录展开为分组）。 */
    fun fromDirectory(root: File): List<Skill> {
        if (!root.isDirectory) return emptyList()
        return root.listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name }.flatMap { dir ->
            val skillFile = File(dir, "SKILL.md")
            when {
                skillFile.isFile -> listOfNotNull(parse(dir.name, skillFile.readText()))
                File(dir, "group.md").isFile ->
                    dir.listFiles().orEmpty().filter { File(it, "SKILL.md").isFile }.sortedBy { it.name }
                        .mapNotNull { parse(it.name, File(it, "SKILL.md").readText(), group = dir.name) }
                else -> emptyList()
            }
        }
    }

    /** 安装技能，同名后者覆盖前者（项目技能可覆盖随包技能）。 */
    @Synchronized
    fun install(skills: List<Skill>) {
        skills.forEach { installed[it.name] = it }
    }

    /** 拆出 `---` 包围的 frontmatter（只认简单的 `key: value` 行）；没有时整段都是正文。 */
    internal fun frontmatter(text: String): Pair<Map<String, String>, String> {
        val lines = text.replace("\r\n", "\n").trimStart('﻿').split("\n")
        if (lines.firstOrNull()?.trim() != "---") return emptyMap<String, String>() to lines.joinToString("\n")
        val end = (1 until lines.size).firstOrNull { lines[it].trim() == "---" } ?: return emptyMap<String, String>() to lines.joinToString("\n")
        val meta = linkedMapOf<String, String>()
        var i = 1
        while (i < end) {
            val line = lines[i++]
            if (line.isBlank() || line.startsWith(' ') || line.trimStart().startsWith("#") || ':' !in line) continue
            val key = line.substringBefore(':').trim()
            var value = line.substringAfter(':').trim()
            if (value in setOf("|", "|-", "|+", ">", ">-", ">+")) {
                val literal = value.startsWith('|')
                val block = mutableListOf<String>()
                while (i < end && (lines[i].isBlank() || lines[i].startsWith(' '))) block += lines[i++].trim()
                value = block.joinToString(if (literal) "\n" else " ").trim()
            } else if (value.startsWith('"') && value.endsWith('"')) {
                value = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(value).let {
                    (it as kotlinx.serialization.json.JsonPrimitive).content
                } }.getOrElse { value.trim('"') }
            } else if (value.startsWith('\'') && value.endsWith('\'')) value = value.drop(1).dropLast(1).replace("''", "'")
            meta[key] = value
        }
        return meta to lines.drop(end + 1).joinToString("\n").trimStart('\n')
    }
}
