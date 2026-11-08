package com.raontorrent.torrent

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.libtorrent4j.AlertListener
import org.libtorrent4j.AnnounceEntry
import org.libtorrent4j.MoveFlags
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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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

    TorrentSettings.init(app)
    val sp = TorrentSettings.toSettingsPack()

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
    val params = SessionParams(sp)
    // mmap 디스크 I/O는 Android 저장소(FUSE)에서 느리거나 시스템 저장소 서비스를 크래시시킴
    // (에뮬레이터에서 재현) → 단순 read/write 방식 사용
    params.setPosixDiskIO()
    session.start(params)
    isStarted = true
    restore()
    trashIo.execute { purgeExpiredTrash() }
    watchNetwork()
    startStatsLog()
  }

  // ---------------------------------------------------------------- settings

  fun settingsJson(): String {
    TorrentSettings.init(app)
    return TorrentSettings.get().put("effectivePort", TorrentSettings.effectivePort()).toString()
  }

  /** 설정 일부 변경 → 즉시 세션에 반영 */
  fun updateSettings(patchJson: String): String {
    TorrentSettings.init(app)
    val before = TorrentSettings.get()
    val after = TorrentSettings.merge(JSONObject(patchJson))
    if (isStarted) {
      session.applySettings(TorrentSettings.toSettingsPack())
      if (!before.optBoolean("extraTrackers") && after.optBoolean("extraTrackers")) {
        handles().forEach { addExtraTrackers(it) }
      }
      if (before.optBoolean("sequential") != after.optBoolean("sequential")) {
        handles().forEach { applySequential(it, after.optBoolean("sequential")) }
      }
      evaluateNetwork()
    }
    return settingsJson()
  }

  private fun addExtraTrackers(th: TorrentHandle) {
    // 비공개 토렌트에는 외부 트래커를 붙이지 않는다
    val ti = th.torrentFile()
    if (ti != null && ti.isPrivate) return
    val existing = th.trackers().map { it.url() }.toHashSet()
    var tier = existing.size
    for (url in TorrentSettings.PUBLIC_TRACKERS) {
      if (url in existing) continue
      val e = AnnounceEntry(url)
      e.tier((tier++).toShort())
      th.addTracker(e)
    }
  }

  private fun applySequential(th: TorrentHandle, on: Boolean) {
    if (on) th.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
    else th.unsetFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
  }

  // ---------------------------------------------------------------- Wi-Fi 전용

  @Volatile
  var waitingForWifi = false
    private set

  private fun watchNetwork() {
    try {
      val cm = app.getSystemService(ConnectivityManager::class.java)
      cm.registerDefaultNetworkCallback(
          object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = evaluateNetwork()

            override fun onLost(network: Network) = evaluateNetwork()

            override fun onCapabilitiesChanged(n: Network, c: NetworkCapabilities) =
                evaluateNetwork()
          })
    } catch (t: Throwable) {
      Log.w(TAG, "network callback failed", t)
    }
    evaluateNetwork()
  }

  private fun evaluateNetwork() {
    if (!isStarted) return
    val wifiOnly = TorrentSettings.get().optBoolean("wifiOnly")
    val metered =
        try {
          app.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered
        } catch (_: Throwable) {
          false
        }
    val shouldPause = wifiOnly && metered
    if (shouldPause && !session.isPaused) session.pause()
    if (!shouldPause && session.isPaused) session.resume()
    waitingForWifi = shouldPause
  }

  // ---------------------------------------------------------------- 진단 로그

  private fun startStatsLog() {
    Executors.newSingleThreadScheduledExecutor()
        .scheduleWithFixedDelay(
            {
              try {
                val sb = StringBuilder()
                sb.append("dht=").append(session.dhtNodes())
                sb.append(" down=").append(session.downloadRate())
                sb.append(" up=").append(session.uploadRate())
                sb.append(" port=").append(TorrentSettings.effectivePort())
                sb.append(" listen=").append(session.listenEndpoints())
                for (th in handles()) {
                  val st = th.status(true)
                  sb.append(" | ").append(th.infoHash().toHex().take(8))
                  sb.append(" peers=").append(st.numPeers())
                  sb.append(" seeds=").append(st.numSeeds())
                  sb.append(" known=").append(st.listPeers())
                  sb.append(" rate=").append(st.downloadPayloadRate())
                  sb.append(" done=").append(st.totalWantedDone())
                  sb.append("/").append(st.totalWanted())
                }
                Log.i("RaonTorrentStats", sb.toString())
              } catch (t: Throwable) {
                Log.w(TAG, "stats log failed", t)
              }
            },
            5,
            5,
            TimeUnit.SECONDS)
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
        val cfg = TorrentSettings.get()
        if (cfg.optBoolean("extraTrackers", true)) addExtraTrackers(th)
        if (cfg.optBoolean("sequential")) applySequential(th, true)
        if (entry?.optBoolean("paused") == true) {
          th.unsetFlags(TorrentFlags.AUTO_MANAGED)
          th.pause()
        } else {
          th.setFlags(TorrentFlags.AUTO_MANAGED)
          th.resume()
        }
      }
      AlertType.TORRENT_FINISHED -> {
        val th = (alert as org.libtorrent4j.alerts.TorrentFinishedAlert).handle()
        moveToFinal(th)
        maybeAutoExport(th)
      }
      AlertType.STORAGE_MOVED -> {
        val a = alert as org.libtorrent4j.alerts.StorageMovedAlert
        val hash = a.handle().infoHash().toHex()
        entry(hash)?.let { saveEntry(hash, it.put("loc", "final").put("save", a.storagePath())) }
        Log.i(TAG, "moved $hash -> ${a.storagePath()}")
        maybeAutoExport(a.handle())
      }
      AlertType.STORAGE_MOVED_FAILED -> {
        val a = alert as org.libtorrent4j.alerts.StorageMovedFailedAlert
        Log.w(TAG, "move failed: ${a.error().message} ${a.filePath()}")
        moveFailed[a.handle().infoHash().toHex()] = a.error().message
      }
      AlertType.METADATA_RECEIVED -> {
        val th = (alert as org.libtorrent4j.alerts.MetadataReceivedAlert).handle()
        if (TorrentSettings.get().optBoolean("extraTrackers", true)) addExtraTrackers(th)
      }
      else -> Unit
    }
  }

  // ---------------------------------------------------------------- storage

  /** 저장 위치: 권한이 있으면 공용 Download/RaonTorrent, 없으면 앱 전용 폴더 */
  fun saveDir(): File {
    val custom = TorrentSettings.get().optString("downloadDir")
    if (custom.isNotEmpty() && canWritePublic()) {
      val d = File(custom)
      d.mkdirs()
      if (d.canWrite()) return d
    }
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

  /**
   * 받는 중인 파일은 앱 내부 저장소(FUSE를 거치지 않아 빠르고 안정적)에 두고,
   * 완료되면 사용자 폴더로 한 번에 이동한다. (qBittorrent '미완료 파일 별도 보관'과 같은 방식)
   */
  private fun incompleteDir(): File = File(app.filesDir, "incomplete").apply { mkdirs() }

  private val moveFailed = java.util.concurrent.ConcurrentHashMap<String, String>()

  private fun moveToFinal(th: TorrentHandle) {
    val hash = th.infoHash().toHex()
    val e = entry(hash) ?: return
    if (e.optString("loc", "final") == "final") return
    val target = File(e.optString("save", saveDir().absolutePath))
    target.mkdirs()
    if (th.savePath() == target.absolutePath) {
      saveEntry(hash, e.put("loc", "final"))
      return
    }
    Log.i(TAG, "moving $hash to $target")
    th.moveStorage(target.absolutePath, MoveFlags.ALWAYS_REPLACE_FILES)
  }

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
        if (e.optString("type") == "exported") continue
        val dir =
            if (e.optString("loc", "final") == "incomplete") incompleteDir()
            else File(e.optString("save", saveDir().absolutePath)).apply { mkdirs() }
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
            .put("loc", "incomplete")
            .put("paused", false)
            .put("added", System.currentTimeMillis()))
    session.download(ti, incompleteDir())
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
            .put("loc", "incomplete")
            .put("paused", false)
            .put("added", System.currentTimeMillis()))
    session.download(magnet, incompleteDir(), torrent_flags_t())
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

  /** deleteFiles = true 이면 파일을 바로 지우지 않고 휴지통으로 옮긴다 (30일 후 자동 삭제). */
  fun remove(hash: String, deleteFiles: Boolean) {
    if (deleteFiles) {
      moveToTrash(hash)
      return
    }
    find(hash)?.let { session.remove(it) }
    entry(hash)?.let { e ->
      if (e.optString("type") == "file") File(e.optString("value")).delete()
    }
    prefs.edit().remove(hash).apply()
  }

  // ---------------------------------------------------------------- trash

  private const val TRASH_DAYS = 30L
  private const val TRASH_DIR = ".raontrash"
  private val trash by lazy { app.getSharedPreferences("trash", Context.MODE_PRIVATE) }
  private val trashIo = Executors.newSingleThreadExecutor()

  private fun trashEntry(hash: String): JSONObject? =
      trash.getString(hash, null)?.let {
        try {
          JSONObject(it)
        } catch (_: Exception) {
          null
        }
      }

  private fun <T> onTrashIo(block: () -> T): T =
      try {
        trashIo.submit(java.util.concurrent.Callable { block() }).get()
      } catch (e: java.util.concurrent.ExecutionException) {
        throw e.cause ?: e
      }

  /** 휴지통에 넣은 MediaStore 항목도 찾도록 하는 옵션 (Android 11+) */
  @androidx.annotation.RequiresApi(30)
  private fun matchTrashed(): Bundle =
      Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE) }

  @androidx.annotation.RequiresApi(30)
  private fun setTrashed(uri: Uri, trashed: Boolean) {
    val v = ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, if (trashed) 1 else 0) }
    app.contentResolver.update(uri, v, matchTrashed())
  }

  private fun deleteUris(uris: JSONArray?) {
    for (i in 0 until (uris?.length() ?: 0)) {
      try {
        val uri = Uri.parse(uris!!.getString(i))
        if (Build.VERSION.SDK_INT >= 30) app.contentResolver.delete(uri, matchTrashed())
        else app.contentResolver.delete(uri, null, null)
      } catch (t: Throwable) {
        Log.w(TAG, "delete uri failed", t)
      }
    }
  }

  private fun moveToTrash(hash: String) {
    val e = entry(hash)
    val now = System.currentTimeMillis()

    // Download 폴더로 내보낸 항목 → MediaStore 휴지통 (Android 11+)
    if (e?.optString("type") == "exported") {
      val uris = e.optJSONArray("uris") ?: JSONArray()
      if (Build.VERSION.SDK_INT >= 30) {
        for (i in 0 until uris.length()) setTrashed(Uri.parse(uris.getString(i)), true)
        trash
            .edit()
            .putString(
                hash,
                JSONObject()
                    .put("kind", "media")
                    .put("name", e.optString("name"))
                    .put("size", e.optLong("size"))
                    .put("deleted", now)
                    .put("entry", e)
                    .toString())
            .apply()
      } else {
        deleteUris(uris) // Android 10 이하는 시스템 휴지통이 없음
      }
      prefs.edit().remove(hash).apply()
      return
    }

    val th = find(hash)
    val ti = th?.torrentFile()
    if (th == null || ti == null || e == null) {
      // 아직 받은 데이터가 없음 → 그냥 정리
      th?.let { session.remove(it, SessionHandle.DELETE_FILES) }
      e?.takeIf { it.optString("type") == "file" }?.let { File(it.optString("value")).delete() }
      prefs.edit().remove(hash).apply()
      return
    }

    val savePath = th.savePath()
    val fs = ti.files()
    val names = LinkedHashSet<String>()
    var size = 0L
    for (i in 0 until fs.numFiles()) {
      if (fs.padFileAt(i)) continue
      names.add(fs.filePath(i).substringBefore('/'))
      size += fs.fileSize(i)
    }
    val root = File(savePath, TRASH_DIR).apply { mkdirs() }
    try {
      File(root, ".nomedia").createNewFile()
    } catch (_: Throwable) {}
    val dir = File(root, hash).apply { mkdirs() }
    File(dir, "meta.torrent").writeBytes(ti.bencode())
    trash
        .edit()
        .putString(
            hash,
            JSONObject()
                .put("kind", "files")
                .put("name", ti.name())
                .put("size", size)
                .put("deleted", now)
                .put("entry", e)
                .put("savePath", savePath)
                .put("dir", dir.absolutePath)
                .put("names", JSONArray(names.toList()))
                .toString())
        .apply()

    th.pause()
    session.remove(th)
    if (e.optString("type") == "file") File(e.optString("value")).delete()
    prefs.edit().remove(hash).apply()

    trashIo.execute {
      Thread.sleep(1500) // libtorrent가 파일을 닫을 시간
      for (n in names) {
        val src = File(savePath, n)
        if (src.exists() && !src.renameTo(File(dir, n))) Log.w(TAG, "trash move failed: $src")
      }
      File(savePath, ".$hash.parts").renameTo(File(dir, ".$hash.parts"))
      Log.i(TAG, "trashed $hash")
    }
  }

  fun trashJson(): String {
    val arr = JSONArray()
    if (!::app.isInitialized) return arr.toString()
    val day = TimeUnit.DAYS.toMillis(1)
    for ((hash, raw) in trash.all) {
      try {
        val t = JSONObject(raw as String)
        val deleted = t.optLong("deleted")
        arr.put(
            JSONObject()
                .put("hash", hash)
                .put("name", t.optString("name"))
                .put("size", t.optLong("size"))
                .put("deleted", deleted)
                .put("expires", deleted + TRASH_DAYS * day))
      } catch (_: Exception) {}
    }
    return arr.toString()
  }

  /** 휴지통에서 되살리기 → 이름 반환 */
  fun restoreFromTrash(hash: String): String =
      onTrashIo {
            val t = trashEntry(hash) ?: throw IllegalStateException("휴지통에 없는 항목입니다")
            if (find(hash) != null || entry(hash) != null)
                throw IllegalStateException("이미 목록에 있는 토렌트입니다")
            val e = t.getJSONObject("entry")
            if (t.optString("kind") == "media") {
              if (Build.VERSION.SDK_INT < 30) throw IllegalStateException("이 기기에서는 되살릴 수 없습니다")
              val uris = e.optJSONArray("uris") ?: JSONArray()
              for (i in 0 until uris.length()) setTrashed(Uri.parse(uris.getString(i)), false)
              saveEntry(hash, e)
            } else {
              val dir = File(t.getString("dir"))
              val save = File(t.getString("savePath")).apply { mkdirs() }
              val names = t.getJSONArray("names")
              for (i in 0 until names.length()) {
                val n = names.getString(i)
                if (File(dir, n).exists() && File(save, n).exists())
                    throw IllegalStateException("같은 이름의 파일이 이미 있습니다: $n")
              }
              for (i in 0 until names.length()) {
                val n = names.getString(i)
                val src = File(dir, n)
                if (src.exists() && !src.renameTo(File(save, n)))
                    throw IllegalStateException("되살리기 실패: $n")
              }
              File(dir, ".$hash.parts").renameTo(File(save, ".$hash.parts"))
              val meta = File(torrentFileDir(), "$hash.torrent")
              File(dir, "meta.torrent").copyTo(meta, overwrite = true)
              val loc =
                  if (save.absolutePath == incompleteDir().absolutePath) "incomplete" else "final"
              saveEntry(
                  hash,
                  JSONObject()
                      .put("type", "file")
                      .put("value", meta.absolutePath)
                      .put("name", t.optString("name"))
                      .put("save", e.optString("save", saveDir().absolutePath))
                      .put("loc", loc)
                      .put("paused", false)
                      .put("added", e.optLong("added", System.currentTimeMillis())))
              session.download(TorrentInfo(meta), save)
              dir.deleteRecursively()
            }
            trash.edit().remove(hash).apply()
            t.optString("name")
          }

  /** 휴지통에서 영구 삭제 ('' = 전부 비우기) */
  fun deleteFromTrash(hash: String) {
    onTrashIo {
      val keys = if (hash.isEmpty()) trash.all.keys.toList() else listOf(hash)
      keys.forEach { purgeTrash(it) }
    }
  }

  private fun purgeTrash(hash: String) {
    val t = trashEntry(hash)
    if (t != null) {
      if (t.optString("kind") == "media") deleteUris(t.optJSONObject("entry")?.optJSONArray("uris"))
      else File(t.optString("dir")).takeIf { it.path.isNotEmpty() }?.deleteRecursively()
    }
    trash.edit().remove(hash).apply()
  }

  private fun purgeExpiredTrash() {
    val limit = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(TRASH_DAYS)
    for ((hash, raw) in trash.all) {
      try {
        if (JSONObject(raw as String).optLong("deleted") < limit) purgeTrash(hash)
      } catch (t: Throwable) {
        Log.w(TAG, "purge failed for $hash", t)
      }
    }
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

  // ---------------------------------------------------------------- 공용 Download 폴더로 내보내기

  private val exporting = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
  private val exportFailed = java.util.concurrent.ConcurrentHashMap<String, String>()
  private val exportIo = Executors.newSingleThreadExecutor()

  /** 앱 전용 폴더(Android/data, 내부 저장소)에 있는지 = 파일 앱에서 안 보이는지 */
  private fun isPrivatePath(path: String): Boolean =
      path.contains("/Android/data/") || path.startsWith(app.filesDir.absolutePath)

  /** 완료 + 앱 전용 폴더에 저장됨 + 자동 이동 켜짐 → 공용 Download/RaonTorrent로 자동 이동 */
  private fun maybeAutoExport(th: TorrentHandle) {
    if (!TorrentSettings.get().optBoolean("autoExport", true)) return
    val hash = th.infoHash().toHex()
    val e = entry(hash) ?: return
    if (e.optString("loc", "final") != "final") return // 아직 내부 폴더 → 이동 후 STORAGE_MOVED에서 다시 호출
    if (!isPrivatePath(th.savePath())) return
    if (exportFailed.containsKey(hash)) return // 실패한 건 자동 재시도하지 않음 (버튼으로 다시)
    if (!th.status().isFinished) return
    exportIo.execute {
      try {
        exportToPublic(hash)
      } catch (t: Throwable) {
        Log.w(TAG, "auto export failed", t)
      }
    }
  }

  /**
   * 완료된 토렌트 파일을 공용 Download/RaonTorrent 로 옮긴다.
   * - '모든 파일 접근' 권한이 있으면 libtorrent가 직접 이동 (시딩 계속)
   * - 없으면 MediaStore로 복사(Android 10+, 권한 불필요) 후 원본 삭제, 목록엔 '완료' 기록으로 남김
   * 반환: 사용자에게 보여줄 경로
   */
  fun exportToPublic(hash: String): String {
    val th = find(hash) ?: throw IllegalStateException("목록에 없는 토렌트입니다")
    val st = th.status()
    if (!st.isFinished) throw IllegalStateException("다운로드가 끝난 뒤에 옮길 수 있습니다")
    val ti = th.torrentFile() ?: throw IllegalStateException("아직 파일 정보가 없습니다")
    val e = entry(hash) ?: throw IllegalStateException("목록 정보가 없습니다")
    if (!exporting.add(hash)) throw IllegalStateException("이미 옮기는 중입니다")
    try {
      exportFailed.remove(hash)
      val base =
          File(
              Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
              "RaonTorrent")

      if (canWritePublic()) {
        base.mkdirs()
        saveEntry(hash, e.put("save", base.absolutePath))
        th.moveStorage(base.absolutePath, MoveFlags.ALWAYS_REPLACE_FILES)
        return "Download/RaonTorrent"
      }
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        throw IllegalStateException("이 Android 버전은 설정 → 저장 → 저장소 권한 허용이 필요합니다")
      }

      val src = th.savePath()
      val fs = ti.files()
      val uris = org.json.JSONArray()
      var mainUri = ""
      var mainSize = -1L
      var total = 0L
      val resolver = app.contentResolver
      val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
      try {
        for (i in 0 until fs.numFiles()) {
          if (fs.padFileAt(i)) continue
          val rel = fs.filePath(i) // 예: "폴더/sub/a.mkv" 또는 "a.mkv"
          val f = File(src, rel)
          if (!f.exists()) throw IllegalStateException("파일이 없습니다: $rel")
          val parent = File(rel).parent
          val relDir =
              Environment.DIRECTORY_DOWNLOADS + "/RaonTorrent" + (if (parent != null) "/$parent" else "")
          val mime =
              MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase())
                  ?: "application/octet-stream"
          val values =
              ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relDir)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
              }
          val uri =
              resolver.insert(collection, values)
                  ?: throw IllegalStateException("Download 폴더에 파일을 만들 수 없습니다")
          uris.put(uri.toString())
          resolver.openOutputStream(uri)?.use { out ->
            f.inputStream().use { it.copyTo(out, 1 shl 20) }
          } ?: throw IllegalStateException("Download 폴더에 쓸 수 없습니다")
          resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
          val size = f.length()
          total += size
          if (size > mainSize) {
            mainSize = size
            mainUri = uri.toString()
          }
        }
      } catch (t: Throwable) {
        // 반쯤 복사된 파일 정리
        for (i in 0 until uris.length()) {
          try {
            resolver.delete(Uri.parse(uris.getString(i)), null, null)
          } catch (_: Throwable) {}
        }
        throw t
      }

      val topDir = if (fs.numFiles() > 1 || fs.filePath(0).contains('/')) "/" + ti.name() else ""
      val shown = "Download/RaonTorrent$topDir"
      // 원본은 지우고, 목록에는 '완료' 기록으로 남긴다 (열기/삭제 가능)
      saveEntry(
          hash,
          JSONObject()
              .put("type", "exported")
              .put("name", ti.name())
              .put("path", shown)
              .put("uris", uris)
              .put("main", mainUri)
              .put("size", total)
              .put("paused", true)
              .put("added", e.optLong("added")))
      session.remove(th, SessionHandle.DELETE_FILES)
      e.optString("value").takeIf { e.optString("type") == "file" }?.let { File(it).delete() }
      Log.i(TAG, "exported $hash -> $shown")
      return shown
    } catch (t: Throwable) {
      exportFailed[hash] = t.message ?: t.javaClass.simpleName
      throw t
    } finally {
      exporting.remove(hash)
    }
  }

  /** 내보낸 항목의 대표 파일 content:// 주소 ('' = 내보낸 항목 아님) */
  fun exportedMainUri(hash: String): String {
    val e = entry(hash) ?: return ""
    return if (e.optString("type") == "exported") e.optString("main") else ""
  }

  private fun exportedJson(): List<JSONObject> =
      prefs.all.mapNotNull { (hash, raw) ->
        try {
          val e = JSONObject(raw as String)
          if (e.optString("type") != "exported") return@mapNotNull null
          val size = e.optLong("size")
          JSONObject()
              .put("hash", hash)
              .put("name", e.optString("name"))
              .put("state", "exported")
              .put("error", "")
              .put("location", "final")
              .put("isPublic", true)
              .put("progress", 1.0)
              .put("totalSize", size)
              .put("doneSize", size)
              .put("downRate", 0)
              .put("upRate", 0)
              .put("peers", 0)
              .put("seeds", 0)
              .put("eta", -1)
              .put("hasMetadata", true)
              .put("knownPeers", 0)
              .put("connectCandidates", 0)
              .put("trackers", 0)
              .put("trackersFailed", 0)
              .put("activeSeconds", 0)
              .put("savePath", e.optString("path"))
              .put("added", e.optLong("added"))
        } catch (_: Exception) {
          null
        }
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
        // 내보내기 직후 세션에서 빠지기 전까지 중복 표시 방지
        if (entry(th.infoHash().toHex())?.optString("type") == "exported") continue
        arr.put(statusJson(th))
      } catch (t: Throwable) {
        Log.w(TAG, "status failed", t)
      }
    }
    if (::app.isInitialized) exportedJson().forEach { arr.put(it) }
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
    var trackers = 0
    var trackersFailed = 0
    try {
      for (t in th.trackers()) {
        trackers++
        val eps = t.endpoints()
        if (eps.isNotEmpty() && eps.all { ep -> ep.infohashV1().fails() > 0 }) trackersFailed++
      }
    } catch (_: Throwable) {}

    val state =
        when {
          hash in exporting -> "exporting"
          err.isError -> "error"
          st.isMovingStorage -> "moving"
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
        .put(
            "error",
            if (err.isError) err.message
            else
                moveFailed[hash]?.let { "폴더 이동 실패: $it" }
                    ?: exportFailed[hash]?.let { "Download로 옮기기 실패: $it" }
                    ?: "")
        .put("isPublic", !isPrivatePath(th.savePath()))
        .put("location", if (e?.optString("loc", "final") == "incomplete") "incomplete" else "final")
        .put("progress", st.progress().toDouble())
        .put("totalSize", wanted)
        .put("doneSize", done)
        .put("downRate", rate)
        .put("upRate", st.uploadPayloadRate())
        .put("peers", st.numPeers())
        .put("seeds", st.numSeeds())
        .put("eta", eta)
        .put("hasMetadata", st.hasMetadata())
        .put("knownPeers", st.listPeers())
        .put("connectCandidates", st.connectCandidates())
        .put("trackers", trackers)
        .put("trackersFailed", trackersFailed)
        // libtorrent4j activeDuration()은 밀리초 단위 (에뮬레이터 실측으로 확인)
        .put("activeSeconds", st.activeDuration() / 1000)
        .put("savePath", e?.optString("save") ?: "")
        .put("added", e?.optLong("added") ?: 0L)
  }

  fun infoJson(): String =
      JSONObject()
          .put("started", isStarted)
          .put("waitingForWifi", waitingForWifi)
          .put("port", if (::app.isInitialized) TorrentSettings.effectivePort() else 0)
          .put("saveDir", if (::app.isInitialized) saveDir().absolutePath else "")
          .put("publicStorage", ::app.isInitialized && canWritePublic())
          .put("sdk", Build.VERSION.SDK_INT)
          .put("dhtNodes", if (isStarted) session.dhtNodes() else 0)
          .put("downRate", if (isStarted) session.downloadRate() else 0)
          .put("upRate", if (isStarted) session.uploadRate() else 0)
          .put("libtorrent", org.libtorrent4j.LibTorrent.version())
          .toString()
}
