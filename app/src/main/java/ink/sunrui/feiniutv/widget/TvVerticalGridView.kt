package ink.sunrui.feiniutv.widget

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import androidx.leanback.widget.VerticalGridView

class TvVerticalGridView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : VerticalGridView(context, attrs, defStyleAttr) {

    private var keyInterceptor: ((Int, Int) -> Boolean)? = null

    fun setKeyInterceptor(interceptor: (keyCode: Int, position: Int) -> Boolean) {
        this.keyInterceptor = interceptor
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            keyInterceptor?.let { interceptor ->
                if (interceptor(event.keyCode, selectedPosition)) {
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
