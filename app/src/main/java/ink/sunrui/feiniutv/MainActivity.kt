package ink.sunrui.feiniutv

import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import ink.sunrui.feiniutv.base.BaseVMActivity
import ink.sunrui.feiniutv.databinding.ActivityMainBinding
import ink.sunrui.feiniutv.modules.detail.DetailActivity
import ink.sunrui.feiniutv.modules.login.LoginActivity
import ink.sunrui.feiniutv.store.AccountStore
import ink.sunrui.feiniutv.ui.home.HomeViewModel

/**
 * 主页：顶部品牌 + 媒体库 Tab + 海报网格。
 *
 * 焦点策略：
 *   - 进入页面：先把焦点给 Tab 行（保证遥控器可见反馈）
 *   - Tab → 海报：按 DOWN 键自动下放，由系统 nextFocus 处理
 *   - 海报 → Tab：按 UP 键回到 Tab，系统 nextFocus 处理
 *   - Tab 之间切换不会立即触发数据刷新；只有 OK/CENTER 选中才刷新（参考 PosterAdapter 的解耦）
 */
class MainActivity : BaseVMActivity<ActivityMainBinding, HomeViewModel>() {

    private companion object {
        private const val TAG = "MainActivity"
        private const val POSTER_COLUMNS = 5
    }

    private lateinit var libraryAdapter: LibraryTabAdapter
    private lateinit var posterAdapter: PosterAdapter

    override fun initView() {
        setTheme(R.style.Theme_FeiniuTvLite)
        Log.d(TAG, "initView start")

        libraryAdapter = LibraryTabAdapter { library ->
            Log.d(TAG, "Library selected: ${library.name} guid=${library.guid}")
            binding.authStatusText.text = getString(R.string.status_auth_loading_items)
            mViewModel.fetchMediaItems(library.guid)
        }

        posterAdapter = PosterAdapter(
            tokenProvider = { mViewModel.getToken() }
        ) { item ->
            Log.d(TAG, "Opening detail: ${item.title}")
            val intent = Intent(this, DetailActivity::class.java).apply {
                putExtra(DetailActivity.EXTRA_ITEM_GUID, item.itemGuid)
                putExtra(DetailActivity.EXTRA_ITEM_TITLE, item.title)
            }
            startActivity(intent)
        }

        binding.libraryTabs.apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = libraryAdapter
            // 拦截 UP：Tab 行已经在顶端，避免焦点逃逸到状态条
            setKeyInterceptor { keyCode, _ ->
                keyCode == KeyEvent.KEYCODE_DPAD_UP
            }
        }

        binding.posterGrid.apply {
            // Leanback VerticalGridView 默认 1 列，必须显式设置
            setNumColumns(POSTER_COLUMNS)
            adapter = posterAdapter
            // 焦点 align 策略：让焦点行始终保持在 grid 可视区 50% 位置（垂直中线）
            // 默认 Leanback 把焦点行尽量往上贴，导致前一行被推出 grid 顶部裁掉
            // 改成"焦点居中"后，前一行至少有一半 poster 可见，滚动视觉更连贯
            // BOTH_EDGE 让边界行（第一行/最后一行）保持贴边，不强制居中
            windowAlignment = androidx.leanback.widget.BaseGridView.WINDOW_ALIGN_BOTH_EDGE
            windowAlignmentOffsetPercent = 50f
            isItemAlignmentOffsetWithPadding = true
            // 焦点逃逸：Leanback 1.0.0 默认锁住焦点不让从顶部出去（即使按 UP）。
            // 这里手动处理：UP 在第一行（selectedPosition < 列数）时把焦点送回 libraryTabs。
            setKeyInterceptor { keyCode, position ->
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP && position < POSTER_COLUMNS) {
                    binding.libraryTabs.requestFocus()
                    true
                } else {
                    false
                }
            }
        }

        Log.d(TAG, "initView done")
    }

    override fun initData() {
        Log.d(TAG, "start (token cached=${AccountStore.hasToken()})")
        binding.authStatusText.text = getString(R.string.status_auth_logging_in)
        mViewModel.start()
    }

    override fun initObserver() {
        mViewModel.logEvent.observe(this) { msg ->
            Log.d(TAG, msg)
        }

        mViewModel.loginStatus.observe(this) { success ->
            Log.d(TAG, "loginStatus=$success")
            if (success) {
                binding.authStatusText.text = getString(R.string.status_auth_loading_media)
            }
        }

        mViewModel.libraryList.observe(this) { libraries ->
            Log.d(TAG, "libraryList arrived size=${libraries.size}")
            if (libraries.isEmpty()) {
                showEmpty(getString(R.string.status_auth_no_library))
                libraryAdapter.submitList(emptyList())
                return@observe
            }
            binding.emptyHint.visibility = View.GONE
            binding.posterGrid.visibility = View.VISIBLE
            libraryAdapter.submitList(libraries)
            Log.d(TAG, "Auto-selecting first library: ${libraries[0].name}")
            libraryAdapter.setSelected(libraries[0].guid)
            mViewModel.fetchMediaItems(libraries[0].guid)
            binding.libraryTabs.post {
                binding.libraryTabs.requestFocus()
                binding.libraryTabs.selectedPosition = libraryAdapter.getSelectedPosition()
            }
        }

        mViewModel.mediaItemList.observe(this) { items ->
            Log.d(TAG, "mediaItemList arrived size=${items.size}")
            if (items.isEmpty()) {
                showEmpty(getString(R.string.status_auth_no_library))
            } else {
                binding.emptyHint.visibility = View.GONE
                binding.posterGrid.visibility = View.VISIBLE
            }
            binding.authStatusText.text = getString(R.string.status_auth_media_loaded, items.size)
            posterAdapter.submitList(items, 0)
        }

        mViewModel.errorMessage.observe(this) { error ->
            Log.w(TAG, "ERROR: $error")
            val short = shorten(error)
            binding.authStatusText.text = short
            showEmpty(short)
        }

        // token 失效 → 跳回登录页
        mViewModel.tokenExpired.observe(this) { expired ->
            if (expired == true) {
                Log.w(TAG, "Token expired, redirecting to LoginActivity")
                val intent = Intent(this, LoginActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                startActivity(intent)
                finish()
            }
        }
    }

    /** 把后端/异常长字符串裁短，避免顶部状态条/空态文案变成 5 行错误码 */
    private fun shorten(text: String): String {
        val firstLine = text.lineSequence().firstOrNull()?.trim().orEmpty()
        return if (firstLine.length > 80) firstLine.substring(0, 78) + "…" else firstLine
    }

    private fun showEmpty(text: String) {
        binding.emptyHint.text = text
        binding.emptyHint.visibility = View.VISIBLE
        binding.posterGrid.visibility = View.GONE
    }
}
