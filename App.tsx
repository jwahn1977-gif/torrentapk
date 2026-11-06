/**
 * RaonTorrent — React Native + libtorrent4j 토렌트 다운로더
 */
import React, {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import {
  Alert,
  AppState,
  FlatList,
  Linking,
  PermissionsAndroid,
  Platform,
  Pressable,
  StatusBar,
  StyleSheet,
  Text,
  TextInput,
  ToastAndroid,
  useColorScheme,
  View,
} from 'react-native';
import {SafeAreaProvider, useSafeAreaInsets} from 'react-native-safe-area-context';
import Torrent from './specs/NativeTorrent';

type TState =
  | 'metadata'
  | 'checking'
  | 'downloading'
  | 'seeding'
  | 'completed'
  | 'paused'
  | 'queued'
  | 'error';

interface TorrentItem {
  hash: string;
  name: string;
  state: TState;
  error: string;
  progress: number;
  totalSize: number;
  doneSize: number;
  downRate: number;
  upRate: number;
  peers: number;
  seeds: number;
  eta: number;
  hasMetadata: boolean;
  savePath: string;
  added: number;
}

interface EngineInfo {
  started: boolean;
  saveDir: string;
  publicStorage: boolean;
  sdk: number;
  dhtNodes: number;
  downRate: number;
  upRate: number;
  libtorrent: string;
}

const STATE_LABEL: Record<TState, string> = {
  metadata: '메타데이터 받는 중',
  checking: '파일 확인 중',
  downloading: '다운로드 중',
  seeding: '완료 · 시딩',
  completed: '완료',
  paused: '일시정지',
  queued: '대기',
  error: '오류',
};

function fmtSize(b: number): string {
  if (b <= 0) return '0 B';
  const u = ['B', 'KB', 'MB', 'GB', 'TB'];
  const i = Math.min(Math.floor(Math.log(b) / Math.log(1024)), u.length - 1);
  return `${(b / Math.pow(1024, i)).toFixed(i >= 2 ? 2 : 0)} ${u[i]}`;
}
const fmtRate = (b: number) => `${fmtSize(b)}/s`;
function fmtEta(s: number): string {
  if (s < 0) return '—';
  if (s < 60) return `${s}초`;
  if (s < 3600) return `${Math.floor(s / 60)}분 ${s % 60}초`;
  const h = Math.floor(s / 3600);
  if (h < 48) return `${h}시간 ${Math.floor((s % 3600) / 60)}분`;
  return `${Math.floor(h / 24)}일+`;
}

function toast(msg: string) {
  if (Platform.OS === 'android') ToastAndroid.show(msg, ToastAndroid.SHORT);
}

// ------------------------------------------------------------------ theme
function usePalette() {
  const dark = useColorScheme() === 'dark';
  return useMemo(
    () =>
      dark
        ? {
            dark,
            bg: '#0f1412',
            card: '#18201c',
            text: '#e8efe9',
            sub: '#95a39a',
            line: '#26302b',
            accent: '#7cc35b',
            accentText: '#0f1412',
            track: '#2a352f',
            danger: '#ff7a6e',
            input: '#121916',
          }
        : {
            dark,
            bg: '#f4f6f3',
            card: '#ffffff',
            text: '#16201a',
            sub: '#5f6d64',
            line: '#e2e7e2',
            accent: '#4f9a2f',
            accentText: '#ffffff',
            track: '#e6ece5',
            danger: '#c8382c',
            input: '#f8faf7',
          },
    [dark],
  );
}
type Palette = ReturnType<typeof usePalette>;

// ------------------------------------------------------------------ app
export default function App() {
  const p = usePalette();
  return (
    <SafeAreaProvider>
      <StatusBar barStyle={p.dark ? 'light-content' : 'dark-content'} />
      <Main />
    </SafeAreaProvider>
  );
}

function Main() {
  const p = usePalette();
  const insets = useSafeAreaInsets();
  const [items, setItems] = useState<TorrentItem[]>([]);
  const [info, setInfo] = useState<EngineInfo | null>(null);
  const [magnet, setMagnet] = useState('');
  const [busy, setBusy] = useState(false);
  const [showSettings, setShowSettings] = useState(false);
  const ready = useRef(false);
  const pendingUrls = useRef<string[]>([]);

  const refresh = useCallback(async () => {
    try {
      const [list, inf] = await Promise.all([
        Torrent.getTorrents(),
        Torrent.getInfo(),
      ]);
      const parsed: TorrentItem[] = JSON.parse(list);
      parsed.sort((a, b) => b.added - a.added);
      setItems(parsed);
      setInfo(JSON.parse(inf));
    } catch {
      // 엔진 시작 전에는 무시
    }
  }, []);

  const addUri = useCallback(
    async (uri: string) => {
      const u = uri.trim();
      if (!u) return;
      if (!ready.current) {
        pendingUrls.current.push(u);
        return;
      }
      setBusy(true);
      try {
        const name = await Torrent.addFromUri(u);
        toast(`추가됨: ${name}`);
        setMagnet('');
        refresh();
      } catch (e: any) {
        Alert.alert('추가 실패', e?.message ?? String(e));
      } finally {
        setBusy(false);
      }
    },
    [refresh],
  );

  // 엔진 시작 + 권한 + 외부에서 연 링크/파일 처리
  useEffect(() => {
    let alive = true;
    (async () => {
      if (Platform.OS === 'android' && Number(Platform.Version) >= 33) {
        await PermissionsAndroid.request(
          'android.permission.POST_NOTIFICATIONS' as any,
        ).catch(() => null);
      }
      try {
        await Torrent.start();
      } catch (e: any) {
        Alert.alert('엔진 시작 실패', e?.message ?? String(e));
        return;
      }
      if (!alive) return;
      ready.current = true;
      const initial = await Linking.getInitialURL();
      if (initial) pendingUrls.current.push(initial);
      const queued = pendingUrls.current.splice(0);
      for (const u of queued) await addUri(u);
      refresh();
    })();
    const sub = Linking.addEventListener('url', ({url}) => addUri(url));
    return () => {
      alive = false;
      sub.remove();
    };
  }, [addUri, refresh]);

  // 화면이 켜져 있을 때 1초마다 상태 갱신
  useEffect(() => {
    let timer: ReturnType<typeof setInterval> | null = null;
    const startPoll = () => {
      if (!timer) timer = setInterval(refresh, 1000);
    };
    const stopPoll = () => {
      if (timer) clearInterval(timer);
      timer = null;
    };
    startPoll();
    const sub = AppState.addEventListener('change', s =>
      s === 'active' ? (refresh(), startPoll()) : stopPoll(),
    );
    return () => {
      stopPoll();
      sub.remove();
    };
  }, [refresh]);

  const pickFile = async () => {
    try {
      const uri = await Torrent.pickTorrentFile();
      if (uri) await addUri(uri);
    } catch (e: any) {
      Alert.alert('파일 선택 실패', e?.message ?? String(e));
    }
  };

  const pasteMagnet = () => addUri(magnet);

  const askStorage = async () => {
    if (!info) return;
    if (info.sdk >= 30) {
      Torrent.requestAllFilesAccess();
    } else {
      await PermissionsAndroid.request(
        PermissionsAndroid.PERMISSIONS.WRITE_EXTERNAL_STORAGE,
      );
      refresh();
    }
  };

  const totalDown = items.reduce((s, t) => s + t.downRate, 0);
  const totalUp = items.reduce((s, t) => s + t.upRate, 0);
  const s = styles(p);

  return (
    <View style={[s.root, {paddingTop: insets.top}]}>
      {/* header */}
      <View style={s.header}>
        <View style={{flex: 1}}>
          <Text style={s.title}>RaonTorrent</Text>
          <Text style={s.sub}>
            ↓ {fmtRate(totalDown)}   ↑ {fmtRate(totalUp)}
            {info ? `   ·   DHT ${info.dhtNodes}` : ''}
          </Text>
        </View>
        <Pressable
          onPress={() => setShowSettings(v => !v)}
          style={({pressed}) => [s.iconBtn, pressed && {opacity: 0.6}]}
          accessibilityLabel="설정">
          <Text style={s.iconBtnText}>{showSettings ? '닫기' : '설정'}</Text>
        </Pressable>
      </View>

      {showSettings && info && (
        <View style={s.settings}>
          <Text style={s.label}>저장 위치</Text>
          <Text style={s.mono} selectable>
            {info.saveDir}
          </Text>
          {!info.publicStorage && (
            <>
              <Text style={[s.sub, {marginTop: 8}]}>
                지금은 앱 전용 폴더에 저장됩니다. 파일 관리자에서 바로 보려면 공용
                Download/RaonTorrent 폴더 저장을 허용하세요. (이후 추가하는 토렌트부터
                적용)
              </Text>
              <Pressable style={[s.btn, {marginTop: 10}]} onPress={askStorage}>
                <Text style={s.btnText}>공용 Download 폴더 사용 허용</Text>
              </Pressable>
            </>
          )}
          <Text style={[s.sub, {marginTop: 10}]}>
            엔진: libtorrent {info.libtorrent} · Android API {info.sdk}
          </Text>
        </View>
      )}

      {/* add bar */}
      <View style={s.addBar}>
        <TextInput
          style={s.input}
          value={magnet}
          onChangeText={setMagnet}
          placeholder="magnet:?xt=urn:btih:… 붙여넣기"
          placeholderTextColor={p.sub}
          autoCapitalize="none"
          autoCorrect={false}
          onSubmitEditing={pasteMagnet}
          returnKeyType="go"
        />
        <Pressable
          style={[s.btn, (!magnet || busy) && {opacity: 0.5}]}
          disabled={!magnet || busy}
          onPress={pasteMagnet}>
          <Text style={s.btnText}>추가</Text>
        </Pressable>
      </View>
      <Pressable
        style={({pressed}) => [s.fileBtn, pressed && {opacity: 0.7}]}
        onPress={pickFile}
        disabled={busy}>
        <Text style={s.fileBtnText}>＋ .torrent 파일 열기</Text>
      </Pressable>

      <FlatList
        data={items}
        keyExtractor={t => t.hash}
        contentContainerStyle={{padding: 16, paddingBottom: insets.bottom + 24}}
        ItemSeparatorComponent={Separator}
        ListEmptyComponent={
          <View style={s.empty}>
            <Text style={s.emptyTitle}>다운로드가 없습니다</Text>
            <Text style={s.sub}>
              .torrent 파일을 열거나 마그넷 링크를 붙여넣으세요.{'\n'}파일 관리자나
              브라우저에서 .torrent / magnet 링크를 눌러도 이 앱으로 열립니다.
            </Text>
          </View>
        }
        renderItem={({item}) => <TorrentCard t={item} p={p} onChanged={refresh} />}
      />
    </View>
  );
}

const Separator = () => <View style={{height: 12}} />;

// ------------------------------------------------------------------ card
function TorrentCard({
  t,
  p,
  onChanged,
}: {
  t: TorrentItem;
  p: Palette;
  onChanged: () => void;
}) {
  const s = styles(p);
  const pct = Math.max(0, Math.min(1, t.progress));
  const isPaused = t.state === 'paused' || t.state === 'completed';
  const done = pct >= 1 || t.state === 'seeding' || t.state === 'completed';
  const barColor =
    t.state === 'error' ? p.danger : isPaused ? p.sub : p.accent;

  const run = async (fn: () => Promise<unknown>) => {
    try {
      await fn();
    } catch (e: any) {
      Alert.alert('오류', e?.message ?? String(e));
    }
    onChanged();
  };

  const confirmRemove = () =>
    Alert.alert('삭제', t.name, [
      {text: '취소', style: 'cancel'},
      {text: '목록에서만', onPress: () => run(() => Torrent.remove(t.hash, false))},
      {
        text: '파일까지 삭제',
        style: 'destructive',
        onPress: () => run(() => Torrent.remove(t.hash, true)),
      },
    ]);

  return (
    <View style={s.card}>
      <Text style={s.name} numberOfLines={2}>
        {t.name}
      </Text>
      <View style={s.row}>
        <Text style={[s.badge, t.state === 'error' && {color: p.danger}]}>
          {STATE_LABEL[t.state] ?? t.state}
        </Text>
        <Text style={s.pct}>{(pct * 100).toFixed(1)}%</Text>
      </View>
      <View style={s.track}>
        <View style={[s.bar, {width: `${pct * 100}%`, backgroundColor: barColor}]} />
      </View>
      <Text style={s.meta}>
        {t.hasMetadata
          ? `${fmtSize(t.doneSize)} / ${fmtSize(t.totalSize)}`
          : '파일 정보를 받는 중…'}
        {!isPaused && !done ? `  ·  남은 시간 ${fmtEta(t.eta)}` : ''}
      </Text>
      {!isPaused && (
        <Text style={s.meta}>
          ↓ {fmtRate(t.downRate)}  ↑ {fmtRate(t.upRate)}  ·  피어 {t.peers} (시드{' '}
          {t.seeds})
        </Text>
      )}
      {!!t.error && <Text style={[s.meta, {color: p.danger}]}>{t.error}</Text>}

      <View style={[s.row, {marginTop: 10, gap: 8, justifyContent: 'flex-end'}]}>
        {t.hasMetadata && pct > 0 && (
          <Pressable style={s.smallBtn} onPress={() => run(() => Torrent.openFile(t.hash))}>
            <Text style={s.smallBtnText}>열기</Text>
          </Pressable>
        )}
        <Pressable
          style={s.smallBtn}
          onPress={() =>
            run(() => (isPaused ? Torrent.resume(t.hash) : Torrent.pause(t.hash)))
          }>
          <Text style={s.smallBtnText}>{isPaused ? '재개' : '일시정지'}</Text>
        </Pressable>
        <Pressable style={s.smallBtn} onPress={confirmRemove}>
          <Text style={[s.smallBtnText, {color: p.danger}]}>삭제</Text>
        </Pressable>
      </View>
    </View>
  );
}

// ------------------------------------------------------------------ styles
const styles = (p: Palette) =>
  StyleSheet.create({
    root: {flex: 1, backgroundColor: p.bg},
    header: {
      flexDirection: 'row',
      alignItems: 'center',
      paddingHorizontal: 16,
      paddingVertical: 12,
    },
    title: {fontSize: 22, fontWeight: '700', color: p.text},
    sub: {fontSize: 13, color: p.sub, lineHeight: 19},
    iconBtn: {
      paddingHorizontal: 14,
      paddingVertical: 8,
      borderRadius: 18,
      borderWidth: 1,
      borderColor: p.line,
      backgroundColor: p.card,
    },
    iconBtnText: {color: p.text, fontSize: 14, fontWeight: '600'},
    settings: {
      marginHorizontal: 16,
      marginBottom: 12,
      padding: 14,
      borderRadius: 14,
      backgroundColor: p.card,
      borderWidth: 1,
      borderColor: p.line,
    },
    label: {fontSize: 12, color: p.sub, marginBottom: 4, fontWeight: '600'},
    mono: {fontSize: 13, color: p.text, fontFamily: 'monospace'},
    addBar: {flexDirection: 'row', gap: 8, paddingHorizontal: 16},
    input: {
      flex: 1,
      height: 46,
      borderRadius: 12,
      paddingHorizontal: 14,
      backgroundColor: p.input,
      borderWidth: 1,
      borderColor: p.line,
      color: p.text,
      fontSize: 14,
    },
    btn: {
      height: 46,
      paddingHorizontal: 18,
      borderRadius: 12,
      alignItems: 'center',
      justifyContent: 'center',
      backgroundColor: p.accent,
    },
    btnText: {color: p.accentText, fontWeight: '700', fontSize: 15},
    fileBtn: {
      marginHorizontal: 16,
      marginTop: 10,
      height: 48,
      borderRadius: 12,
      borderWidth: 1.5,
      borderColor: p.accent,
      alignItems: 'center',
      justifyContent: 'center',
    },
    fileBtnText: {color: p.accent, fontWeight: '700', fontSize: 15},
    empty: {alignItems: 'center', paddingTop: 60, paddingHorizontal: 24, gap: 8},
    emptyTitle: {fontSize: 17, fontWeight: '700', color: p.text},
    card: {
      padding: 14,
      borderRadius: 14,
      backgroundColor: p.card,
      borderWidth: 1,
      borderColor: p.line,
    },
    name: {fontSize: 15, fontWeight: '600', color: p.text, marginBottom: 8},
    row: {flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between'},
    badge: {fontSize: 13, fontWeight: '600', color: p.accent},
    pct: {fontSize: 13, fontWeight: '600', color: p.text, fontVariant: ['tabular-nums']},
    track: {
      height: 8,
      borderRadius: 4,
      backgroundColor: p.track,
      overflow: 'hidden',
      marginTop: 6,
      marginBottom: 8,
    },
    bar: {height: 8, borderRadius: 4},
    meta: {fontSize: 12.5, color: p.sub, lineHeight: 19, fontVariant: ['tabular-nums']},
    smallBtn: {
      paddingHorizontal: 14,
      paddingVertical: 8,
      borderRadius: 10,
      backgroundColor: p.track,
    },
    smallBtnText: {fontSize: 13.5, fontWeight: '600', color: p.text},
  });
