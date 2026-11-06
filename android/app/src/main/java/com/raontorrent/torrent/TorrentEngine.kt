package com.raontorrent.torrent

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.libtorrent4j.AlertListener
import org.libtorrent4j.SessionHandle
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.TorrentStatus
import org.libtorrent4j.alerts.AddTorrentAlert
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.swig.error_code
import org.libtorrent4j.swig.libtorrent
import org.libtorrent4j.swig.torrent_flags_t
import java.io.File

/**
 * libtorrent4j(=libtorrent C++) 세션을 앱 프로세스 단위로 하나만 유지하는 엔진.
 * 추가한 토렌트 목록은 SharedPreferences에 저장했다가 앱 재시작 시 복원한다.
 */
object TorrentEngine {
  private const val TAG = "RaonTorrent"
  private const val PREFS = "torrents"

  private lateinit var app: Context
  private val session = SessionManager(false)

  @Volatile
  var isStarted = false
    private set

  private val prefs by lazy { app.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

  // ---------------------------------------------------------------- lifecycle

  @Synchronized
  fun start(ctx: Context) {
    if (isStarted) return
    app = ctx.applicationContext

    val sp = SettingsPack()
    sp.setEnableDht(true)
    sp.setEnableLsd(true)
    sp.listenInterfaces("0.0.0.0:6881,[::]:6881")

    session.addListener(
        object : AlertListener {
          override fun types(): IntArray? = null

          override fun alert(alert: Alert<*>) {
            try {
              onAlert(alert)
            } catch (t: Throwable) {
              Log.w(TAG, "alert handling failed", t)
            }
          }
        })
    session.start(SessionParams(sp))
    isStarted = true
    restore()
  }

  private fun onAlert(alert: Alert<*>) {
    when (alert.type()) {
      AlertType.ADD_TORRENT -> {
        val a = alert as AddTorrentAlert
        if (a.error().isError) {
          Log.w(TAG, "add failed: ${a.error().message}")
          return
        }
        val th = a.handle()
        val hash = th.infoHash().toHex()
        val entry = entry(hash)
        if (entry?.optBoolean("paused") == true) {
          th.unsetFlags(TorrentFlags.AUTO_MANAGED)
          th.pause()
        } else {
          th.setFlags(TorrentFlags.AUTO_MANAGED)
          th.resume()
        }
      }
      else -> Unit
    }
  }

  // ---------------------------------------------------------------- storage

  /** 저장 위치: 권한이 있으면 공용 Download/RaonTorrent, 없으면 앱 전용 폴더 */
  fun saveDir(): File {
    val publicDir =
        File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "RaonTorrent")
    if (canWritePublic()) {
      publicDir.mkdirs()
      if (publicDir.canWrite()) return publicDir
    }
    val dir =
        File(app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: app.filesDir, "RaonTorrent")
    dir.mkdirs()
    return dir
  }

