package com.raontorrent.torrent

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.facebook.react.bridge.BaseActivityEventListener
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.raontorrent.specs.NativeTorrentSpec
import java.util.concurrent.Executors

/** JS ↔ TorrentEngine 브리지 (TurboModule, specs/NativeTorrent.ts 참고) */
class TorrentModule(private val ctx: ReactApplicationContext) : NativeTorrentSpec(ctx) {

  private val io = Executors.newSingleThreadExecutor()
  private var pickPromise: Promise? = null
  private var folderPromise: Promise? = null

  private val activityListener =
      object : BaseActivityEventListener() {
        override fun onActivityResult(
            activity: Activity,
            requestCode: Int,
            resultCode: Int,
            data: Intent?,
        ) {
          val uri = data?.data
          val ok = resultCode == Activity.RESULT_OK && uri != null
          when (requestCode) {
            REQ_PICK -> {
              val p = pickPromise ?: return
              pickPromise = null
              p.resolve(if (ok) uri.toString() else "")
            }
            REQ_FOLDER -> {
              val p = folderPromise ?: return
              folderPromise = null
              p.resolve(if (ok) treeUriToPath(uri!!) ?: "" else "")
            }
          }
        }
      }

  init {
    ctx.addActivityEventListener(activityListener)
  }

  private fun bg(promise: Promise, block: () -> Any?) {
    io.execute {
      try {
        val r = block()
        promise.resolve(if (r is Unit) null else r)
      } catch (t: Throwable) {
        promise.reject("E_TORRENT", t.message ?: t.javaClass.simpleName, t)
      }
    }
  }

  override fun start(promise: Promise) =
      bg(promise) {
        TorrentEngine.start(ctx)
        if (TorrentEngine.hasResumable()) TorrentService.refresh(ctx)
      }

  override fun getTorrents(promise: Promise) = bg(promise) { TorrentEngine.listJson() }

  override fun getInfo(promise: Promise) = bg(promise) { TorrentEngine.infoJson() }

  override fun addFromUri(uri: String, promise: Promise) =
      bg(promise) {
        TorrentEngine.start(ctx)
        val name = TorrentEngine.add(uri)
        TorrentService.refresh(ctx)
        name
      }

  override fun pickTorrentFile(promise: Promise) {
    val activity = ctx.currentActivity
    if (activity == null) {
      promise.reject("E_NO_ACTIVITY", "화면이 활성화되어 있지 않습니다")
      return
    }
    pickPromise?.resolve("")
    pickPromise = promise
    val intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
          addCategory(Intent.CATEGORY_OPENABLE)
          // 기기마다 .torrent MIME이 달라서 전체 허용 후 엔진에서 검증
          type = "*/*"
          putExtra(
              Intent.EXTRA_MIME_TYPES,
              arrayOf("application/x-bittorrent", "application/octet-stream", "*/*"))
        }
    try {
      activity.startActivityForResult(intent, REQ_PICK)
    } catch (e: ActivityNotFoundException) {
      pickPromise = null
      promise.reject("E_PICKER", "파일 선택기를 열 수 없습니다")
    }
  }

  override fun pause(hash: String, promise: Promise) = bg(promise) { TorrentEngine.pause(hash) }

  override fun resume(hash: String, promise: Promise) =
      bg(promise) {
        TorrentEngine.resume(hash)
        TorrentService.refresh(ctx)
      }

  override fun remove(hash: String, deleteFiles: Boolean, promise: Promise) =
      bg(promise) { TorrentEngine.remove(hash, deleteFiles) }

  override fun exportToPublic(hash: String, promise: Promise) =
      bg(promise) { TorrentEngine.exportToPublic(hash) }

  override fun openFile(hash: String, promise: Promise) {
    try {
      val exported = TorrentEngine.exportedMainUri(hash)
      if (exported.isNotEmpty()) {
        val uri = Uri.parse(exported)
        val mime = ctx.contentResolver.getType(uri) ?: "*/*"
        val view =
            Intent(Intent.ACTION_VIEW).apply {
              setDataAndType(uri, mime)
              addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        val chooser =
            Intent.createChooser(view, "열기").apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        (ctx.currentActivity ?: ctx).startActivity(chooser)
        promise.resolve(null)
        return
      }
      val file =
          TorrentEngine.mainFile(hash) ?: throw IllegalStateException("아직 파일 정보가 없습니다")
      if (!file.exists()) throw IllegalStateException("파일이 아직 없습니다")
      val uri: Uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
      val ext = file.extension.lowercase()
      val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
      val view =
          Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
          }
      val chooser =
          Intent.createChooser(view, "열기").apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
      (ctx.currentActivity ?: ctx).startActivity(chooser)
      promise.resolve(null)
    } catch (t: Throwable) {
      promise.reject("E_OPEN", t.message ?: "열 수 없습니다", t)
    }
  }

  override fun getSettings(promise: Promise) =
      bg(promise) {
        TorrentEngine.start(ctx)
        TorrentEngine.settingsJson()
      }

  override fun setSettings(patchJson: String, promise: Promise) =
      bg(promise) {
        TorrentEngine.start(ctx)
        TorrentEngine.updateSettings(patchJson)
      }

  override fun pickDownloadFolder(promise: Promise) {
    val activity = ctx.currentActivity
    if (activity == null) {
      promise.reject("E_NO_ACTIVITY", "화면이 활성화되어 있지 않습니다")
      return
    }
    folderPromise?.resolve("")
    folderPromise = promise
    try {
      activity.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_FOLDER)
    } catch (e: ActivityNotFoundException) {
      folderPromise = null
      promise.reject("E_PICKER", "폴더 선택기를 열 수 없습니다")
    }
  }

  /** content://…/tree/primary%3ADownload%2FTV → /storage/emulated/0/Download/TV */
  private fun treeUriToPath(uri: Uri): String? {
    val docId =
        try {
          android.provider.DocumentsContract.getTreeDocumentId(uri)
        } catch (_: Exception) {
          return null
        }
    val parts = docId.split(":", limit = 2)
    val volume = parts[0]
    val rel = if (parts.size > 1) parts[1] else ""
    val root =
        if (volume.equals("primary", ignoreCase = true))
            android.os.Environment.getExternalStorageDirectory().absolutePath
        else "/storage/$volume"
    return if (rel.isEmpty()) root else "$root/$rel"
  }

  override fun requestAllFilesAccess() {
    val activity = ctx.currentActivity ?: return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      try {
        activity.startActivity(
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${ctx.packageName}")))
      } catch (_: Exception) {
        activity.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
      }
    }
  }

  override fun invalidate() {
    ctx.removeActivityEventListener(activityListener)
    io.shutdown()
    super.invalidate()
  }

  companion object {
    const val NAME = NativeTorrentSpec.NAME
    private const val REQ_PICK = 4711
    private const val REQ_FOLDER = 4712
  }
}
