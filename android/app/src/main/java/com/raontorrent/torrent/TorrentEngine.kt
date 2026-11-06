package com.raontorrent.torrent

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Environment
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
      }
      AlertType.STORAGE_MOVED -> {
        val a = alert as org.libtorrent4j.alerts.StorageMovedAlert
        val hash = a.handle().infoHash().toHex()
        entry(hash)?.let { saveEntry(hash, it.put("loc", "final")) }
        Log.i(TAG, "moved $hash -> ${a.storagePath()}")
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
        .put("error", if (err.isError) err.message else moveFailed[hash]?.let { "폴더 이동 실패: $it" } ?: "")
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
        .put("activeSeconds", st.activeDuration())
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
