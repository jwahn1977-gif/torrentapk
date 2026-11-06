/**
 * 설정 화면 — uTorrent Android 설정 구성을 기준으로 함
 */
import React, {useState} from 'react';
import {
  Modal,
  PermissionsAndroid,
  Pressable,
  ScrollView,
  StyleSheet,
  Switch,
  Text,
  TextInput,
  View,
} from 'react-native';
import {useSafeAreaInsets} from 'react-native-safe-area-context';
import Torrent from '../specs/NativeTorrent';
import {Palette, ThemeMode, usePalette} from './theme';

export interface AppSettings {
  theme: ThemeMode;
  wifiOnly: boolean;
  downLimitKB: number;
  upLimitKB: number;
  activeDownloads: number;
  port: number;
  effectivePort: number;
  maxConnections: number;
  encryption: 'enabled' | 'forced' | 'disabled';
  extraTrackers: boolean;
  dht: boolean;
  lsd: boolean;
  upnp: boolean;
  sequential: boolean;
  downloadDir: string;
}

interface Props {
  settings: AppSettings;
  onChange: (patch: Partial<AppSettings>) => Promise<void>;
  onClose: () => void;
  saveDir: string;
  publicStorage: boolean;
  sdk: number;
  libtorrent: string;
  onStorageChanged: () => void;
}

type Prompt = {
  title: string;
  hint: string;
  key: keyof AppSettings;
  presets: {label: string; value: number}[];
};

const ENC_LABEL = {enabled: '허용 (권장)', forced: '강제', disabled: '사용 안 함'};

