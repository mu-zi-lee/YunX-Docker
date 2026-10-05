package com.yunx.app.ui.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import kotlin.reflect.KClass
import com.yunx.app.data.db.ILanzouAccountEntity
import com.yunx.app.data.repository.ILanzouAccountRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 蓝奏云优享版账号 ViewModel：账号密码登录（换取 appToken 并落库），暴露登录态供主页/登录页/云盘页共享。
 */
class ILanzouAccountViewModel(
    private val repository: ILanzouAccountRepository
) : ViewModel() {

    val ilanzouAccount: StateFlow<ILanzouAccountEntity?> = repository.observeAccount()
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

    /** 账号密码登录；结果通过 [loginError] / [ilanzouAccount] 反馈。 */
    fun login(account: String, password: String) {
        viewModelScope.launch {
            loginError = null
            isLoggingIn = true
            try {
                repository.login(account, password).fold(
                    onSuccess = { },
                    onFailure = { loginError = it.message ?: "登录失败，请检查账号密码" }
                )
            } catch (e: Exception) {
                loginError = e.message ?: "登录失败，请检查账号密码"
            } finally {
                isLoggingIn = false
            }
        }
    }

    fun logout() {
        viewModelScope.launch { repository.logout() }
    }

    class Factory(
        private val repository: ILanzouAccountRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T {
            require(modelClass == ILanzouAccountViewModel::class)
            return ILanzouAccountViewModel(repository) as T
        }
    }
}
