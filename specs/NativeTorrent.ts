import type {TurboModule} from 'react-native';
import {TurboModuleRegistry} from 'react-native';

export interface Spec extends TurboModule {
  /** 엔진 시작 + 저장된 목록 복원 (여러 번 호출해도 안전) */
  start(): Promise<void>;
  /** 토렌트 목록(JSON 배열 문자열) */
  getTorrents(): Promise<string>;
  /** 엔진/저장소 정보(JSON 문자열) */
  getInfo(): Promise<string>;
  /** magnet:, content://, file:// URI 추가 → 이름 반환 */
  addFromUri(uri: string): Promise<string>;
  /** 시스템 파일 선택기로 .torrent 선택 → URI ('' = 취소) */
  pickTorrentFile(): Promise<string>;
  pause(hash: string): Promise<void>;
  resume(hash: string): Promise<void>;
  remove(hash: string, deleteFiles: boolean): Promise<void>;
  /** 다운로드된 파일(가장 큰 파일)을 외부 앱으로 열기 */
  openFile(hash: string): Promise<void>;
  /** '모든 파일 접근' 설정 화면 열기 (공용 Download 폴더 저장용) */
  requestAllFilesAccess(): void;
}

export default TurboModuleRegistry.getEnforcing<Spec>('NativeTorrent');
