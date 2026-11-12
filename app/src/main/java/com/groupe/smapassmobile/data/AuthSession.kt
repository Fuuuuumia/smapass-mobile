package com.groupe.smapassmobile.data

import com.groupe.smapassmobile.data.remote.SmapassApi
import io.ktor.client.plugins.ResponseException
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** サーバー未登録、または認証情報が失効して使えない状態。 */
class NotRegisteredException(message: String = "この端末はサーバーに登録されていません") :
    IllegalStateException(message)

/**
 * アクセストークン（15分）の取得と更新を管理する。
 *
 * サーバーは使用済みリフレッシュトークンの再利用を検知すると、そのアプリ登録の認証情報を
 * すべて失効させる（docs/authentication.md）。そのため更新処理はプロセス全体で1つに直列化し、
 * 同時更新や同じリフレッシュトークンの自動再送をしない。
 */
class AuthSession(
    private val repository: DeviceRepository,
    private val api: SmapassApi,
) {
    /**
     * 有効なアクセストークンで [block] を実行する。401 のときだけ1回更新して再実行する。
     */
    suspend fun <T> withAccessToken(block: suspend (accessToken: String) -> T): T {
        val token = validAccessToken()
        return try {
            block(token)
        } catch (e: ResponseException) {
            if (e.response.status != HttpStatusCode.Unauthorized) throw e
            block(refreshAfterRejection(token))
        }
    }

    private suspend fun validAccessToken(): String = refreshMutex.withLock {
        val access = repository.accessTokenOnce()
        val expiresAt = repository.accessExpiresAtOnce()
        if (access != null && expiresAt != null &&
            System.currentTimeMillis() < expiresAt - EXPIRY_MARGIN_MS
        ) {
            return@withLock access
        }
        refreshLocked()
    }

    private suspend fun refreshAfterRejection(rejected: String): String = refreshMutex.withLock {
        // 待っている間に別の処理が更新済みなら、それを使う（二重更新しない）。
        val current = repository.accessTokenOnce()
        if (current != null && current != rejected) return@withLock current
        refreshLocked()
    }

    private suspend fun refreshLocked(): String {
        val refreshToken = repository.refreshTokenOnce() ?: throw NotRegisteredException()
        val tokens = try {
            api.refresh(refreshToken)
        } catch (e: ResponseException) {
            if (e.response.status == HttpStatusCode.Unauthorized) {
                // 期限切れ・失効・再利用検知。このトークンでは二度と更新できない。
                repository.clearTokens()
                throw NotRegisteredException("認証情報が失効しました。再登録が必要です")
            }
            throw e
        }
        repository.saveTokens(tokens)
        return tokens.accessToken
    }

    private companion object {
        /** 期限ぎりぎりのトークンを使わないための余裕。 */
        const val EXPIRY_MARGIN_MS = 30_000L

        /** 複数の画面・ViewModel から呼ばれても更新を1本にする。 */
        val refreshMutex = Mutex()
    }
}