export default function SettingsScreen(props: Props) {
  const {settings: st, onChange} = props;
  const p = usePalette();
  const s = styles(p);
  const insets = useSafeAreaInsets();
  const [prompt, setPrompt] = useState<Prompt | null>(null);
  const [encOpen, setEncOpen] = useState(false);

  const limitLabel = (kb: number) => (kb > 0 ? `${kb} KB/s` : '무제한');

  const chooseFolder = async () => {
    if (!props.publicStorage) {
      await askStorage();
      return;
    }
    const path = await Torrent.pickDownloadFolder();
    if (path) await onChange({downloadDir: path});
  };

  const askStorage = async () => {
    if (props.sdk >= 30) {
      Torrent.requestAllFilesAccess();
    } else {
      await PermissionsAndroid.request(
        PermissionsAndroid.PERMISSIONS.WRITE_EXTERNAL_STORAGE,
      );
      props.onStorageChanged();
    }
  };

  return (
    <View style={[s.root, {paddingTop: insets.top}]}>
      <View style={s.topBar}>
        <Pressable onPress={props.onClose} hitSlop={12} style={s.back}>
          <Text style={s.backText}>‹</Text>
        </Pressable>
        <Text style={s.topTitle}>설정</Text>
        <View style={s.back} />
      </View>

      <ScrollView contentContainerStyle={{paddingBottom: insets.bottom + 32}}>
        <Section title="테마" p={p}>
          {(['light', 'dark', 'system'] as ThemeMode[]).map(m => (
            <Row
              key={m}
              p={p}
              label={m === 'light' ? '라이트' : m === 'dark' ? '다크' : '시스템 설정 따르기 (권장)'}
              onPress={() => onChange({theme: m})}
              right={st.theme === m ? <Text style={s.check}>✓</Text> : null}
            />
          ))}
        </Section>

        <Section title="대역폭" p={p}>
          <Row
            p={p}
            label="Wi-Fi에서만 다운로드"
            sub="모바일 데이터(데이터 요금 네트워크)에서는 자동 일시정지"
            right={
              <Switch
                value={st.wifiOnly}
                onValueChange={v => onChange({wifiOnly: v})}
                trackColor={{true: p.accent}}
              />
            }
          />
          <Row
            p={p}
            label="다운로드 속도 제한"
            value={limitLabel(st.downLimitKB)}
            onPress={() =>
              setPrompt({
                title: '다운로드 속도 제한',
                hint: 'KB/s · 0 = 무제한',
                key: 'downLimitKB',
                presets: [
                  {label: '무제한', value: 0},
                  {label: '1 MB/s', value: 1024},
                  {label: '5 MB/s', value: 5120},
                  {label: '10 MB/s', value: 10240},
                ],
              })
            }
          />
          <Row
            p={p}
            label="업로드 속도 제한"
            value={limitLabel(st.upLimitKB)}
            sub="업로드를 너무 낮추면 상대 피어가 내게 덜 보내줍니다 (최소 50 KB/s 권장)"
            onPress={() =>
              setPrompt({
                title: '업로드 속도 제한',
                hint: 'KB/s · 0 = 무제한',
                key: 'upLimitKB',
                presets: [
                  {label: '무제한', value: 0},
                  {label: '100 KB/s', value: 100},
                  {label: '500 KB/s', value: 500},
                  {label: '1 MB/s', value: 1024},
                ],
              })
            }
          />
          <Row
            p={p}
            label="동시 다운로드 수"
            value={st.activeDownloads > 0 ? `${st.activeDownloads}개` : '제한 없음'}
            onPress={() =>
              setPrompt({
                title: '동시 다운로드 수',
                hint: '0 = 제한 없음',
                key: 'activeDownloads',
                presets: [
                  {label: '제한 없음', value: 0},
                  {label: '1', value: 1},
                  {label: '3', value: 3},
                  {label: '5', value: 5},
                ],
              })
            }
          />
        </Section>

        <Section title="연결" p={p}>
          <Row
            p={p}
            label="수신 포트"
            value={st.port > 0 ? String(st.port) : `자동 (${st.effectivePort})`}
            sub="0 = 자동. 6881 같은 기본 포트는 통신사가 속도를 제한하는 경우가 있습니다"
            onPress={() =>
              setPrompt({
                title: '수신 포트',
                hint: '1024~65535 · 0 = 자동',
                key: 'port',
                presets: [{label: '자동', value: 0}],
              })
            }
          />
          <Row
            p={p}
            label="최대 연결 수"
            value={String(st.maxConnections)}
            onPress={() =>
              setPrompt({
                title: '최대 연결 수',
                hint: '전체 피어 연결 수',
                key: 'maxConnections',
                presets: [
                  {label: '100', value: 100},
                  {label: '300', value: 300},
                  {label: '500', value: 500},
                ],
              })
            }
          />
          <Row
            p={p}
            label="공개 트래커 자동 추가"
            sub="죽은 트래커만 있는 토렌트에서 피어를 찾는 데 가장 효과가 큽니다 (비공개 토렌트 제외)"
            right={
              <Switch
                value={st.extraTrackers}
                onValueChange={v => onChange({extraTrackers: v})}
                trackColor={{true: p.accent}}
              />
            }
          />
          <Row
            p={p}
            label="DHT"
            sub="트래커 없이 피어 찾기"
            right={
              <Switch
                value={st.dht}
                onValueChange={v => onChange({dht: v})}
                trackColor={{true: p.accent}}
              />
            }
          />
          <Row
            p={p}
            label="로컬 피어 탐색 (LSD)"
            right={
              <Switch
                value={st.lsd}
                onValueChange={v => onChange({lsd: v})}
                trackColor={{true: p.accent}}
              />
            }
          />
          <Row
            p={p}
            label="UPnP / NAT-PMP 포트 열기"
            sub="공유기에서 포트를 자동으로 엽니다 (모바일 데이터에서는 효과 없음)"
            right={
              <Switch
                value={st.upnp}
                onValueChange={v => onChange({upnp: v})}
                trackColor={{true: p.accent}}
              />
            }
          />
          <Row
            p={p}
            label="프로토콜 암호화"
            value={ENC_LABEL[st.encryption]}
            onPress={() => setEncOpen(true)}
          />
        </Section>

        <Section title="저장" p={p}>
          <Row
            p={p}
            label="기본 다운로드 폴더"
            value={st.downloadDir ? shortPath(st.downloadDir) : '자동'}
            sub={`현재: ${props.saveDir}`}
            onPress={chooseFolder}
          />
          {!!st.downloadDir && (
            <Row p={p} label="자동 폴더로 되돌리기" onPress={() => onChange({downloadDir: ''})} />
          )}
          {!props.publicStorage && (
            <Row
              p={p}
              label="모든 파일 접근 허용"
              sub="허용하면 Download/RaonTorrent 또는 원하는 폴더에 저장합니다 (새로 추가하는 토렌트부터)"
              onPress={askStorage}
              right={<Text style={[s.value, {color: p.warn}]}>필요</Text>}
            />
          )}
        </Section>

        <Section title="다운로드" p={p}>
          <Row
            p={p}
            label="순차 다운로드"
            sub="앞부분부터 받아 동영상을 미리 볼 수 있지만, 전체 속도는 느려질 수 있습니다"
            right={
              <Switch
                value={st.sequential}
                onValueChange={v => onChange({sequential: v})}
                trackColor={{true: p.accent}}
              />
            }
          />
        </Section>

        <Text style={s.footer}>
          RaonTorrent 1.1 · libtorrent {props.libtorrent} · Android API {props.sdk}
        </Text>
      </ScrollView>

      <NumberPrompt
        p={p}
        prompt={prompt}
        current={prompt ? Number(st[prompt.key]) : 0}
        onCancel={() => setPrompt(null)}
        onSubmit={async v => {
          const key = prompt!.key;
          setPrompt(null);
          await onChange({[key]: v} as Partial<AppSettings>);
        }}
      />

      <Modal transparent visible={encOpen} animationType="fade" onRequestClose={() => setEncOpen(false)}>
        <Pressable style={s.backdrop} onPress={() => setEncOpen(false)}>
          <View style={s.dialog}>
            <Text style={s.dialogTitle}>프로토콜 암호화</Text>
            {(['enabled', 'forced', 'disabled'] as const).map(k => (
              <Pressable
                key={k}
                style={s.option}
                onPress={() => {
                  setEncOpen(false);
                  onChange({encryption: k});
                }}>
                <Text style={s.optionText}>{ENC_LABEL[k]}</Text>
                {st.encryption === k && <Text style={s.check}>✓</Text>}
              </Pressable>
            ))}
            <Text style={s.rowSub}>
              '강제'는 암호화를 지원하지 않는 피어와 연결하지 않아 피어 수가 줄 수 있습니다.
            </Text>
          </View>
        </Pressable>
      </Modal>
    </View>
  );
}

