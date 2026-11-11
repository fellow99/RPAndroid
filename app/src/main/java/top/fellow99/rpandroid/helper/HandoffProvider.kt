package top.fellow99.rpandroid.helper

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import top.fellow99.rpandroid.core.shell.MfaHandoff

/**
 * [HandoffProvider] —— App 进程内保存"待交接虚拟屏 Surface"的出口（007-webrtc）。
 *
 * 免 root 助手（shell UID 的独立 app_process）经 `content://top.fellow99.rpandroid.helper` 的
 * `call(takeSurface, nonce)` **跨 UID Binder 事务**拉取 App 进程 FrameHub 的 Surface，
 * 用以创建虚拟屏，使帧直接落在 App 进程。
 *
 * 安全（C-12）：只接受 shell 身份调用（UID 2000）；nonce 一次性防重放；该 Surface 仅为本机虚拟屏画面，
 * 不涉及任何账号/密钥。任何 App 无法伪装成 shell UID。
 */
class HandoffProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val result = Bundle()
        when (method) {
            MfaHandoff.METHOD_TAKE_SURFACE -> {
                val caller = Binder.getCallingUid()
                if (caller != MfaHandoff.EXPECTED_CALLER_UID) {
                    result.putString(MfaHandoff.KEY_RESULT, "${MfaHandoff.RESULT_BAD_UID}:$caller")
                    return result
                }
                val nonce = extras?.getString(MfaHandoff.KEY_NONCE)
                val s = MfaHandoff.take(nonce)
                if (s == null) {
                    result.putString(MfaHandoff.KEY_RESULT, MfaHandoff.RESULT_EMPTY)
                } else {
                    result.putString(MfaHandoff.KEY_RESULT, MfaHandoff.RESULT_OK)
                    result.putParcelable(MfaHandoff.KEY_SURFACE, s)
                }
            }
            else -> result.putString(MfaHandoff.KEY_RESULT, "unknown-method")
        }
        return result
    }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?
    ): Int = 0
}
