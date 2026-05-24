package ink.sunrui.feiniutv.store

/**
 * 账号与 token 持久化。
 *
 * 设计原则：
 *   - **不存密码**：密码用完即弃，仅 token 长期保留
 *   - token 失效（401）由调用方 [clearToken] 清掉，路由回登录页
 *   - username 单独保留，登录页可预填，减少用户输入
 *
 * 没有第三方上报，没有云端同步。
 */
object AccountStore {
    private const val K_USERNAME = "account_username"
    private const val K_TOKEN = "account_token"

    fun saveUsername(username: String) {
        AppPrefs.get().edit().putString(K_USERNAME, username.trim()).apply()
    }

    fun saveToken(token: String) {
        AppPrefs.get().edit().putString(K_TOKEN, token).apply()
    }

    fun clearToken() {
        AppPrefs.get().edit().remove(K_TOKEN).apply()
    }

    fun clearAll() {
        AppPrefs.get().edit()
            .remove(K_USERNAME)
            .remove(K_TOKEN)
            .apply()
    }

    fun getUsername(): String = AppPrefs.get().getString(K_USERNAME, "") ?: ""
    fun getToken(): String = AppPrefs.get().getString(K_TOKEN, "") ?: ""
    fun hasToken(): Boolean = getToken().isNotBlank()
}
