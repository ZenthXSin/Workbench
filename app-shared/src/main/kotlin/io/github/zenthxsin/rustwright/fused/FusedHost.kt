package io.github.zenthxsin.rustwright.fused

import kotlinx.coroutines.CoroutineScope

/** Assembly facade; individual features depend on the narrower service contracts. */
interface FusedHost : ProjectServices, FileServices, ToolServices, AccountServices, AgentServices, RuntimeServices {
    val scope: CoroutineScope
}
