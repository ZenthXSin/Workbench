package io.github.zenthxsin.rustwright.core.agent

/** 各场景给助手的补充说明（由模块注册）。 */
class AgentPersona {
    private val scenes = HashMap<AgentScene, String>()
    private val contexts = HashMap<AgentScene, () -> String?>()

    fun setScenePrompt(scene: AgentScene, prompt: String) { scenes[scene] = prompt }

    /** 每轮重新计算的场景上下文（如当前打开的模组、模组记忆）。 */
    fun setSceneContext(scene: AgentScene, provider: () -> String?) { contexts[scene] = provider }

    fun promptFor(scene: AgentScene): String = listOfNotNull(
        scenes[scene],
        contexts[scene]?.let { runCatching(it).getOrNull() }?.takeIf { it.isNotBlank() },
    ).joinToString("\n\n")
}
