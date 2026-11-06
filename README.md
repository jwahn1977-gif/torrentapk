# RaonTorrent

React Native(0.88) + [libtorrent4j](https://github.com/aldenml/libtorrent4j)(libtorrent 2.x) 기반 Android 토렌트 다운로더.

## 기능
- `.torrent` 파일 열기(앱 내 파일 선택 / 파일 관리자·브라우저에서 "RaonTorrent로 열기")
- 마그넷 링크 붙여넣기 및 `magnet:` 링크 클릭 시 자동 추가
- 진행률·속도·피어·남은 시간 표시(1초 갱신), 일시정지/재개/삭제(파일 포함 선택)
- 포그라운드 서비스로 화면이 꺼져도 다운로드 유지(알림에 속도 표시)
- 저장 위치: 기본은 앱 전용 폴더, '모든 파일 접근' 허용 시 `Download/RaonTorrent`
- 완료된(또는 진행 중인) 가장 큰 파일을 외부 플레이어로 열기
- 앱 재시작 시 목록 복원(파일 재검사 후 이어받기)
- DHT/LSD/트래커(UDP·HTTP) 지원, 라이트/다크 모드

## 요구 사항
- Android 9(API 28) 이상, ABI: arm64-v8a / armeabi-v7a / x86_64

## 빌드
```bash
npm ci
cd android && ./gradlew assembleRelease
# → android/app/build/outputs/apk/release/app-release.apk
```
GitHub에 올리면 `.github/workflows/android.yml`이 APK를 빌드해 Actions 아티팩트로 올립니다.

## 구조
| 경로 | 역할 |
|---|---|
| `App.tsx` | UI (목록·추가·설정) |
| `specs/NativeTorrent.ts` | TurboModule 스펙(Codegen) |
| `android/.../torrent/TorrentEngine.kt` | libtorrent 세션, 추가/일시정지/삭제/상태 JSON, 목록 저장·복원 |
| `android/.../torrent/TorrentModule.kt` | JS ↔ 엔진 브리지, 파일 선택기, 파일 열기 |
| `android/.../torrent/TorrentService.kt` | 포그라운드 서비스(dataSync) + 알림 |

## 알려진 제약
- Android 15+는 dataSync 포그라운드 서비스가 하루 6시간으로 제한됩니다(초과 시 서비스 종료, 앱을 열면 재개).
- 재시작 시 resume 데이터 대신 파일 해시 재검사를 하므로 큰 파일은 '파일 확인 중'이 잠시 표시됩니다.
- Wi-Fi 전용 모드, 속도 제한 UI, 파일별 선택 다운로드는 미구현.
- 서명은 템플릿의 debug keystore입니다(사이드로드용). 배포 시 자체 키로 교체하세요.
