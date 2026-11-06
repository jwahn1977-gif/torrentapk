package com.raontorrent.torrent

import android.content.Context
import org.json.JSONObject
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.swig.settings_pack
import kotlin.random.Random

/**
 * 사용자 설정 (SharedPreferences에 JSON 1개로 저장) + libtorrent SettingsPack 변환.
 * uTorrent Android 설정 항목을 기준으로 구성.
 */
object TorrentSettings {
  private const val PREFS = "settings"
  private const val KEY = "json"

  /** 2026-11-06 기준 ngosang/trackerslist 'best' 목록 (응답 확인된 공개 트래커) */
  val PUBLIC_TRACKERS =
      listOf(
          "udp://tracker.opentrackr.org:1337/announce",
          "udp://open.stealth.si:80/announce",
          "udp://tracker.torrent.eu.org:451/announce",
          "udp://open.demonii.com:1337/announce",
          "udp://exodus.desync.com:6969/announce",
          "udp://open.tracker.cl:1337/announce",
          "udp://tracker.qu.ax:6969/announce",
          "udp://tracker.corpscorp.online:80/announce",
          "udp://tracker.bittor.pw:1337/announce",
          "udp://tracker.theoks.net:6969/announce",
          "udp://tracker-udp.gbitt.info:80/announce",
          "udp://explodie.org:6969/announce",
          "udp://tracker.ducks.party:1984/announce",
          "udp://tracker.tryhackx.org:6969/announce",
          "udp://tracker.nyaa.vc:6969/announce",
          "udp://tracker.dler.org:6969/announce",
          "http://tracker.dler.com:6969/announce",
          "udp://tracker2.dler.org:80/announce",
          "udp://tracker.gmi.gd:6969/announce",
          "udp://retracker01-msk-virt.corbina.net:80/announce",
      )

  private const val DHT_BOOTSTRAP =
      "dht.libtorrent.org:25401,router.bittorrent.com:6881,router.utorrent.com:6881," +
          "dht.transmissionbt.com:6881,router.silotis.us:6881,dht.aelitis.com:6881"

  private val DEFAULTS =
      JSONObject()
          .put("theme", "system") // system | light | dark
          .put("wifiOnly", false)
          .put("downLimitKB", 0) // 0 = 무제한
          .put("upLimitKB", 0)
          .put("activeDownloads", 0) // 0 = 제한 없음 (uTorrent 'Off')
          .put("port", 0) // 0 = 자동(무작위, 고정 저장)
          .put("maxConnections", 300)
          .put("encryption", "enabled") // enabled | forced | disabled
          .put("extraTrackers", true)
          .put("dht", true)
          .put("lsd", true)
          .put("upnp", true)
          .put("sequential", false)
          .put("downloadDir", "") // '' = 자동

  private lateinit var app: Context
  private val prefs by lazy { app.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

  fun init(ctx: Context) {
    if (!::app.isInitialized) app = ctx.applicationContext
  }

  @Synchronized
  fun get(): JSONObject {
    val saved =
        try {
          JSONObject(prefs.getString(KEY, "{}")!!)
        } catch (_: Exception) {
          JSONObject()
        }
    val out = JSONObject(DEFAULTS.toString())
    for (k in saved.keys()) out.put(k, saved.get(k))
    return out
  }

  /** 일부 키만 덮어쓰기 */
  @Synchronized
  fun merge(patch: JSONObject): JSONObject {
    val cur = get()
    for (k in patch.keys()) if (DEFAULTS.has(k)) cur.put(k, patch.get(k))
    prefs.edit().putString(KEY, cur.toString()).apply()
    return cur
  }

  /** port 설정이 0(자동)이면 처음 한 번 무작위 포트를 골라 계속 사용 (6881 대역 회피) */
  fun effectivePort(): Int {
    val p = get().optInt("port", 0)
    if (p in 1..65535) return p
    var auto = prefs.getInt("autoPort", 0)
    if (auto == 0) {
      auto = Random.nextInt(20000, 60000)
      prefs.edit().putInt("autoPort", auto).apply()
    }
    return auto
  }

  fun toSettingsPack(): SettingsPack {
    val s = get()
    val sp = SettingsPack()
    val port = effectivePort()
    sp.listenInterfaces("0.0.0.0:$port,[::]:$port")
    sp.setDhtBootstrapNodes(DHT_BOOTSTRAP)

    sp.downloadRateLimit(s.optInt("downLimitKB") * 1024)
    sp.uploadRateLimit(s.optInt("upLimitKB") * 1024)

    val active = s.optInt("activeDownloads")
    if (active > 0) {
      sp.activeDownloads(active)
      sp.activeSeeds(active)
      sp.activeLimit(active * 2)
    } else {
      sp.activeDownloads(-1)
      sp.activeSeeds(-1)
      sp.activeLimit(-1)
    }
    sp.connectionsLimit(s.optInt("maxConnections", 300))

    // 속도 핵심: 모든 트래커/모든 티어에 동시에 announce (uTorrent와 동일한 동작)
    bool(sp, settings_pack.bool_types.announce_to_all_trackers, true)
    bool(sp, settings_pack.bool_types.announce_to_all_tiers, true)
    // 느린 토렌트가 활성 슬롯을 잡아먹지 않도록
    bool(sp, settings_pack.bool_types.dont_count_slow_torrents, true)
    // 피어 탐색을 공격적으로
    int(sp, settings_pack.int_types.torrent_connect_boost, 60)
    int(sp, settings_pack.int_types.connection_speed, 50)
    int(sp, settings_pack.int_types.num_want, 200)
    int(sp, settings_pack.int_types.max_peerlist_size, 4000)

    sp.setEnableDht(s.optBoolean("dht", true))
    sp.setEnableLsd(s.optBoolean("lsd", true))
    bool(sp, settings_pack.bool_types.enable_upnp, s.optBoolean("upnp", true))
    bool(sp, settings_pack.bool_types.enable_natpmp, s.optBoolean("upnp", true))

    val policy =
        when (s.optString("encryption")) {
          "forced" -> settings_pack.enc_policy.pe_forced
          "disabled" -> settings_pack.enc_policy.pe_disabled
          else -> settings_pack.enc_policy.pe_enabled
        }
    int(sp, settings_pack.int_types.out_enc_policy, policy.swigValue())
    int(sp, settings_pack.int_types.in_enc_policy, policy.swigValue())
    int(sp, settings_pack.int_types.allowed_enc_level, settings_pack.enc_level.pe_both.swigValue())
    return sp
  }

  private fun bool(sp: SettingsPack, k: settings_pack.bool_types, v: Boolean) {
    sp.setBoolean(k.swigValue(), v)
  }

  private fun int(sp: SettingsPack, k: settings_pack.int_types, v: Int) {
    sp.setInteger(k.swigValue(), v)
  }
}
