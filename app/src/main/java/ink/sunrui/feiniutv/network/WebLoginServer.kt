package ink.sunrui.feiniutv.network

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import ink.sunrui.feiniutv.store.ServerStore
import java.net.URLDecoder
import java.security.SecureRandom

/**
 * 内嵌 HTTP 服务，专门用于"手机扫码登录"流程。
 *
 * 工作方式：
 *   1. LoginActivity onCreate → [WebLoginServer.start] 在 LAN 0.0.0.0 上挑一个空闲端口监听
 *   2. 生成一次性 nonce（8 字节随机 hex），构造 URL `http://<lan-ip>:<port>/<nonce>`
 *   3. URL 进 QR，TV 显示
 *   4. 手机扫码访问 → 服务返回 HTML 表单（极简，纯文本）
 *   5. 用户提交 → POST /<nonce>/submit → 服务调用 [onSubmit] 回调把账密推给 LoginActivity
 *   6. 表单页显示「已提交，请回 TV 查看结果」
 *   7. LoginActivity onDestroy → [WebLoginServer.stop]
 *
 * 安全：
 *   - nonce 一次性，2^64 不可枚举
 *   - 提交成功后立刻把 nonce 失效，后续请求 404
 *   - 不存储任何提交内容；密码原样回传给 LoginActivity 后由后者负责传给 NasApiClient.login
 *   - 仅 LAN 监听；端口由 OS 分配（避开常用端口）
 *
 * 严守约束：完全不联网到任何第三方，仅监听本地端口。
 */
class WebLoginServer(
    private val port: Int,
    private val onSubmit: (username: String, password: String) -> Unit
) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "WebLoginServer"

        /** 让 OS 自动分配端口；避免与 HlsProxyServer 等已有服务冲突 */
        const val EPHEMERAL_PORT = 0

        /** 生成 16 位 hex nonce（8 字节随机） */
        fun newNonce(): String {
            val bytes = ByteArray(8)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }

    @Volatile private var nonce: String = newNonce()
    @Volatile private var submitted: Boolean = false

    fun currentNonce(): String = nonce
    fun isSubmitted(): Boolean = submitted

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri.orEmpty()
        val method = session.method
        Log.d(TAG, "$method $uri remote=${session.remoteIpAddress}")

        // 路径形如 /<nonce>      → GET → 表单
        //           /<nonce>/submit → POST → 接收提交
        //           其它 → 404
        val parts = uri.trim('/').split('/')
        val urlNonce = parts.getOrNull(0).orEmpty()
        val action = parts.getOrNull(1).orEmpty()

        if (urlNonce != nonce || submitted) {
            return plain(Response.Status.NOT_FOUND, "404 - link expired")
        }

        return when {
            method == Method.GET && action.isEmpty() -> renderForm(null)
            method == Method.POST && action == "submit" -> handleSubmit(session)
            else -> plain(Response.Status.METHOD_NOT_ALLOWED, "405")
        }
    }

    private fun handleSubmit(session: IHTTPSession): Response {
        return try {
            // NanoHTTPD 要求显式 parseBody 以读取 POST form
            val files = HashMap<String, String>()
            session.parseBody(files)
            val params = session.parameters
            val username = (params["username"]?.firstOrNull() ?: "").trim()
            val password = params["password"]?.firstOrNull() ?: ""

            if (username.isBlank() || password.isBlank()) {
                return renderForm("用户名和密码均不能为空")
            }

            // 推给 LoginActivity 处理；UI 线程切换由调用方负责
            try {
                onSubmit(username, password)
            } catch (e: Exception) {
                Log.e(TAG, "onSubmit threw", e)
            }

            // 立刻失效该 nonce（一次性使用）
            submitted = true
            renderSubmitted()
        } catch (e: Exception) {
            Log.e(TAG, "handleSubmit threw", e)
            plain(Response.Status.INTERNAL_ERROR, "500 - ${e.message}")
        }
    }

    private fun renderForm(error: String?): Response {
        val errorBlock = error?.let { "<div class=err>${escape(it)}</div>" }.orEmpty()
        val serverInfo = escape(ServerStore.getRootUrl())
        val action = "/$nonce/submit"
        val html = """
            <!doctype html>
            <html lang="zh">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
            <title>飞牛 TV 登录</title>
            <style>
              :root { color-scheme: dark; }
              html,body { background:#15171a; color:#fff; font-family:-apple-system,sans-serif; margin:0; padding:0; }
              .wrap { max-width:420px; margin:0 auto; padding:32px 20px; }
              h1 { font-size:22px; font-weight:600; margin:0 0 6px; }
              .sub { color:#a6ffffff; font-size:14px; margin-bottom:24px; word-break:break-all; }
              .field { display:flex; flex-direction:column; margin-bottom:16px; }
              label { font-size:13px; color:#a6ffffff; margin-bottom:6px; }
              input { font-size:17px; padding:14px 16px; border-radius:10px; border:1px solid #33ffffff;
                       background:#23252b; color:#fff; box-sizing:border-box; }
              input:focus { outline:none; border-color:#3374db; }
              button { width:100%; padding:16px; font-size:17px; font-weight:600; border:0; border-radius:12px;
                        background:#3374db; color:#fff; margin-top:8px; cursor:pointer; }
              button:active { background:#0e4caf; }
              .err { color:#ff6e6e; background:#3a1e1e; padding:10px 12px; border-radius:8px;
                      margin-bottom:16px; font-size:14px; }
              .tip { color:#59ffffff; font-size:12px; margin-top:18px; line-height:1.5; }
            </style>
            </head>
            <body>
              <div class="wrap">
                <h1>飞牛 TV · 登录</h1>
                <div class="sub">服务器：$serverInfo</div>
                $errorBlock
                <form method="post" action="$action" autocomplete="off">
                  <div class="field">
                    <label for="u">用户名</label>
                    <input id="u" name="username" type="text" autocapitalize="off" autocorrect="off" required>
                  </div>
                  <div class="field">
                    <label for="p">密码</label>
                    <input id="p" name="password" type="password" required>
                  </div>
                  <button type="submit">登 录</button>
                </form>
                <div class="tip">提交后请回到电视查看结果。此页仅在本次登录有效，一次性使用。</div>
              </div>
            </body>
            </html>
        """.trimIndent()
        return html(html)
    }

    private fun renderSubmitted(): Response {
        val html = """
            <!doctype html>
            <html lang="zh"><head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>已提交</title>
            <style>
              html,body { background:#15171a; color:#fff; font-family:-apple-system,sans-serif;
                          margin:0; padding:0; height:100%; }
              .wrap { display:flex; align-items:center; justify-content:center; height:100%; }
              .card { text-align:center; padding:32px; }
              h1 { font-size:24px; margin:0 0 10px; }
              p { color:#a6ffffff; font-size:15px; line-height:1.6; margin:6px 0; }
            </style>
            </head><body>
              <div class="wrap"><div class="card">
                <h1>✓ 已提交</h1>
                <p>请回到电视查看登录结果。</p>
                <p>此链接已失效，可关闭页面。</p>
              </div></div>
            </body></html>
        """.trimIndent()
        return html(html)
    }

    private fun html(body: String): Response {
        val r = newFixedLengthResponse(Response.Status.OK, "text/html; charset=UTF-8", body)
        r.addHeader("Cache-Control", "no-store")
        return r
    }

    private fun plain(status: Response.IStatus, body: String): Response =
        newFixedLengthResponse(status, "text/plain; charset=UTF-8", body)

    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;")
}
