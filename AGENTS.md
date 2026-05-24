# Feiniu TV Lite - 项目知识库

## 项目定位

飞牛TV饭（`ink.sunrui.feiniutv`）—— 面向 Android TV / 电视盒子的轻量级 NAS 媒体播放客户端。连接飞牛 NAS 私有 API，浏览媒体库、选片、播放视频，核心场景是**遥控器操作 + 大屏播放**。

## ⚠️ 硬性约束：minSdk 19 (Android 4.4)

**本项目必须运行在 Android 4.4 (API 19) 及以上设备。这是不可妥协的底线——目标硬件是老旧电视盒子。**

任何代码修改、依赖升级、新增第三方库都必须先确认兼容 API 19。违反此约束的改动会导致线上设备崩溃。

### 不可使用

- `java.util.Optional`、`java.util.stream.*`、`java.time.*` 等 Java 8+ API（desugaring 未启用）
- OkHttp 3.13.0+（含 4.x、5.x）—— 从 3.13.0 起最低要求 Android 5.0 (API 21) + Java 8，当前锁定 **3.12.13**（3.12.x 是官方为 Android 2.3+/Java 7+ 维护的最后分支，已于 2021-12-31 停止维护）
- Glide 5.x（尚未发布的大版本可能抬高 minSdk）
- 任何声明 `minSdk >= 21` 的 AAR / 库
- Kotlin 标准库中仅 JDK 8+ 才有实现的扩展（如部分 `kotlin.streams`）

### 升级依赖前必须检查

1. 该库的 `minSdkVersion` 或 Maven POM 中声明的最低 Android 版本
2. 该库是否内部调用了 API 19 不存在的系统方法（即使编译通过，运行时会 `NoSuchMethodError`）
3. 如有 native so，是否仍提供 armeabi-v7a 产物

### 当前已锁定的关键依赖版本（因 API 19）