function shortPath(path: string) {
  return path.replace(/^\/storage\/emulated\/0\//, '');
}

function Section({title, p, children}: {title: string; p: Palette; children: React.ReactNode}) {
  const s = styles(p);
  return (
    <View style={s.section}>
      <Text style={s.sectionTitle}>{title}</Text>
      <View style={s.sectionBody}>{children}</View>
    </View>
  );
}

function Row({
  p,
  label,
  sub,
  value,
  right,
  onPress,
}: {
  p: Palette;
  label: string;
  sub?: string;
  value?: string;
  right?: React.ReactNode;
  onPress?: () => void;
}) {
  const s = styles(p);
  return (
    <Pressable
      onPress={onPress}
      disabled={!onPress}
      style={({pressed}) => [s.row, pressed && {backgroundColor: p.track}]}>
      <View style={s.rowMain}>
        <Text style={s.rowLabel}>{label}</Text>
        {!!sub && <Text style={s.rowSub}>{sub}</Text>}
      </View>
      {value !== undefined && (
        <Text style={s.value} numberOfLines={1}>
          {value} {onPress ? '›' : ''}
        </Text>
      )}
      {right}
    </Pressable>
  );
}

function NumberPrompt({
  p,
  prompt,
  current,
  onCancel,
  onSubmit,
}: {
  p: Palette;
  prompt: Prompt | null;
  current: number;
  onCancel: () => void;
  onSubmit: (v: number) => void;
}) {
  const s = styles(p);
  const [text, setText] = useState('');
  return (
    <Modal
      transparent
      visible={!!prompt}
      animationType="fade"
      onShow={() => setText(String(current))}
      onRequestClose={onCancel}>
      <Pressable style={s.backdrop} onPress={onCancel}>
        <Pressable style={s.dialog} onPress={() => {}}>
          <Text style={s.dialogTitle}>{prompt?.title}</Text>
          <TextInput
            style={s.input}
            value={text}
            onChangeText={t => setText(t.replace(/[^0-9]/g, ''))}
            keyboardType="number-pad"
            placeholder={prompt?.hint}
            placeholderTextColor={p.sub}
            autoFocus
          />
          <Text style={s.rowSub}>{prompt?.hint}</Text>
          <View style={s.presets}>
            {prompt?.presets.map(pr => (
              <Pressable key={pr.label} style={s.chip} onPress={() => onSubmit(pr.value)}>
                <Text style={s.chipText}>{pr.label}</Text>
              </Pressable>
            ))}
          </View>
          <View style={s.dialogButtons}>
            <Pressable style={s.dlgBtn} onPress={onCancel}>
              <Text style={s.dlgBtnText}>취소</Text>
            </Pressable>
            <Pressable
              style={s.dlgBtn}
              onPress={() => {
                let v = parseInt(text || '0', 10);
                if (prompt?.key === 'port' && v !== 0) v = Math.min(65535, Math.max(1024, v));
                if (prompt?.key === 'maxConnections') v = Math.min(2000, Math.max(20, v || 300));
                onSubmit(isNaN(v) ? 0 : v);
              }}>
              <Text style={[s.dlgBtnText, {color: p.accent}]}>확인</Text>
            </Pressable>
          </View>
        </Pressable>
      </Pressable>
    </Modal>
  );
}

const styles = (p: Palette) =>
  StyleSheet.create({
    root: {flex: 1, backgroundColor: p.bg},
    topBar: {
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'space-between',
      paddingHorizontal: 8,
      height: 56,
      borderBottomWidth: StyleSheet.hairlineWidth,
      borderBottomColor: p.line,
    },
    back: {width: 44, height: 44, alignItems: 'center', justifyContent: 'center'},
    backText: {fontSize: 34, color: p.text, marginTop: -4},
    topTitle: {fontSize: 18, fontWeight: '700', color: p.text},
    section: {marginTop: 20, paddingHorizontal: 16},
    sectionTitle: {fontSize: 15, fontWeight: '700', color: p.text, marginBottom: 8, marginLeft: 4},
    sectionBody: {
      backgroundColor: p.card,
      borderRadius: 14,
      borderWidth: 1,
      borderColor: p.line,
      overflow: 'hidden',
    },
    row: {
      flexDirection: 'row',
      alignItems: 'center',
      paddingHorizontal: 14,
      paddingVertical: 13,
      gap: 10,
      borderBottomWidth: StyleSheet.hairlineWidth,
      borderBottomColor: p.line,
    },
    rowMain: {flex: 1},
    rowLabel: {fontSize: 15, color: p.text},
    rowSub: {fontSize: 12.5, color: p.sub, marginTop: 3, lineHeight: 18},
    value: {fontSize: 14, color: p.sub, maxWidth: 170},
    check: {fontSize: 18, color: p.accent, fontWeight: '700'},
    footer: {textAlign: 'center', color: p.sub, fontSize: 12, marginTop: 24},
    backdrop: {
      flex: 1,
      backgroundColor: 'rgba(0,0,0,0.45)',
      justifyContent: 'center',
      padding: 24,
    },
    dialog: {backgroundColor: p.card, borderRadius: 18, padding: 20},
    dialogTitle: {fontSize: 17, fontWeight: '700', color: p.text, marginBottom: 12},
    input: {
      height: 48,
      borderRadius: 12,
      borderWidth: 1,
      borderColor: p.line,
      backgroundColor: p.input,
      color: p.text,
      fontSize: 17,
      paddingHorizontal: 14,
    },
    presets: {flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginTop: 12},
    chip: {paddingHorizontal: 12, paddingVertical: 8, borderRadius: 16, backgroundColor: p.track},
    chipText: {color: p.text, fontSize: 13.5, fontWeight: '600'},
    dialogButtons: {flexDirection: 'row', justifyContent: 'flex-end', gap: 8, marginTop: 16},
    dlgBtn: {paddingHorizontal: 14, paddingVertical: 10},
    dlgBtnText: {fontSize: 15, fontWeight: '700', color: p.text},
    option: {
      flexDirection: 'row',
      justifyContent: 'space-between',
      alignItems: 'center',
      paddingVertical: 12,
    },
    optionText: {fontSize: 15, color: p.text},
  });
