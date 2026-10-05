package com.yunx.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import kotlin.reflect.KClass
import com.yunx.app.data.db.LanzouAccountEntity
import com.yunx.app.data.repository.LanzouAccountRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 蓝奏云账号 ViewModel：原生账号密码登录（官网接口 + acw_sc__v2 人机校验）校验落库。
 */
class LanzouAccountViewModel(
    private val repository: LanzouAccountRepository
) : ViewModel() {

    val lanzouAccount: StateFlow<LanzouAccountEntity?> = repository.observeAccount()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null
        )

    /** 校验并保存 Cookie；返回是否成功（供手动粘贴 Cookie 的备用入口使用）。 */
    suspend fun saveCookie(cookie: String): Boolean = repository.saveCookie(cookie)

    /** 原生账号密码登录（官网接口 + 人机校验）；成功返回账号实体。 */
    suspend fun login(account: String, password: String): Result<LanzouAccountEntity> =
        repository.login(account, password)

    fun logout() {
        viewModelScope.launch { repository.logout() }
    }

    class Factory(
        private val repository: LanzouAccountRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T {
            require(modelClass == LanzouAccountViewModel::class)
            return LanzouAccountViewModel(repository) as T
        }
    }
}
