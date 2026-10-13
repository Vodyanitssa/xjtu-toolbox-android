package com.xjtu.toolbox.webvpn

import com.xjtu.toolbox.error.FriendlyError
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 网关拒绝页是 HTTP 200 的 HTML（#125 的现场样本），只能认内容。
 * 「目标站点连不上」的页面也是 200，但那不是权限问题，不能被误认。
 */
class WebVpnDeniedTest {

    private val deniedBody = "<html><head><title>访问被拒绝</title></head><body>您没有访问该资源的权限，请联系管理员处理</body></html>"
    private val unreachableBody = "<html><head><title>访问出错</title></head><body><h3>出错啦！该网站无法访问!</h3></body></html>"

    @Test
    fun 拒绝页能认出() {
        assertTrue(WebVpnDenied.isDeniedPage(deniedBody))
        assertTrue(WebVpnDenied.isDeniedPage("<title> 访问被拒绝 </title>"))
        assertTrue(WebVpnDenied.isDeniedPage("您没有访问该资源的权限，请联系管理员处理"))
    }

    @Test
    fun 目标站点连不上和正常页面都不算拒绝() {
        assertFalse(WebVpnDenied.isDeniedPage(unreachableBody))
        assertFalse(WebVpnDenied.isDeniedPage("<html><title>思源学堂</title></html>"))
        assertFalse(WebVpnDenied.isDeniedPage(""))
    }

    private fun clientReturning(contentType: String, body: String) = OkHttpClient.Builder()
        .addInterceptor(WebVpnInterceptor())
        .addInterceptor { chain ->
            Response.Builder()
                .request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("Content-Type", contentType)
                .body(body.toResponseBody(contentType.toMediaType()))
                .build()
        }
        .build()

    private fun fetch(client: OkHttpClient) =
        client.newCall(Request.Builder().url("https://rg.lib.xjtu.edu.cn/qspace").build()).execute()

    @Test
    fun 拦截器遇到拒绝页抛专门的异常() {
        try {
            fetch(clientReturning("text/html; charset=utf-8", deniedBody)).use { }
            fail("应该抛 WebVpnDeniedException")
        } catch (e: WebVpnDeniedException) {
            assertEquals(WebVpnDeniedException.MESSAGE, FriendlyError.of(e))
        }
    }

    @Test
    fun 拦截器放行正常页面和接口数据() {
        fetch(clientReturning("text/html", "<html>座位图</html>")).use { assertEquals(200, it.code) }
        // 业务接口返回的 JSON 里恰好出现这句话也不该被截
        fetch(clientReturning("application/json", """{"msg":"您没有访问该资源的权限"}""")).use { assertEquals(200, it.code) }
    }

    @Test
    fun 申请入口指向事务中心() {
        assertTrue(WebVpnDenied.APPLY_URL.startsWith("https://workflow.xjtu.edu.cn/"))
        assertTrue("app_id=326" in WebVpnDenied.APPLY_URL)
    }
}
