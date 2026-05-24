package ink.sunrui.feiniutv.base

import android.os.Bundle
import android.view.LayoutInflater
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.viewbinding.ViewBinding
import java.lang.reflect.ParameterizedType

abstract class BaseVMActivity<VB : ViewBinding, VM : ViewModel> : AppCompatActivity() {

    lateinit var binding: VB
    lateinit var mViewModel: VM

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val type = javaClass.genericSuperclass as ParameterizedType
        
        val vbClass = type.actualTypeArguments[0] as Class<VB>
        val method = vbClass.getMethod("inflate", LayoutInflater::class.java)
        binding = method.invoke(null, layoutInflater) as VB
        setContentView(binding.root)

        val vmClass = type.actualTypeArguments[1] as Class<VM>
        mViewModel = ViewModelProvider(this)[vmClass]

        initView()
        initObserver()
        initData()
    }

    abstract fun initView()

    abstract fun initData()

    abstract fun initObserver()
}