| 依赖 | 锁定版本 | 不可升级原因 |
|---|---|---|
| OkHttp | 3.12.13 | 3.13.0 起要求 API 21 + Java 8（[官方 changelog](https://square.github.io/okhttp/changelogs/changelog_3x/#version-3130)）；3.12.x 是最后支持 API 19 的分支 |
| Retrofit | 2.9.0 | 兼容 OkHttp 3.x 的最后大版本 |
| Gson | 2.8.9 | 2.10+ 要求 Java 8+ |
| IjkPlayer | 0.8.8 | 最后的稳定版，支持 armeabi-v7a |
| AndroidX Leanback | 1.0.0 | TV 焦点管理基础 |

## 技术栈

| 层 | 选型 | 备注 |
|---|---|---|
| 语言 | Kotlin（纯 Kotlin，无 Java 源码） | JVM target 1.8 |
| 最低 SDK | API 19 (Android 4.4) | 需兼容老旧电视盒子，故 OkHttp 锁定 3.12.x |
| 播放器 | IjkPlayer 0.8.8 | SurfaceView 直渲染，非 ExoPlayer |
| 网络 | 双轨并存：Retrofit（声明但 ViewModel 未用）+ HttpURLConnection（`NasApiClient` 实际使用） | |
| 架构 | MVVM：`BaseVMActivity` + ViewBinding + ViewModel + LiveData | 反射泛型自动实例化 |
| TV 适配 | Leanback VerticalGridView/HorizontalGridView + 自定义 KeyInterceptor | |
| 缓存 | SharedPreferences（`MediaCacheStore`，2h TTL，版本号控刷新） | 已声明但未接入 ViewModel |

## 模块结构

```
ink.sunrui.feiniutv
├── LiteApplication              # MultiDexApplication + AppPrefs.init()
├── AppConfig                    # 动态读取 ServerStore + AccountStore（不再硬编码）
├── MainActivity                 # 主页：顶部 Tab（媒体库）+ 5 列海报网格
├── PlayerActivity               # IjkPlayer 播放页 + 顶/底 overlay
├── MediaItem / MediaLibrary     # UI 层数据类（非 API model）
├── LibraryTabAdapter            # 顶部 Tab 适配器（药丸按钮 + 焦点白底反色）
├── PosterAdapter                # 海报卡适配器（2:3 + 焦点描边+缩放）
├── modules/
│   ├── splash/SplashActivity    # 启动屏 + 路由（Scan / Login / Main）
│   ├── server/
│   │   ├── ScanDeviceActivity   # mDNS 发现 NAS（NsdManager _trim_media._tcp）
│   │   ├── AddDeviceActivity    # 手动 IP+端口（mDNS 失败兜底）
│   │   ├── ServerEntry          # 发现的设备数据类
│   │   └── ServerListAdapter
│   ├── login/LoginActivity      # QR 优先 + EditText 兜底
│   └── detail/                  # Phase 3
│       ├── DetailActivity       # 媒体详情：backdrop + 海报 + meta + 简介 + 播放按钮
│       └── DetailViewModel
├── store/
│   ├── AppPrefs                 # SharedPreferences 单例
│   ├── ServerStore              # NAS 服务器配置（host/port）
│   └── AccountStore             # 用户名 + token（密码不持久化）
├── base/
│   └── BaseVMActivity           # 泛型基类，反射实例化 ViewBinding + ViewModel
├── api/
│   ├── FeiniuApiService         # Retrofit 接口定义（暂未使用）
│   └── RetrofitClient           # Retrofit 单例
├── model/Models.kt              # API 响应数据类
├── network/
│   ├── NasApiClient             # 实际网络层：HttpURLConnection + Gson
│   ├── HlsProxyServer           # 本地 HLS 代理（auth 注入 + m3u8 重写）
│   ├── MediaCacheStore          # SharedPreferences 缓存
│   └── WebLoginServer           # 内嵌 HTTP 服务（NanoHTTPD），为 QR 扫码登录提供 LAN 表单
├── util/
│   ├── NetworkUtil              # 取本机 LAN IPv4
│   └── QrUtil                   # ZXing → Bitmap
└── widget/
    ├── TvVerticalGridView       # Leanback GridView + 按键拦截
    └── TvHorizontalGridView     # 同上，水平方向
```

## ⚠️ 硬约束：不访问任何第三方

**仅与用户自己的飞牛 NAS 直接通信**，不做任何第三方依赖：
- **不做**：扫码登录（官方 `/v/api/v1/logincode/*` 走官方移动端回调）
- **不做**：云中转/P2P（`/api/v1/visit/*`、`/api/v1/fn/con`、`event.fnnas.com`）
- **不做**：自更新检查（`/api/v1/check-update`）
- **不做**：任何遥测（Bugly、Tencent RMonitor 等）

替代方案：QR 扫码登录是**自包含的**——app 自己起 NanoHTTPD LAN 服务器，QR 内容是 `http://<本机 LAN IP>:<随机端口>/<8 字节 nonce>`，手机扫码后访问的是本机服务，不经过任何外部域名。

## UI 设计语言（Phase 1 已对齐官方 FNTV 1.2.5）

颜色 / 间距 / 字号 token 全部 1:1 复用官方 `colors.xml`：
- 背景：`bg_content_1=#15171A`（主页），`bg_placeholder=#23242B`（海报底）
- 文本：`text_title=#FFF`，`text_desc=#A6FFF`，`text_tips=#59FFF`，`text_title_fix=#101114`（白底反色）
- Tab 三态：`bg_tab_default=#CC2F3035` / `bg_tab_focus=#FFF` / `bg_tab_selected=#992F3035`
- 品牌色：`brand=#3374DB`（进度条 + 强调）

设计尺寸基准 1920×1080，在 720p 盒子上等比缩放（见 `dimens.xml`）。

**主页结构**：顶部品牌 + 状态药丸 → 中部水平 Tab（每库一个药丸按钮）→ 下部 5 列海报网格（Leanback `VerticalGridView.setNumColumns(5)`）。Tab 选中 vs 焦点解耦：浏览 Tab 不立即刷新数据，OK 键才触发 `fetchMediaItems(guid)`。

**焦点视觉**：海报卡 focused → 3dp 白色描边 + 1.06× 缩放 + 标题加亮（`bg_poster_focus` 选择器 + `OnFocusChangeListener` + `ViewPropertyAnimator`）。焦点态跨层级靠 `duplicateParentState="true"` 链传递。

**播放页 overlay**：顶部黑→透明渐变（标题+操作提示）/ 底部黑→透明渐变（时间码+进度条）/ 中央 loading。3 秒无操作自动淡出，任意按键唤醒。进度更新 500ms 心跳。

## 启动流程（Phase 2 新增）

```
SplashActivity (800ms 短停)
    ├── ServerStore 未配置 → ScanDeviceActivity
    │       ├── mDNS 监听 _trim_media._tcp（15s 超时）
    │       ├── 发现的设备点击 → 写 ServerStore → LoginActivity
    │       ├── 「重新扫描」按钮
    │       └── 「手动输入 IP」按钮 → AddDeviceActivity
    │                                    └── 输入 host+port → 写 ServerStore → LoginActivity
    ├── 已配置 ServerStore + 无 token → LoginActivity
    │       ├── 左半区：QR（http://<lan-ip>:<port>/<nonce>）
    │       │   - 手机扫码 → 本机 WebLoginServer → 表单页（极简 HTML）
    │       │   - 用户提交 → POST /<nonce>/submit → 回调 doLogin()
    │       ├── 右半区：EditText 兜底（遥控器输入）
    │       └── 「更换服务器」清 ServerStore + token → ScanDeviceActivity
    └── 已配置 + 有 token → MainActivity
            └── 任意请求 401 → tokenExpired LiveData → 清 token → 跳 LoginActivity
```

## QR 扫码登录详解（Phase 2 关键能力）

**架构**：TV app 自己启 LAN HTTP 服务，QR 让手机扫码访问。手机端在更易输入的浏览器里完成账密提交。

| 组件 | 实现 |
|---|---|
| HTTP 服务器 | NanoHTTPD 2.3.1（单 jar 50KB，Java 6+，API 19 兼容） |
| QR 生成 | ZXing core 3.3.3（纯 Java，最后支持 Java 7 的版本） |
| 监听端口 | `0.0.0.0` + OS 自动分配（避开冲突） |
| URL 格式 | `http://<lan-ip>:<port>/<8字节 hex nonce>` |
| 手机端页面 | 服务端渲染极简 HTML（无 JS、深色风格、与 app 同色板） |
| 一次性 | submit 成功后 nonce 立即失效，后续请求返回 404 |
| 生命周期 | LoginActivity onCreate 启动 / onDestroy 停止 |
| 兜底 | EditText 表单 + 登录按钮（遥控器/物理键盘可用） |

**安全边界**：8 字节 nonce（2^64 不可枚举）；服务只在 LoginActivity 存活期间监听；提交内容不落盘，立刻传给 NasApiClient.login。

## API 端点（飞牛 NAS 私有协议）

实际使用的端点（NasApiClient）：

| 方法 | 路径 | 用途 |
|---|---|---|
| POST | `/v/api/v1/login` | 登录，返回 token（注意：base url 含 /v 前缀，整体路径仍是 /v/api/v1/login） |
| GET | `/v/api/v1/mediadb/list` | 媒体库列表 |
| GET | `/v/api/v1/mediadb/sum` | 各库媒体数量统计 |
| POST | `/v/api/v1/item/list` | 库内媒体条目（分页） |
| POST | `/v/api/v1/play/info` | 条目播放信息 |
| POST | `/v/api/v1/play/quality` | 可用分辨率/码率列表 |
| POST | `/v/api/v1/play/play` | 获取 play_link（HLS 流地址） |
| GET | `/v/api/v1/media/range/{guid}` | 原始媒体 range 请求（降级兜底） |
| GET | `/v/api/v1/item/{guid}` | 条目详情（DetailActivity 用） |
| GET | `/v/api/v1/sys/img<path>` | 海报/backdrop 图片，**需 Authorization + Cookie 才返回 webp，否则 501** |

所有请求附带 `Authorization: {token}` + `cookie: mode=relay`。

> 注：早期 NasApiClient 写的路径前缀是 `/api/v1/...`，与 ServerStore.getBaseUrl() 拼接后实际是 `http://host:port/v/api/v1/...`，正确——见 `network/NasApiClient.kt` 内 `normalize(AppConfig.BASE_URL)` 的逻辑。

## 图片 URL 拼法陷阱（Phase 3 踩坑记录）

飞牛 NAS 把 SPA 前端挂在 `/v/*` 下，所以 `/v/<image-path>` 会被 nginx 兜底成 SPA index.html 而不是图片。**真正的图片端点**位于：

```
http://host:port/v/api/v1/sys/img<poster-path>
```

要点：
- 需要 `Authorization: <token>` + `cookie: mode=relay`，否则返回 501
- 取自官方 `x74.F()`（反编译 `defpackage/x74.java`）
- 适用于 `poster` / `posters` / `poster_list[]` / `backdrops` / `still_path` 等所有图片字段
- `poster_list` 路径形如 `/52/06/poster-uuid.webp`（注意以 `/` 开头）

`NasApiClient.posterLink(path)` 已封装此逻辑。Glide 加载时仍需 `GlideUrl + LazyHeaders` 注入 token：

```kotlin
val glideUrl = GlideUrl(url, LazyHeaders.Builder()
    .addHeader("Authorization", token)
    .addHeader("cookie", "mode=relay")
    .build())
Glide.with(ctx).load(glideUrl)...
```

## 媒体条目字段二态（Phase 3 踩坑记录）

`/v/api/v1/item/list` 返回的 item 海报字段随 `type` 不同：
- `type=Video` → `"poster"`（字符串，单张）
- `type=Directory` → `"poster_list"`（字符串数组，多张候选）

`NasApiClient.fetchMediaItems` 同时兼容两者：先取 `poster`，空则取 `poster_list[0]`。

`/v/api/v1/item/{guid}` 详情接口又改用 `"posters"`（字符串，单张，注意复数 s）和 `"backdrops"`（字符串，单张）。`NasApiClient.fetchItemDetail` 按此读取。

对刮削过的条目（带 `imdb_id`）会返回完整 metadata（overview、release_date、runtime、vote_average、genres、production_countries、original_title）；原始视频字段大多为空——`ItemDetail` 全部用 nullable/empty 兜底，UI 层做防御性渲染（缺字段跳过 meta 行的对应段）。


## 数据流

```
启动 → MainActivity.initData()
    → HomeViewModel.login()
        → NasApiClient.login() [IO 线程, HttpURLConnection]
        → 成功 → NasApiClient.fetchMediaLibraries(token)
            → GET /api/v1/mediadb/list + GET /api/v1/mediadb/sum
            → libraryList.postValue()
        → MainActivity 自动选中第一个库
            → HomeViewModel.fetchMediaItems(guid)
                → POST /api/v1/item/list → 遍历 item
                    → 每个 item 串行调用 buildPlayableItem():
                        POST /api/v1/play/info
                        POST /api/v1/play/quality（选最低码率）
                        POST /api/v1/play/play → 拿到 play_link
                → mediaItemList.postValue()

点击媒体 → PlayerActivity
    → IjkPlayer.setDataSource(url) + prepareAsync()
    → 失败 → 三级降级：
        stage 0: refetchPlayableUrlByItemGuid(preferLowestQuality=true)
        stage 1: buildOriginalRangeUrl(mediaGuid) → /api/v1/media/range/{guid}
        stage 2: Toast 播放失败
```

## API 端点（飞牛 NAS 私有协议）

| 方法 | 路径 | 用途 |
|---|---|---|
| POST | `/api/v1/login` | 登录，返回 token |
| GET | `/api/v1/mediadb/list` | 媒体库列表 |
| GET | `/api/v1/mediadb/sum` | 各库媒体数量统计 |
| POST | `/api/v1/item/list` | 库内媒体条目（分页） |
| POST | `/api/v1/play/info` | 条目播放信息（media_guid, video/audio/subtitle guid） |
| POST | `/api/v1/play/quality` | 可用分辨率/码率列表 |
| POST | `/api/v1/play/play` | 获取 play_link（HLS 流地址） |
| GET | `/api/v1/media/range/{guid}` | 原始媒体 range 请求（降级兜底） |

所有请求附带 `Authorization: {token}` + `cookie: mode=relay`。

## 编码约定

- **无 DI 框架**：手动单例（`object`），无 Hilt/Koin
- **无 Repository 层**：ViewModel 直接调用 NasApiClient
- **ViewBinding**：全局启用，BaseVMActivity 反射 inflate
- **线程模型**：ViewModel 用 `viewModelScope.launch(Dispatchers.IO)`；PlayerActivity 用 `kotlin.concurrent.thread` + `runOnUiThread`
- **错误处理**：`runCatching` / `Result<T>` 包装，不抛异常到 UI
- **日志**：MainActivity 内嵌绿色终端风格日志面板（调试用），`appendLog()` 贯穿全流程
- **遥控器适配**：所有列表 item 设置 `focusable=true`；GridView 拦截方向键防焦点逃逸

## 已知设计债务

1. **Retrofit 声明未使用**：`FeiniuApiService` + `RetrofitClient` 完整定义但 ViewModel 走的是 `NasApiClient`（HttpURLConnection），两套网络层并存
2. **buildPlayableItem 串行阻塞**：每个媒体条目需 3 次 HTTP 请求（info → quality → play），20 条目 = 60 次串行请求，列表加载极慢
3. **缓存层未接入**：`MediaCacheStore` 实现完整但 HomeViewModel 从未调用
4. **硬编码凭据**：`AppConfig` 写死 NAS 地址和账号密码，无配置 UI
5. **ProGuard 为空**：release 已关闭混淆（`minifyEnabled false`），proguard-rules.pro 空文件
6. **HlsProxyServer 未使用**：完整的本地 HLS 代理实现（auth 注入 + m3u8 重写），但播放流程直接传 URL 给 IjkPlayer 而非走代理
7. **PlayerActivity 未继承 BaseVMActivity**：直接继承 AppCompatActivity，与主页架构不一致

## 构建

```bash
./gradlew assembleDebug    # Debug APK
./gradlew assembleRelease  # Release APK（未签名，未混淆）
```

输出：`app/build/outputs/apk/`

## 修改须知

- **兼容 API 19（最高优先级）**：见顶部硬性约束。任何新增依赖、代码改动都必须在 API 19 设备上可运行。编译通过不代表运行安全——很多 Java/Android API 编译期不报错但运行时 `NoSuchMethodError`
- **TV 焦点**：任何新增列表/按钮必须设置 `focusable=true` + `nextFocusXxx`，否则遥控器无法操作
- **IjkPlayer native**：`packagingOptions.pickFirst` 已配置，新增 ABI 需同步更新
- **网络层选择**：如需统一，建议迁移至 Retrofit 路线（已定义好接口），废弃 NasApiClient 手动解析