  fun canWritePublic(): Boolean =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
      } else {
        app.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
      }

  private fun torrentFileDir(): File = File(app.filesDir, "torrents").apply { mkdirs() }

  private fun entry(hash: String): JSONObject? =
      prefs.getString(hash, null)?.let {
        try {
          JSONObject(it)
        } catch (_: Exception) {
          null
        }
      }

  private fun saveEntry(hash: String, obj: JSONObject) {
    prefs.edit().putString(hash, obj.toString()).apply()
  }

  private fun setPausedFlag(hash: String, paused: Boolean) {
    val e = entry(hash) ?: return
    e.put("paused", paused)
    saveEntry(hash, e)
  }

  /** 저장된 목록 중 일시정지가 아닌 항목이 있는지 */
  fun hasResumable(): Boolean =
      prefs.all.values.any {
        try {
          !JSONObject(it as String).optBoolean("paused")
        } catch (_: Exception) {
          false
        }
      }

  private fun restore() {
    for ((hash, raw) in prefs.all) {
      try {
        val e = JSONObject(raw as String)
        val dir = File(e.optString("save", saveDir().absolutePath)).apply { mkdirs() }
        when (e.getString("type")) {
          "file" -> {
            val f = File(e.getString("value"))
            if (f.exists()) session.download(TorrentInfo(f), dir)
          }
          "magnet" -> session.download(e.getString("value"), dir, torrent_flags_t())
        }
      } catch (t: Throwable) {
        Log.w(TAG, "restore failed for $hash", t)
      }
    }
  }

  // ---------------------------------------------------------------- actions

  /** magnet:, content://, file:// 를 받아 토렌트를 추가하고 표시 이름을 돌려준다. */
  fun add(uriString: String): String {
    check(isStarted) { "엔진이 아직 시작되지 않았습니다" }
    val trimmed = uriString.trim()
    return if (trimmed.startsWith("magnet:", ignoreCase = true)) addMagnet(trimmed)
    else addTorrentBytes(readBytes(Uri.parse(trimmed)))
  }

  private fun readBytes(uri: Uri): ByteArray {
    val bytes =
        when (uri.scheme) {
          "content" -> app.contentResolver.openInputStream(uri)?.use { it.readBytes() }
          "file",
          null -> File(uri.path ?: "").takeIf { it.exists() }?.readBytes()
          else -> throw IllegalArgumentException("지원하지 않는 주소입니다: ${uri.scheme}")
        } ?: throw IllegalArgumentException("파일을 읽을 수 없습니다")
    require(bytes.size < 20 * 1024 * 1024) { ".torrent 파일이 너무 큽니다" }
    return bytes
  }

  private fun addTorrentBytes(bytes: ByteArray): String {
    val ti =
        try {
          TorrentInfo(bytes)
        } catch (t: Throwable) {
          throw IllegalArgumentException("올바른 .torrent 파일이 아닙니다")
        }
    val hash = ti.infoHash().toHex()
    if (find(hash) != null) throw IllegalStateException("이미 목록에 있는 토렌트입니다")

    val file = File(torrentFileDir(), "$hash.torrent")
    file.writeBytes(bytes)
    val dir = saveDir()
    saveEntry(
        hash,
        JSONObject()
            .put("type", "file")
            .put("value", file.absolutePath)
            .put("name", ti.name())
            .put("save", dir.absolutePath)
            .put("paused", false)
            .put("added", System.currentTimeMillis()))
    session.download(ti, dir)
    return ti.name()
  }

  private fun addMagnet(magnet: String): String {
    val ec = error_code()
    val p = libtorrent.parse_magnet_uri(magnet, ec)
    if (ec.value() != 0) throw IllegalArgumentException("마그넷 링크 형식이 잘못되었습니다")
    val hash = Sha1Hash(p.getInfo_hashes().get_best()).toHex()
    if (find(hash) != null) throw IllegalStateException("이미 목록에 있는 토렌트입니다")

    val name = p.getName().ifEmpty { hash }
    val dir = saveDir()
    saveEntry(
        hash,
        JSONObject()
            .put("type", "magnet")
            .put("value", magnet)
            .put("name", name)
            .put("save", dir.absolutePath)
            .put("paused", false)
            .put("added", System.currentTimeMillis()))
    session.download(magnet, dir, torrent_flags_t())
    return name
  }

  fun find(hash: String): TorrentHandle? {
    val th = session.find(Sha1Hash.parseHex(hash)) ?: return null
    return if (th.isValid) th else null
  }

  fun pause(hash: String) {
    val th = find(hash) ?: return
    th.unsetFlags(TorrentFlags.AUTO_MANAGED)
    th.pause()
    setPausedFlag(hash, true)
  }

  fun resume(hash: String) {
    val th = find(hash) ?: return
    th.setFlags(TorrentFlags.AUTO_MANAGED)
    th.resume()
    setPausedFlag(hash, false)
  }

  fun remove(hash: String, deleteFiles: Boolean) {
    find(hash)?.let { th ->
      if (deleteFiles) session.remove(th, SessionHandle.DELETE_FILES) else session.remove(th)
    }
    entry(hash)?.let { e ->
      if (e.optString("type") == "file") File(e.optString("value")).delete()
    }
    prefs.edit().remove(hash).apply()
  }

  /** 가장 큰 파일(동영상 등)의 실제 경로 */
  fun mainFile(hash: String): File? {
    val th = find(hash) ?: return null
    val ti = th.torrentFile() ?: return null
    val fs = ti.files()
    var best = -1
    var bestSize = -1L
    for (i in 0 until fs.numFiles()) {
      if (fs.padFileAt(i)) continue
      val s = fs.fileSize(i)
      if (s > bestSize) {
        best = i
        bestSize = s
      }
    }
    if (best < 0) return null
    return File(fs.filePath(best, th.savePath()))
  }

  // ---------------------------------------------------------------- status

  private fun handles(): List<TorrentHandle> =
      if (!isStarted) emptyList()
      else SessionHandle(session.swig()).torrents().filter { it.isValid }

  data class Summary(val active: Int, val downloading: Int, val downRate: Long, val upRate: Long)

  fun summary(): Summary {
    var active = 0
    var downloading = 0
    for (th in handles()) {
      val st = th.status()
      if (isUserPaused(st)) continue
      active++
      if (!st.isFinished) downloading++
    }
    return Summary(
        active,
        downloading,
        if (isStarted) session.downloadRate() else 0,
        if (isStarted) session.uploadRate() else 0)
  }

  /** 사용자가 직접 일시정지한 상태 (자동 관리 대기열의 일시정지는 제외) */
  private fun isUserPaused(st: TorrentStatus): Boolean {
    val f = st.flags()
    return f.and_(TorrentFlags.PAUSED).non_zero() && !f.and_(TorrentFlags.AUTO_MANAGED).non_zero()
  }

  fun listJson(): String {
    val arr = JSONArray()
    for (th in handles()) {
      try {
        arr.put(statusJson(th))
      } catch (t: Throwable) {
        Log.w(TAG, "status failed", t)
      }
    }
    return arr.toString()
  }

  private fun statusJson(th: TorrentHandle): JSONObject {
    val st = th.status()
    val hash = th.infoHash().toHex()
    val e = entry(hash)
    val paused = isUserPaused(st)
    val queued = !paused && st.flags().and_(TorrentFlags.PAUSED).non_zero()
    val err = st.errorCode()
    val wanted = st.totalWanted()
    val done = st.totalWantedDone()
    val rate = st.downloadPayloadRate()
    val eta = if (rate > 0 && wanted > done) (wanted - done) / rate else -1L
    val name = st.name().ifEmpty { e?.optString("name") ?: hash }

    val state =
        when {
          err.isError -> "error"
          paused && st.isFinished -> "completed"
          paused -> "paused"
          queued -> "queued"
          else ->
              when (st.state()) {
                TorrentStatus.State.DOWNLOADING_METADATA -> "metadata"
                TorrentStatus.State.CHECKING_FILES,
                TorrentStatus.State.CHECKING_RESUME_DATA -> "checking"
                TorrentStatus.State.DOWNLOADING -> "downloading"
                TorrentStatus.State.FINISHED,
                TorrentStatus.State.SEEDING -> "seeding"
                else -> "queued"
              }
        }

    return JSONObject()
        .put("hash", hash)
        .put("name", name)
        .put("state", state)
        .put("error", if (err.isError) err.message else "")
        .put("progress", st.progress().toDouble())
        .put("totalSize", wanted)
        .put("doneSize", done)
        .put("downRate", rate)
        .put("upRate", st.uploadPayloadRate())
        .put("peers", st.numPeers())
        .put("seeds", st.numSeeds())
        .put("eta", eta)
        .put("hasMetadata", st.hasMetadata())
        .put("savePath", e?.optString("save") ?: "")
        .put("added", e?.optLong("added") ?: 0L)
  }

  fun infoJson(): String =
      JSONObject()
          .put("started", isStarted)
          .put("saveDir", if (::app.isInitialized) saveDir().absolutePath else "")
          .put("publicStorage", ::app.isInitialized && canWritePublic())
          .put("sdk", Build.VERSION.SDK_INT)
          .put("dhtNodes", if (isStarted) session.dhtNodes() else 0)
          .put("downRate", if (isStarted) session.downloadRate() else 0)
          .put("upRate", if (isStarted) session.uploadRate() else 0)
          .put("libtorrent", org.libtorrent4j.LibTorrent.version())
          .toString()
}
