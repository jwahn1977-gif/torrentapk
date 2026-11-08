/**
 * 휴지통 — '파일도 삭제'한 항목을 30일 동안 보관했다가 되살리거나 영구 삭제
 */
import React, { useCallback, useEffect, useState } from 'react';
import {
  Alert,
  FlatList,
  Pressable,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import Torrent from '../specs/NativeTorrent';
import { Palette, fmtSize, usePalette } from './theme';

export interface TrashItem {
  hash: string;
  name: string;
  size: number;
  deleted: number;
  expires: number;
}

interface Props {
  onClose: () => void;
  onChanged: () => void;
  toast: (msg: string) => void;
}

function daysLeft(expires: number): string {
  const d = Math.ceil((expires - Date.now()) / 86400000);
  return d <= 1 ? '곧 영구 삭제' : `${d}일 후 영구 삭제`;
}

export default function TrashScreen({ onClose, onChanged, toast }: Props) {
  const p = usePalette();
  const s = styles(p);
  const insets = useSafeAreaInsets();
  const [items, setItems] = useState<TrashItem[]>([]);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    try {
      const list: TrashItem[] = JSON.parse(await Torrent.listTrash());
      list.sort((a, b) => b.deleted - a.deleted);
      setItems(list);
    } catch {}
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const run = async (fn: () => Promise<unknown>) => {
    setBusy(true);
    try {
      await fn();
    } catch (e: any) {
      Alert.alert('오류', e?.message ?? String(e));
    } finally {
      setBusy(false);
    }
    await load();
    onChanged();
  };

  const restore = (t: TrashItem) =>
    run(async () => {
      const name = await Torrent.restoreTrash(t.hash);
      toast(`되살렸습니다: ${name}`);
    });

  const purge = (t: TrashItem) =>
    Alert.alert('영구 삭제', `${t.name}\n\n되돌릴 수 없습니다.`, [
      { text: '취소', style: 'cancel' },
      {
        text: '영구 삭제',
        style: 'destructive',
        onPress: () => run(() => Torrent.deleteTrash(t.hash)),
      },
    ]);

  const emptyAll = () =>
    Alert.alert(
      '휴지통 비우기',
      `${items.length}개 항목을 영구 삭제합니다.\n되돌릴 수 없습니다.`,
      [
        { text: '취소', style: 'cancel' },
        {
          text: '비우기',
          style: 'destructive',
          onPress: () => run(() => Torrent.deleteTrash('')),
        },
      ],
    );

  return (
    <View style={[s.root, { paddingTop: insets.top }]}>
      <View style={s.topBar}>
        <Pressable onPress={onClose} hitSlop={12} style={s.back}>
          <Text style={s.backText}>‹</Text>
        </Pressable>
        <Text style={s.topTitle}>휴지통</Text>
        <View style={s.back} />
      </View>
      <Text style={s.note}>
        삭제한 파일은 30일 동안 보관된 뒤 자동으로 영구 삭제됩니다.
      </Text>

      <FlatList
        data={items}
        keyExtractor={t => t.hash}
        contentContainerStyle={[s.list, { paddingBottom: insets.bottom + 24 }]}
        ItemSeparatorComponent={Sep}
        ListEmptyComponent={
          <View style={s.empty}>
            <Text style={s.emptyTitle}>휴지통이 비어 있습니다</Text>
          </View>
        }
        renderItem={({ item }) => (
          <View style={s.card}>
            <Text style={s.name} numberOfLines={2}>
              {item.name}
            </Text>
            <Text style={s.meta}>
              {fmtSize(item.size)} · {daysLeft(item.expires)}
            </Text>
            <View style={s.actions}>
              <Pressable
                style={[s.smallBtn, busy && s.dim]}
                disabled={busy}
                onPress={() => purge(item)}
              >
                <Text style={[s.smallBtnText, { color: p.danger }]}>
                  영구 삭제
                </Text>
              </Pressable>
              <Pressable
                style={[s.smallBtn, s.restoreBtn, busy && s.dim]}
                disabled={busy}
                onPress={() => restore(item)}
              >
                <Text style={[s.smallBtnText, { color: p.accentText }]}>
                  되살리기
                </Text>
              </Pressable>
            </View>
          </View>
        )}
        ListFooterComponent={
          items.length > 0 ? (
            <Pressable
              style={[s.emptyBtn, busy && s.dim]}
              disabled={busy}
              onPress={emptyAll}
            >
              <Text style={[s.smallBtnText, { color: p.danger }]}>
                휴지통 비우기
              </Text>
            </Pressable>
          ) : undefined
        }
      />
    </View>
  );
}

const Sep = () => <View style={sepStyle.sep} />;
const sepStyle = StyleSheet.create({ sep: { height: 12 } });

const styles = (p: Palette) =>
  StyleSheet.create({
    root: { flex: 1, backgroundColor: p.bg },
    topBar: {
      flexDirection: 'row',
      alignItems: 'center',
      paddingHorizontal: 8,
      height: 52,
    },
    back: { width: 44, alignItems: 'center', justifyContent: 'center' },
    backText: { fontSize: 32, color: p.text, marginTop: -4 },
    topTitle: {
      flex: 1,
      textAlign: 'center',
      fontSize: 17,
      fontWeight: '700',
      color: p.text,
    },
    note: { fontSize: 13, color: p.sub, paddingHorizontal: 16, lineHeight: 19 },
    list: { padding: 16 },
    empty: { alignItems: 'center', paddingTop: 60 },
    emptyTitle: { fontSize: 16, fontWeight: '600', color: p.sub },
    card: {
      padding: 14,
      borderRadius: 14,
      backgroundColor: p.card,
      borderWidth: 1,
      borderColor: p.line,
    },
    name: { fontSize: 15, fontWeight: '600', color: p.text, marginBottom: 6 },
    meta: { fontSize: 12.5, color: p.sub },
    actions: {
      flexDirection: 'row',
      marginTop: 10,
      gap: 8,
      justifyContent: 'flex-end',
    },
    smallBtn: {
      paddingHorizontal: 14,
      paddingVertical: 8,
      borderRadius: 10,
      backgroundColor: p.track,
    },
    restoreBtn: { backgroundColor: p.accent },
    smallBtnText: { fontSize: 13.5, fontWeight: '600', color: p.text },
    emptyBtn: {
      marginTop: 20,
      height: 46,
      borderRadius: 12,
      borderWidth: 1,
      borderColor: p.danger,
      alignItems: 'center',
      justifyContent: 'center',
    },
    dim: { opacity: 0.5 },
  });
