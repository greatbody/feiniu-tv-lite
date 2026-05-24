package ink.sunrui.feiniutv

import ink.sunrui.feiniutv.store.AccountStore
import ink.sunrui.feiniutv.store.ServerStore

/**
 * 全局应用配置：从 [ServerStore]/[AccountStore] 动态读取。
 *
 * **不再有硬编码** —— 第一次启动由 Splash → Scan → Login 流程引导用户填入。
 *
 * 旧代码（NasApiClient、RetrofitClient）以 `AppConfig.BASE_URL` 形式访问，保留向后兼容。
 */
object AppConfig {
    /** 业务接口 base，形如 `http://10.10.11.21:5666/v`。未配置时返回空字符串。 */
    val BASE_URL: String get() = ServerStore.getBaseUrl()

    /** 登录用户名。优先读 AccountStore 持久化值，没有则空串。 */
    val USERNAME: String get() = AccountStore.getUsername()

    /**
     * 密码：**不再持久化**。
     * 旧的 [NasApiClient.login] 仍读这个属性，调用方需在登录页临时把密码写到 [PASSWORD_TRANSIENT]，
     * 用完即清。
     */
    val PASSWORD: String get() = PASSWORD_TRANSIENT

    /** 仅本次 login 调用期间存活的密码。登录成功/失败后由调用方清空。 */
    @Volatile
    var PASSWORD_TRANSIENT: String = ""
}
