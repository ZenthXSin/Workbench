package io.github.zenthxsin.rustwright.core.account

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 账号信息带出 NewAPI 角色：管理员（10）、超级管理员（100）才算管理员；没有这个字段按普通用户。 */
class AccountRoleTest {
    private fun info(user: JSONObject): NewApiAccountInfo {
        var result: NewApiAccountInfo? = null
        runTest {
            MockWebServer().use { server ->
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                        "/api/user/self" -> ok(user)
                        "/api/status" -> ok(JSONObject().put("quota_per_unit", 500000).put("display_in_currency", true))
                        "/api/user/topup/info" -> ok(JSONObject())
                        else -> MockResponse().setResponseCode(404)
                    }
                }
                server.start()
                val origin = "http://127.0.0.1:${server.port}"
                val repo = NewApiAccountRepository(object : AccountSessionStore {
                    var v: String? = null
                    override fun load() = v
                    override fun save(value: String?) { v = value }
                }, NewApiOAuthConfig(issuer = "$origin/oidc"))
                repo.accept(NewApiOAuthTokens("access", "refresh", null, System.currentTimeMillis() + 3_600_000, "user-1"))
                result = repo.account()
            }
        }
        return result!!
    }

    private fun ok(data: JSONObject) = MockResponse().setBody(JSONObject().put("success", true).put("data", data).toString())
    private fun user(role: Int?) = JSONObject().put("username", "zx").put("quota", 0).apply { role?.let { put("role", it) } }

    @Test fun adminAndRootAreAdmins() {
        assertEquals(10, info(user(10)).role); assertTrue(info(user(10)).isAdmin)
        assertTrue(info(user(100)).isAdmin)
    }

    @Test fun commonUserOrMissingRoleIsNot() {
        assertFalse(info(user(1)).isAdmin)
        assertEquals(1, info(user(null)).role)
    }
}
