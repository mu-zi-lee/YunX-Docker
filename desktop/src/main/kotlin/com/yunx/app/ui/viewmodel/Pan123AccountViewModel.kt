package com.yunx.app.ui.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import kotlin.reflect.KClass
import com.yunx.app.data.db.Pan123AccountEntity
import com.yunx.app.data.repository.Pan123AccountRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 123 云盘账号 ViewModel：账号+密码登录 → JWT 落库，暴露登录态供主页/登录页/解析页共享。
 */
class Pan123AccountViewModel(
    private val repository: Pan123AccountRepository
) : ViewModel() {

    val pan123Account: StateFlow<Pan123AccountEntity?> = repository.observeAccount()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null
        )

    /** 登录错误信息（登录页 Snackbar 提示） */
    var loginError by mutableStateOf<String?>(null)
        private set

    /** 登录中（按钮 loading） */
    var isLoggingIn by mutableStateOf(false)
        private set

    fun consumeLoginError() {
        loginError = null
    }

    /** 账号密码登录；成功返回 true（错误文案由仓库映射，绝不复述服务端原文） */
    fun login(account: String, password: String) {
        viewModelScope.launch {
            loginError = null
            isLoggingIn = true
            try {
                val message = repository.loginWithPassword(account, password)
                if (message != null) loginError = message
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 登录接口自身已把网络错误映射成文案，这里是最后一道兜底（写库等非网络异常）
                loginError = "登录失败，请稍后重试"
            } finally {
                isLoggingIn = false
            }
        }
    }

    fun logout() {
        viewModelScope.launch { repository.logout() }
    }

    class Factory(
        private val repository: Pan123AccountRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T {
            require(modelClass == Pan123AccountViewModel::class)
            return Pan123AccountViewModel(repository) as T
        }
    }
}