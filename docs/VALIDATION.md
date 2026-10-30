# 2026-10-29 · 전체 코드 가독성 정리 (미배포)

앱/테스트 Java 133개, 직접 관리하는 C/C++ 9개, Python 5개, Android XML 18개, Gradle 3개, CMake/PowerShell/shell 각 1개, 총 171개 파일을 정리했다. 외부 vendor 원본·Gradle wrapper·생성물은 제외했다. 기존 작업 소스와 기능은 유지하고 0.20.1/code27 개발 후보를 공개 배포하지 않았다. 스타일 규칙과 도구 버전은 [CODE_STYLE.md](CODE_STYLE.md)를 참고한다.

- 기존 소스를 별도 로컬 스냅샷으로 보존했다. Python AST, XML 요소/속성/텍스트, PowerShell 토큰을 비교해 동일함을 확인했다. Java 133개와 C/C++·Python·XML·CMake의 포매터 재검사도 통과했다.
- 정리 직전 빌드와 비교해 디버그 정보를 제거한 앱 Java 클래스 209개의 바이트코드가 모두 같았다. 네 ABI 네이티브 라이브러리 8개의 실행 .text 섹션도 동일했다. 문자열 리터럴과 처리 순서는 바꾸지 않았다.
- debug 앱/계측 APK 및 lintDebug 빌드 성공. lint 오류 0개/경고 88개. JVM 기본/가사 타이밍/원격 HTTP 40개 검사 통과. 로그 `.local/build-readability.log`, `.local/build-readability-tests.log`.
- API36 x86_64에서 EngineTest·PlaybackStabilityTest·EqAndThemeTest·CodecNegotiationTest·DriveMetadataSpeedTest·DriveTest·IncrementalLibraryTest·DriveFolderArtworkTest·DriveArtworkPersistenceTest·TrackFormatTest 총 84개 중 80개 통과, 4개 실패. 전체 통과로 표현하지 않는다. 로그 `.local/test-readability.log`.
- 실패: EngineTest.playerLayoutKeepsSinglePlaybackControls(재생 컨트롤 조회 null), largeLibraryRecyclesRows(RecyclerView 조회 null), embeddedArtworkIsDownsampledBeforeDecode(현재 2048px 정책과 다른 기존 512px 기대값), PlaybackStabilityTest.repeatedSeekPauseAndTrackChangesRemainStableWithHeapPressure(Source error). 마지막 검사는 앱 프로세스를 종료한 뒤 단독으로도 실패했다(`.local/test-readability-playback-isolated.log`). 이번 서식 정리에서 이 검사나 앱 동작을 바꿔 실패를 숨기지 않았다. 클래스/네이티브 실행 코드 비교는 통과했지만 실패 항목의 별도 원인 조사는 남아 있다.
- MainActivity는 압축된 360줄에서 구문을 구분한 5,174줄로 정리됐다. 개발 APK SHA-256 `9a8a549e99947b063f02bd9e77f3455e559a3c428528aee70a87b265fd82cf82`. 공개 릴리스·업데이트 피드·서명 자료는 변경하지 않았다.

---

# 2026-10-29 · Drive 메타데이터 읽기 개선 (미배포)

0.20.1/code27 개발 후보에서 FLAC 블록 직접 읽기, 작은 앞부분 16KiB 요청, 일반 기기 최대 4개 병렬 분석과 분석 완료 상태 재사용을 구현했다. 사용자 지시대로 공개 배포·업데이트 피드·배포 서명은 변경하지 않았다. 상세 동작과 큰 태그/표지 제한은 [DRIVE_METADATA_PERFORMANCE.md](DRIVE_METADATA_PERFORMANCE.md)를 참고한다.

- debug 앱/계측 APK 빌드 및 lintDebug 성공. lint 오류 0개/경고 88개. 최종 증분 빌드 로그 `.local/build-drive-speed-final.log`, 테스트 초기화 보완 빌드 `.local/build-drive-speed-test-retry.log`.
- API36 x86_64에서 DriveMetadataSpeedTest·DriveTest·IncrementalLibraryTest·DriveFolderArtworkTest·DriveArtworkPersistenceTest 총 35개 통과(9.255초). 실제 FLAC fixture 태그·트랙 번호·원본 형식·재생 시간·내장 앞표지를 확인했다. 잘린/손상된 태그, 거대 표지 건너뛰기와 작은 단위 readAt도 검사했다.
- 64MiB 모의 HTTP 파일에 실제 FLAC 메타데이터를 넣은 검사에서 범위 요청 1회, 전송 16KiB 이하로 정보를 읽었다. 파일 크기는 인덱스에 알려진 상태이며 모든 파일/실제 네트워크에서 같은 요청 수나 속도를 보장하지 않는다. 그림이 큰 파일은 추가 구간이 필요하다.
- 8곡 지연 모의 분석에서 동시 실행 최대 4개를 확인하고, 변경 없는 재검색에서 분석 호출이 증가하지 않았다. 기존 구간 스트리밍/탐색·계정 오류·전송 예산·증분 확인·폴더 표지·영구 표지 검사도 통과했다.
- 첫 전체 실행은 35개 중 저장된 로컬 전용 필터를 초기화하지 않은 기존 혼합 폴더 UI 검사 1개가 실패했다. 검사에서 source/query/folderCursor를 초기화하도록 보완하고 앱 APK 변경 없이 35개 전체를 다시 통과했다. 로그 `.local/test-drive-speed.log`, `.local/test-drive-speed-final.log`.
- debug APK SHA-256 `4e503f245087a3793e0a74dee4d0bef64fce9d8d097fa02dfc28aa71d332de58`. 실제 Google 계정·실물 기기에서 처리 시간의 전후 비교는 하지 않았다. release APK는 이번 작업에서 새로 빌드하지 않았다.

---

# 2026-10-29 · 목록 포맷·Hi-Res와 Drive 표지 보관 (미배포)

사용자 요청으로 공개 배포를 보류했다. 버전은 기존 개발 후보 0.20.1/code27이며 공개 APK/피드/서명을 변경하지 않았다. 상세 동작과 한계는 [UI-FORMAT-DRIVE-ARTWORK-WIP.md](UI-FORMAT-DRIVE-ARTWORK-WIP.md)를 참고한다.

- `tools/build-windows.ps1 -Variant Debug -AndroidTests`: debug 앱·계측 APK·lintDebug 성공. lint 오류 0개/경고 88개. 이번 변경의 release APK는 별도로 빌드하지 않았다.
- API36 x86_64 에뮬레이터에서 TrackFormatTest, DriveArtworkPersistenceTest, ArtworkQualityTest, DriveFolderArtworkTest, IncrementalLibraryTest 총 21개 통과(15.39초). 실제 24bit/48kHz FLAC fixture의 헤더, 판정 경계·JSON 보존·불명 형식 숨김·행 재활용, 5개 곡 목록의 다크/라이트 위치를 검사했다. 영구 표지의 캐시/메모리 초기화 후 재사용, 이전 표지와 품질 표식 이전·정리, 기존 증분 검색의 음원 재열기 억제도 확인했다.
- 실제 화면 캡처 10장: `screenshots/format-badges/`. 직접 만든 표지와 테스트 메타데이터를 사용했으며 일반 앱에 데모 곡을 넣지 않았다. 스마트폰 배치 검사 후 에뮬레이터 해상도/밀도를 원래 값으로 복원했다.
- Drive 앨범아트는 cache/art에서 files/drive-art로 옮겨 OS의 일반 캐시 정리로 인한 소실을 줄인다. 실제 사용자 Drive 계정·실물 기기에서 장기 미사용 후 속도는 이번에 측정하지 않았다. 이미 사라진 표지를 네트워크 없이 복원할 수는 없다.
- 기존 비트 깊이/샘플레이트 없는 FLAC은 폴더/전체 다시 검색 후 Hi-Res 판정이 보완된다. 시작 시 증분 확인에서 기존 Drive 음원을 일괄 다시 읽지 않는다.
- 로그: `.local/build-format-console.log`, `.local/test-format-drive-final.log`. debug APK SHA-256: `404cf3a4939da89b6889794e400571ae17a970c650975073b870998de4fead79`.

---

# 2026-10-29 · Windows CMake 종료 원인 확인

최소 Android C/C++ 프로젝트로 CMake 종료를 분리 재현했다. 소스와 출력 경로만 영문으로 지정해도 실행 작업 디렉터리가 한글이면 NDK 아카이버 검색 중 -1073740791로 종료됐다. CMake 3.31.8에서도 같은 현상이 발생해 버전 교체만으로는 해결되지 않았다. 동일한 CMake 3.22.1·NDK 27.2.12479018에서 작업 디렉터리까지 영문으로 바꾸자 구성과 C/C++ 컴파일러 ABI 검사가 성공했다.

`tools/build-windows.ps1`은 빌드 입력만 영문 TEMP의 고유 폴더로 복사해 Gradle을 실행하고 APK와 lint 보고서를 원본 폴더로 복사한다. 원본 gradle.properties·오디오 코드·NDK/CMake 버전은 변경하지 않는다. 서명 개인 자료는 복사하지 않고 release APK에 배포 서명을 하지 않는다. 이전 실패 기록은 아래에 보존한다.

- 새 스크립트 기본 실행으로 `:app:lintDebug :app:assembleDebug :app:assembleRelease` 성공(2분 18초, 111개 작업). 네 ABI의 Debug 및 RelWithDebInfo 네이티브 빌드를 모두 완료했다. lint 오류 0개/경고 88개, 기존 SoX CMake 정책/C99 및 Google 인증 라이브러리 D8 경고는 남아 있다.
- 두 APK의 패키지 `dev.aura.player`, 0.20.1/code27, minSdk26/targetSdk36을 aapt로 확인했다. 각각 네 ABI에 libaura_audio.so·libsoxr.so 총 8개 라이브러리가 정확히 포함되어 있고 `zipalign -c -P 16 4` 검사를 통과했다.
- debug APK는 `apksigner verify --verbose --print-certs` 성공(v2). 로컬 Android Debug 인증서 SHA-256은 `d7eecfcd3d617aa14f464f60ecb5cb5526e5ba4cee909cd05fb6b167803e50bd`이며 공개 배포 서명과 다르다. release APK는 debuggable이 아니며 미서명 상태다. 실기기 설치·재생 검사는 이번 작업에서 실행하지 않았다.
- debug: `app/build/outputs/apk/debug/app-debug.apk`, 14,338,222바이트, SHA-256 `f503814b70a2b6612ab8bd97a49a817a486d3f6bfc62890506784b6500c62c89`.
- release: `app/build/outputs/apk/release/app-release-unsigned.apk`, 10,503,524바이트, SHA-256 `15ec17eb26c6ce8b830576a7b2c594fed29b4a9c2f1feca9df090bf0904aad18`.
- 로그: `.local/build-windows.log`. lint 보고서: `app/build/reports/lint-results-debug.html` 및 XML. 원본 gradle.properties는 변경하지 않았고 `git diff --check` 성공. 기존 서명 백업 부재로 공식 서명·GitHub 배포는 진행하지 않았다.

---

# 2026-10-29 · 문서·안내 코드 정리 / 0.20.1 후보

현재 작업 소스는 0.20.1/code27 미배포 후보다. 구현된 기능을 미구현으로 표시하던 roadmap 화면과 라우팅 분기를 제거했다. 개발 현황·Drive·호환성·업데이트·서명 문서를 현재 구현에 맞추고 WIP 문서는 후속 배포 여부를 구분했다. 아래 날짜별 검사는 당시 결과이며 이번 후보에서 모두 재실행한 결과가 아니다.

- 2026-10-29 GitHub Releases API에서 공개 최신 v0.20.0 및 공개 자산 목록을 확인했다. 공개 update-v2.json은 0.20.0/code26, update.json은 고정 전환용 0.18.1/code23을 반환했다. 이번에 공개 APK를 재다운로드하거나 서명을 다시 검증한 것은 아니다.
- JDK의 `javac --release 17`로 기존 기본 검사 코드를 컴파일했다. CoreTest 11개, KaraokeTimingTest 8개, RemoteTest 21개, 총 40개 통과. RemoteTest는 로컬 소켓 접근 허용 후 실행했다.
- Android Studio JBR와 로컬 Android SDK에서 `:app:compileDebugJavaWithJavac :app:lintDebug` 성공. lint XML 기준 오류 0개/경고 88개다. Java 코드 변경과 Android 정적 검사를 확인했다. 이는 네이티브 APK 빌드나 실제 화면 실행 검증을 대신하지 않는다.
- 전체 debug APK 빌드를 시도했다. Windows 한글 프로젝트 경로 검사를 빌드 중에만 `android.overridePathCheck=true`로 해제한 뒤, `configureCMakeDebug[arm64-v8a]`에서 CMake 프로세스가 -1073740791로 종료됐다. 네이티브 빌드 실패 원인은 확정하지 않았다. 따라서 전체 APK 빌드·실행·계측 검사를 통과했다고 주장하지 않는다. 임시 Gradle 설정은 원복한다.
- 수정 파일의 `git diff --check`를 통과했고 앱 Java/리소스에서 `roadmap` 및 오래된 미구현 안내가 남아 있지 않음을 검색으로 확인했다.
- `.local/signing/`과 `.local/signing-migration/AURA-0.18.1-bridge.apk`가 이 체크아웃에 없어 서명·배포를 진행하지 않았다. 저장소 지침에 따라 기존 키·암호·lineage·동결 전환 APK 복원이 필요하다. 새 키를 생성하지 않았다.

## 0.20.0 공개 상태와 아래 기록의 해석

USB 볼륨 팝업은 이후 [v0.20.0](https://github.com/Mobil0010/AURA/releases/tag/v0.20.0)에 공개됐다. 아래 ‘미배포’는 2026-10-28 검사 당시 상태다. 0.19.0에 포함된 기능의 과거 미배포 표기도 같은 방식으로 해석한다. 당시 로그·검사 결과·실물 미검증 범위는 보존한다.

---

# 2026-10-28 · USB DAC 볼륨 팝업 (당시 미배포, 이후 0.20.0 포함)

사용자 요청으로 배포를 보류했다. USB 직접 출력의 명시적 볼륨 조작(화면 슬라이더/물리 키/MediaSession 볼륨 경로)이 들어오면 HUD를 표시한다. 앱 안에서는 미니 재생바 위에 배치하고 플레이어·가사·오른쪽 플레이어 패널에서는 숨긴다. 마지막 조작 후 3초에 사라지며 추가 조작 시 대기 시간을 초기화한다. 180ms 등장/220ms 퇴장과 시스템 애니메이션 설정을 반영한다. 라우트 해제·앱 재진입·서비스 종료에는 제거한다.

오디오 설정에 앱 내부 표시(기본 켜짐)와 백그라운드 표시(기본 꺼짐)를 분리했다. 백그라운드에는 SYSTEM_ALERT_WINDOW 권한을 선언하고, 사용자가 켤 때 용도·선택 가능 여부를 먼저 설명한다. 권한 설정을 허용한 후 오디오 설정에서 다시 켜도록 안내한다. 권한이 없거나 설정이 꺼져 있으면 시스템 오버레이를 만들지 않는다. 재생 서비스가 살아 있는 동안 TYPE_APPLICATION_OVERLAY로 짧게 표시하며 별도 상시 서비스는 추가하지 않았다.

debug/test 빌드 및 lintDebug 성공. API36에서 위치·시간 연장·자동 제거·플레이어 제외·설정 끄기·권한 거절·안내 4개 통과, 권한을 허용한 별도 배경 표시/자동 제거 1개 통과. API28에서 물리 볼륨 키 경로를 포함한 팝업 4개 + 기존 DAC 볼륨 기억 2개 + USB 볼륨 프로토콜 4개 = 10개 통과. 로그: `.local/test-usb-popup.log`, `.local/test-usb-popup-background-final.log`, `.local/test-usb-popup-api28.log`. 에뮬레이터 권한은 테스트 후 기본값으로 복원했다.

실제 앱 UI/시스템 오버레이를 사용했으며 USB 장치는 테스트용 Access로 모의했다. 실제 DAC·S25·NW-ZX507의 볼륨 키와 제조사별 백그라운드 제한은 미검증이다. 버전/공개 APK/업데이트 피드는 변경하지 않았다.

캡처: [앱 안](screenshots/usb-volume-popup/in-app.png), [백그라운드](screenshots/usb-volume-popup/background.png), [오디오 설정](screenshots/usb-volume-popup/settings.png), [권한 설명](screenshots/usb-volume-popup/permission.png).

---

# 2026-10-28 · AURA 0.19.0 공개 배포 완료

사용자 승인으로 https://github.com/Mobil0010/AURA/releases/tag/v0.19.0 에 code25를 게시했다. 홈 모음/백업/오프라인 보관/음향/재생 편의 기능과 태블릿·폴더블 UI, 기존 스타일 복원 및 오른쪽 패널 하단 확장까지 포함한다. 아래 미배포 표기는 당시 검사 시점의 기록이다.

동일한 보존 키와 v3.1 lineage로 release 빌드 및 lintRelease 성공. 공개 0.18.2를 검사용 API28/API36 에뮬레이터에 설치한 뒤 0.19.0을 -r로 무삭제 업데이트하고 실행 성공(Status ok). 실제 NW-ZX507/S25/USB 장치·실계정 Drive는 이번 배포 검사에서 연결하지 않았다. 기능·재생·UI 검사는 아래 개발 빌드 기록을 참고한다.

6개 공개 자산(APK, 전환 APK, 두 피드, 체크섬, 대응 소스 ZIP)을 게시했다. 소스 ZIP 616개 파일 무결성 및 개인 키/로컬 파일 제외 검사 성공. latest/update.json은 동결 전환용 0.18.1/code23, latest/update-v2.json은 0.19.0/code25이며 두 피드 전체를 준비 파일과 대조했다. 두 공개 APK 재다운로드 후 크기·SHA256·서명 검증 성공. 현재 APK 10,622,611바이트, SHA256 `eda93aa97b4a2acde8e5bf038f0e07c91970e8e83008e63a06ddc24f216d9447`. Android 13+ 새 인증서와 Android 8–12 기존 인증서 일치 및 v2/v3/v3.1 검증 성공. 동결 전환 APK 해시는 기존 기록과 동일하다.

로그: `.local/build-release-0.19.0.log`, `.local/lint-release-0.19.0.log`, `.local/release-upgrade-api28.log`, `.local/release-upgrade-api36.log`, `.local/publish-0.19.0.log`, `.local/public-0.19.0-*-signature.log`. 이 게시 후 기록은 로컬에 추가했고 공개 소스 ZIP을 덮어쓰지 않았다.

---

# 2026-10-28 · 오른쪽 플레이어 패널 하단 확장 (미배포)

하단 메뉴를 왼쪽 탐색 영역 안으로 옮겨 오른쪽 패널이 시스템 탐색 영역 직전까지 전체 높이를 쓰도록 했다. 패널이 열린 동안 메뉴는 왼쪽 영역 전체 너비를 사용한다. 패널의 긴 내용은 기존처럼 스크롤할 수 있다. debug/test 빌드 성공, API36 태블릿에서 패널/전체화면/가사/닫기·재열기/재생 유지 검사 1개 통과(`.local/test-panel-full-height.log`). [실제 캡처](screenshots/features-wip/tablet-panel-full-height.png). 배포하지 않았다.

---

# 2026-10-28 · 기존 플레이어 스타일 재대조 및 복원 (미배포)

패널용 플레이어를 별도로 구성하며 바뀐 시각 요소를 기존 playerScreen과 대조했다. 불필요한 이전/다음/셔플/반복 버튼 외곽선을 없애고 재생 버튼의 링·크기·간격, 양끝 시간 표시, 형식 배지, 아이콘이 있는 가사/대기열/도구 링크를 기존 스타일에 맞췄다. 빠졌던 출력 경로 링크와 한 줄 가사 미리보기를 복원했다. 패널/가로 배치/분할 가사 전환은 유지한다.

debug/test 빌드 성공. API36 태블릿에서 패널 너비·전체화면·분할 가사·최소화·제스처 닫기·마지막 모드 재열기·재생 유지 검사 1개 통과(`.local/test-player-style-restore.log`). 실제 USB 장치/물리 기기는 이번에 검사하지 않았다. [가로 전체화면](screenshots/features-wip/tablet-player-style-restored.png), [패널](screenshots/features-wip/tablet-panel-style-restored.png). 배포하지 않았다.

---

# 2026-10-28 · 패널 플레이어·가로 전체화면·분할 가사 (미배포)

상태바에서 기본적으로 오른쪽 3/8 플레이어를 열고 본문 너비를 왼쪽 5/8로 조정한다. 전체화면/최소화 버튼 및 내리기 버튼·앨범아트 당기기 제스처·뒤로 가기를 연결했다. 마지막 전체화면/패널 선택을 prefs에 저장한다. 전체화면 가로 플레이어는 표지 왼쪽, 정보/컨트롤 오른쪽이며 가사 보기에서는 일반 플레이어 왼쪽/가사 오른쪽을 사용한다. 가사를 위한 영역 확장은 저장한 전체화면 선호를 바꾸지 않는다. 배포하지 않았으며 버전은 올리지 않았다.

- assembleDebug/assembleDebugAndroidTest/lintDebug 성공 (`.local/build-player-workspace.log`).
- API36 태블릿(1280×900 mdpi) FeaturePack 20 + LowCostUi 8 = 28개 통과 (`.local/test-player-workspace-final.log`). 패널 실제 너비, 전체화면 버튼, 가사 표시, 최소화, 패널 닫기/재열기, 전체화면의 내리기 제스처/재열기와 재생 유지를 검사했다.
- 펼친 화면(900×1100 mdpi)에서 위 전환·복원 검사 1개 통과 (`.local/test-player-workspace-fold.log`).
- Android9/API28 첫 전체 검사에서 ReplayGain 태그 재생 대기 항목이 1회 실패했다. 재시작 후 해당 항목 단독 통과, 다시 재시작한 전체 검사 20개 통과 (`.local/test-player-workspace-api28-replaygain.log`, `.local/test-player-workspace-api28-clean.log`). 첫 실패의 원인은 확정하지 않았다.
- 실제 USB 볼륨 장치·물리 태블릿/폴더블은 이번에 연결하지 않았다. 가사 캡처는 직접 만든 테스트 LRC이며 실제 노래 가사를 인용하지 않았다. 에뮬레이터 화면 크기/밀도는 검사 후 복원했다.

실제 캡처: [태블릿 패널](screenshots/features-wip/tablet-player-panel.png), [태블릿 가로 전체화면](screenshots/features-wip/tablet-player-landscape.png), [태블릿 분할 가사](screenshots/features-wip/tablet-player-split-lyrics.png), [폴더블 패널](screenshots/features-wip/fold-player-panel.png), [폴더블 전체화면](screenshots/features-wip/fold-player-full.png), [폴더블 분할 가사](screenshots/features-wip/fold-player-split-lyrics.png).

---

# 2026-10-28 · 재생 상태바 3/8 비율 조정 (미배포)

추가 요청으로 큰 화면 하단 메뉴는 왼쪽 5/8, 재생 상태바는 오른쪽 3/8로 조정했다. 긴 세로 화면 분기와 기존 전환 동작은 유지한다. debug/test 빌드 성공. API36 1280×900에서 실제 너비 비율, 상태바 클릭, 플레이어 전환 애니메이션 및 재생 유지 검사 1개 통과(`.local/test-dock-three-eighths.log`). [최신 실제 캡처](screenshots/features-wip/tablet-bottom-three-eighths.png). 배포하지 않았다.

---

# 2026-10-28 · 큰 화면 하단 메뉴/재생 상태바 재배치 (미배포)

사용자 요청으로 상시 옆 플레이어 대신 하단 메뉴 왼쪽 2/3, 재생 상태바 오른쪽 1/3 배치를 적용했다. 현재 창 너비 700dp 이상 및 높이/너비 1.6 미만에서 사용하며 세로로 긴 화면은 기존 폰 배치를 사용한다. 표지·곡 정보·상태바 빈 영역에서 전체 플레이어를 열면 아래에서 위로 올라오는 전환을 사용하고 재생/다음 버튼은 직접 제어를 유지한다. 시스템 애니메이션 끄기를 존중한다. 공개 배포·버전 변경은 하지 않았다.

빌드 assembleDebug/assembleDebugAndroidTest 및 lintDebug 성공. API36 에뮬레이터에서 1280×900 태블릿의 2/3·1/3 실제 측정, 상태바 클릭 후 플레이어 진입/애니메이션 시작·종료/재생 유지, 화면 비율 분기 검사 2개 통과(`.local/test-bottom-dock.log`). 900×1100 펼친 화면에서 같은 배치/전환 검사 1개 통과(`.local/test-bottom-dock-fold.log`). 900×1800 긴 화면에서 홈/도구 검사 1개 통과 및 기존 폰 하단 배치를 캡처로 확인했다(`.local/test-bottom-dock-tall.log`). 실제 폴더블 물리 기기는 미검증이다.

최신 실제 캡처: [태블릿](screenshots/features-wip/tablet-bottom-dock.png), [펼친 폴더블](screenshots/features-wip/fold-bottom-dock.png), [긴 세로 화면](screenshots/features-wip/tall-phone-home.png). 아래의 이전 두 영역 배치 캡처/기록은 변경 이전 상태다.

---

# 2026-10-28 · 추가 기능 및 반응형 UI (미배포)

사용자 요청으로 공개 배포를 보류했다. 현재 변경은 로컬 개발 빌드이며 공개 0.18.2와 동일한 코드가 아니다. 홈 맞춤 모음, 백업/복원, Drive 오프라인 보관, ReplayGain, 다이내믹 베이스, 기기 음향 프로필, 취침 타이머, 북마크/A–B 반복, 곡별 LRC 보정/연결, 중복 후보 숨김, 홈 위젯, 태블릿/폴더블 배치를 구현했다. 상세 동작과 제한은 [FEATURES-WIP.md](FEATURES-WIP.md)를 참고한다.

검사 기록:

- API36 회귀 검사 43개 통과: FeaturePack, EQ/테마, 저비용 UI, 재생 안정성, 증분 검색. 폰에서 큰 화면 전용 항목은 조건부 생략했다. `.local/test-features-verified.log`.
- 오프라인 곡 정보 표시 보완 후 API36 기능/곡 정보/폴더 기억 27개 통과. `.local/test-features-last.log`.
- 같은 보완 후 Android 9/API28 기능 검사 19개 통과(큰 화면 항목 조건부 생략). `.local/test-features-api28-last.log`.
- 이후 작은 보조 플레이어 배치를 추가하고 debug/test 빌드 및 lintDebug 성공. 최종 접힘 배치 1개 및 폰 화면/위젯 2개가 통과했다. `.local/test-features-fold-last.log`, `.local/test-features-phone-last.log`.
- 실제 재생 파이프라인의 ReplayGain 태그, 백그라운드 타이머/A–B 반복, 위젯 재생 제어를 검사했다. Drive 저장/취소/오프라인 탐색은 로컬 모의 HTTP로 확인했다. 실제 Google 계정의 다운로드, NW-ZX507/S25 및 물리 폴더블은 이번에 검사하지 않았다.

실제 앱 캡처: [폰 홈](screenshots/features-wip/phone-home.png), [음향 효과](screenshots/features-wip/phone-effects.png), [태블릿 홈](screenshots/features-wip/tablet-home.png), [책 형태](screenshots/features-wip/fold-book.png), [테이블탑](screenshots/features-wip/fold-tabletop.png). 테스트용 음원·직접 만든 표지를 사용했다.

---

# 2026-10-28 · AURA 0.18.2 서명 전환 공개 배포 확인

사용자가 배포를 승인하여 https://github.com/Mobil0010/AURA/releases/tag/v0.18.2 에 최신 APK·고정 전환 APK·두 피드·체크섬·대응 소스 ZIP 총 6개 파일을 게시했다. 기존 공개 릴리스는 변경하지 않았다. 공개 latest/download/update.json은 기존 앱용 0.18.1/code23, update-v2.json은 최신 0.18.2/code24를 반환하며 두 JSON을 로컬 배포 정보 전체와 대조했다.

두 공개 APK를 다시 내려받아 크기·SHA256·서명을 확인했다. 전환용 APK 10,249,020바이트 / SHA256 `666757d23dc0471c14de503c1a1c00a616aefc18512802c7991e6424182074c6` / 기존 인증서 일치. 최신 APK 10,253,116바이트 / SHA256 `354ae54e186b4aaa2b3e53563ff9a09e17fea949de80ead2d3a69301139edec9` / 새 인증서 및 v3.1 교체 증명 검증 성공. 실제 무삭제 업데이트 테스트에 사용한 APK와 일치한다. 소스 ZIP 558개 파일의 무결성·네이티브 라이선스/빌드 안내 포함 및 개인 키·암호·캐시·SDK·개인 콘솔 캡처 제외를 확인했다.

기존 앱에서는 0.18.1을 설치한 뒤 앱을 다시 열어 업데이트를 다시 확인하면 0.18.2를 설치한다. 앱 삭제/데이터 초기화가 필요하지 않다. 자동 검사의 간격 때문에 두 번째 단계는 수동 업데이트 확인을 안내한다. 무삭제/데이터 보존/이후 버전 검증은 아래 API28/36 에뮬레이터 기록을 참고한다. 실제 NW-ZX507/S25와 Google 계정 로그인/Drive 스트리밍은 이번 공개 후 별도로 실행하지 않았다.

이 공개 후 검증 기록은 로컬에 추가했으며 게시한 소스 ZIP을 덮어쓰지 않았다. 아래 미배포 표기는 당시 검증 시점의 기록이다.

---

# 2026-10-28 · 서명 전환 및 무삭제 업데이트 검증 (미배포)

전용 배포 키와 Android 13+ v3.1 서명 교체 증명을 적용했다. Android 8–12에는 같은 APK의 기존 서명을 유지한다. Android 9(API28 ARM64)와 Android16(API36 ARM64)에서 공개 0.18.0부터 다음 버전까지 앱 삭제 없이 실제 설치하고, UID·설정·폴더 경로·앱 내부 파일 보존 및 실제 WAV 재생을 검사했다. 현대 환경 전환 8개, Android9 전환 5개, 초기 데이터 준비 2개, 각 환경 재생 1개씩 통과했다. 최신 환경에서 기존 앱 내 검사기의 교체 서명 거절을 재현해 전환용 업데이트/두 피드 경로를 준비했고, 무관한 서명·역방향 교체는 거절했다. 빌드/lintRelease 성공, v2/v3/v3.1 및 API28/33–36 서명 검증 성공, release APK는 debuggable이 아니다. 실제 S25/NW-ZX507은 미검증이다.

신규 Google 로그인 인증서 등록도 사용자 승인 후 완료했으며 기존 항목/권한은 유지했다. 실제 계정 로그인 검사는 하지 않았다. 키는 .local/signing 안에만 저장하고 개인 키/암호/SDK는 소스 ZIP에 포함하지 않는다. 두 피드·두 APK 해시와 소스 ZIP 제외 규칙을 검증했다. Google Cloud 콘솔 캡처는 개인 설정 화면이라 .local에만 보관하고 소스 ZIP에는 넣지 않았다. 공개 버전과 피드는 0.18.0/code22 유지. 로컬 전환용 0.18.1/code23, 새 서명 후보 0.18.2/code24, code25는 이후 업데이트 검증용으로만 만들었고 배포하지 않았다. [자세한 경로·검사·키 보관](SIGNING-MIGRATION.md).

---

# 2026-10-28 · AURA 0.18.0 공개 배포 확인

https://github.com/Mobil0010/AURA/releases/tag/v0.18.0 에 APK, update.json, SHA256SUMS.txt, 대응 소스 ZIP 게시 완료. 공개 latest/download/update.json을 다시 받아 로컬 배포 정보 전체와 일치하는 0.18.0/code22 확인. 공개 APK 재다운로드 결과 13,770,952바이트, SHA-256 `adad38084db38f8dcd1e30500da1ffe9317cb74d510d23ea0f114fed7379afad` 일치. 공개 APK apksigner 검증 및 기존 서명 인증서 일치. 소스 ZIP 549개 파일, 빌드 안내·네이티브 소스/라이선스 포함과 로컬 SDK·캐시·개인 키·인증 파일 제외 확인. 기존 공개 릴리스를 덮어쓰지 않았다. 이 공개 후 검증 기록만 로컬에 추가했으며 이미 게시한 소스 ZIP을 변경하지 않았다.

---

# 2026-10-28 · AURA 0.18.0 / code22 배포 전 검증

보류했던 변경을 모두 포함했다. 상세 변경은 RELEASE-0.18.0.md. 앱/테스트 APK 빌드와 lint 성공(오류 0, 경고 48). 가사·노래방 타이밍·원격 HTTP 기본 검사 40개 통과. 배포 APK instrumentation 58개 중 57개 통과, USB 선택창 검사는 초기 화면 생성 전 조회로 실패했다. 검사에 화면 생성 대기를 추가하고 앱 APK 변경 없이 USB/폴더/가사 6개 전부 재통과했다. 따라서 58개 전체를 한 실행에서 통과했다고 주장하지 않는다. 로그: `.local/test-release-0.18.0.log`, `.local/test-release-0.18.0-usb-final.log`, `.local/core-release-0.18.0.log`.

APK 실제 versionName 0.18.0, versionCode 22. apksigner 검증 성공, 기존 서명 SHA-256 `d41fcccff7efb833e448e27aaac0b176454ee70772c358b678edb9a7ea5db89a` 일치. API36 ARM64 CPU2/RAM2GB 에뮬레이터로 검증했으며 실제 USB DAC/NW-ZX507 및 개인 Drive 계정 검증은 하지 않았다. 아래 과거 미배포 기록의 보류 상태는 해당 시점 기록이며 이번 0.18.0에 통합한다.

---

# 2026-10-28 · 플레이어 위치 고정 수정 (미배포)

최신 요청에 따라 표지 원본 비율에 맞춰 플레이어 레이아웃 높이를 줄이던 동작을 제거했다. 기존 정사각형 표지 영역의 가로·세로 크기를 유지하고, 내부 이미지에만 비율 보존 맞춤을 적용하여 중앙에 배치한다. 제목·음질·재생 컨트롤 위치는 표지 비율과 무관하게 유지한다. 이전 가변 영역 기록보다 이 변경을 우선한다.

앱/테스트 빌드와 lint 통과 (`.local/build-fixed-artwork.log`). 실제 WAV 재생으로 가로·세로 원본의 내부 이미지 비율 및 표지 영역 크기·음질 표시 화면 좌표 일치를 검사했다. EmulatorEnvironmentTest, SongInfoDesignTest의 해당 검사, RoutingAndUiTest 총 11개 통과 (`.local/test-fixed-artwork.log`). 두 실제 화면을 `screenshots/fixed-artwork-wip/landscape.png`, `portrait.png`에 저장하고 확인했다. 배포 보류, 버전 0.17.0/code21 유지.

---

# 2026-10-28 · 가변 비율 앨범아트·간결한 음질 표시 (미배포)

앨범아트는 기존 최대 가로·세로 범위 안에서 원본 종횡비를 보존한다. 플레이어는 실제 표지 영역 크기도 변경하고, 목록은 행 높이를 유지하며 이미지 자체만 비율대로 표시한다. 원본 샘플레이트와 출력 샘플레이트가 같으면 원본 비트 깊이·kHz만 표시하고, 다르면 입력 → 실제 출력과 리샘플러를 한 줄로 표시한다. 이 표시는 압축 파일의 kbps가 아닌 PCM 형식이다. 누르면 상세 출력 정보가 열린다.

앱/테스트 빌드 및 lint 성공. EmulatorEnvironmentTest, SongInfoDesignTest, RoutingAndUiTest, ArtworkQualityTest 총 21개 통과 (`.local/test-aspect-compact.log`). 캡처용 원본을 가로/세로 사진으로 변경한 뒤 해당 테스트 1개 재통과 (`.local/test-aspect-final.log`). 실제 WAV 재생, SoX 44.1 → 192 kHz, 다크/라이트 화면과 비율 보존 검증. 실제 NW-ZX507/DAC 미검증. 배포 보류, 버전 0.17.0/code21 유지.

캡처: `screenshots/aspect-compact-wip/landscape.png`, `portrait.png`, `resampled-dark.png`.

---

# 2026-10-28 · USB 엔진·폴더 표지·가사 초기화 미배포 검증

사용자의 배포 보류 요청에 따라 USB 미연결 선택 차단, 이전 엔진 복귀/즉시 전환, 첫 곡/폴더 사진 기반 표지와 비동기 캐시 최적화, 새 곡의 전체화면 가사 스크롤 초기화를 구현하고 실제 앱을 캡처했다. **배포하지 않았으며 버전은 0.17.0/code21 유지**다. 빌드/lint 성공(오류 0 / 경고 48), 회귀 검사 36개 통과 후 발견된 캐시 경로를 수정하고 최종 APK에서 관련 24개를 통과했다. [동작·검사·캡처](UI-USB-FOLDER-LYRICS-WIP.md). 실제 USB DAC/NW-ZX507은 미검증이다.

---

# 2026-10-27 · AURA 0.17.0 배포 검증

## 공개 배포 확인

[GitHub v0.17.0](https://github.com/Mobil0010/AURA/releases/tag/v0.17.0)에 APK·update.json·SHA256SUMS.txt·대응 소스 ZIP 네 파일을 게시했다. 앱의 공개 latest/download/update.json을 다시 내려받아 로컬 배포 정보 전체와 일치하는 0.17.0/code21을 확인했다. 공개 APK 재다운로드 결과 13,770,952바이트 및 SHA-256 `cf081ea53ebfd5ea042df4360ee00de680ac80c80bfc5ac1c088344444756887` 일치, 기존 인증서로 apksigner 검증 성공을 확인했다. 소스 ZIP 535개 파일·11,174,758바이트의 대응 소스/빌드 안내/네이티브 라이선스 포함과 개인 키·로컬 SDK·캐시 제외를 검사했다. 공개 후 기록만 로컬에 추가했으며 기존 공개 파일을 덮어쓰지 않았다.

- 사용자 배포 승인에 따라 앨범아트/곡 정보/입출력 형식 표시/폴더 및 DAC 볼륨 기억 기능을 0.17.0/code21로 준비했다. 직전 공개 버전은 0.16.0/code20이다. [변경 내역](RELEASE-0.17.0.md).
- assembleDebug·assembleDebugAndroidTest·lintDebug 성공. lint 오류 0 / 경고 48.
- 실제 배포 APK에서 **31개 통과**, 78.654초: 앨범아트 5, Drive 폴더 표지 6, 곡 정보/실제 SoX 표시/양 테마 5, 폴더 기억 2, DAC 하드웨어 볼륨 기억 2, 재생 안정성 7, 증분 검색 3, 테스트 환경 1. API36 ARM64 / CPU2·RAM2GB 에뮬레이터를 사용했다.
- 앞선 구현 단계에서 관련 48개 회귀 검사와 UI 변경 후 9개 재검사를 통과했다. 그 뒤 캐스트 문구 한 곳, 버전 및 배포 문서만 바꿨다. 모든 48개를 최종 버전에서 다시 실행했다고 주장하지 않는다.
- APK 13,770,952바이트, SHA-256 `cf081ea53ebfd5ea042df4360ee00de680ac80c80bfc5ac1c088344444756887`. aapt로 0.17.0/code21, minSdk26, arm64-v8a/armeabi-v7a/x86/x86_64를 확인했다. 이전 APK에서 제거된 ZIP 항목이 없으며 네이티브 라이브러리 크기도 동일하다.
- apksigner 검증 성공, 기존 인증서 SHA-256 `d41fcccff7efb833e448e27aaac0b176454ee70772c358b678edb9a7ea5db89a` 유지. 이전 설치 위 업데이트 설치 성공.
- NW-ZX507 실기기·실제 USB DAC·캐스트 수신 기기의 이번 동작은 미검증이다. DAC 복원은 USB 제어 모형으로 확인했다. 일련번호가 없는 같은 모델은 기억값을 공유할 수 있다. 동작/제한/화면은 [구현 기록](UI-ARTWORK-INFO-WIP.md)에 정리했다.

---

# 2026-10-27 · 앨범아트·곡 정보·기억 기능 미배포 검증

사용자의 배포 보류 지시에 따라 고해상도 표지, 곡 메뉴/정보, 실제 앱 입출력 음질 표시, 마지막 폴더 경로 및 DAC별 하드웨어 볼륨 기억을 구현하고 캡처했다. **배포하지 않았고 버전은 0.16.0/code20 유지**다. 관련 48개 검사 통과 후 상세 UI 변경 빌드에서 9개 재검사 통과, 마지막 캐스트 문구 수정 후 빌드/lint 성공(오류 0 / 경고 48). 실제 NW-ZX507/USB DAC는 미검증이다. [구현·검증·실제 화면](UI-ARTWORK-INFO-WIP.md).

---

# 2026-10-27 · AURA 0.16.0 배포 검증

## 공개 배포 확인

2026-10-27 [GitHub v0.16.0](https://github.com/Mobil0010/AURA/releases/tag/v0.16.0)에 APK·update.json·SHA256SUMS.txt·대응 소스 ZIP 네 파일을 게시했다. 앱의 `releases/latest/download/update.json`을 다시 내려받아 로컬 배포 정보 전체와 일치하는 0.16.0/code20을 확인했다. 공개 APK를 다시 내려받아 13,754,568바이트 및 SHA-256 `e7885289c80d4a101a5b138a64aedb7e17f9099c78ce1bc95ff09acad5678479` 일치와 기존 인증서의 apksigner 검증 성공을 확인했다. 소스 ZIP 519개 파일·10,239,506바이트에 대응 소스/빌드 안내/네이티브 라이선스가 포함됐고 개인 키·인증 파일·로컬 SDK·캐시·개인 설정은 제외됐음을 검사했다. 이 공개 후 기록은 로컬 문서에만 추가했으며 이미 공개한 배포 파일을 덮어쓰지 않았다.

- 사용자 배포 승인에 따라 통계 구현을 0.16.0/code20으로 올렸다. [변경 내역](RELEASE-0.16.0.md). 직전 공개 버전은 0.15.0/code19이다.
- assembleDebug·assembleDebugAndroidTest·lintDebug 성공. lint 오류 0 / 경고 47.
- 배포할 실제 APK에서 통계 7개, 재생 안정성 7개, 증분 검색 3개, 테스트 환경 1개 **총 18개 통과**, 72.303초. API36 ARM64 / CPU2·RAM2GB 에뮬레이터를 사용했다. 실제 WAV 반복 재생, 일시정지/탐색/기록 끄기 제외, 정확한 시간·최초/최근 시각·기간 경계·장르·다크/라이트를 검사했다.
- 이전 구현 단계의 관련 48개 회귀 검사를 통과한 소스에서 앱 버전과 배포 문서만 변경했다. 48개 전체를 배포 버전으로 다시 실행했다고 주장하지 않는다.
- aapt로 실제 APK 0.16.0/code20, minSdk26, arm64-v8a/armeabi-v7a/x86/x86_64 네 ABI를 확인했다. 이전 공개 APK에서 제거된 ZIP 항목이 없고 네이티브 라이브러리의 비압축 크기도 동일하다.
- APK 13,754,568바이트. SHA-256 `e7885289c80d4a101a5b138a64aedb7e17f9099c78ce1bc95ff09acad5678479`.
- apksigner 검증 성공(v2), 기존 인증서 SHA-256 `d41fcccff7efb833e448e27aaac0b176454ee70772c358b678edb9a7ea5db89a` 유지. 기존 설치 위 업데이트 설치 성공.
- NW-ZX507 실기기와 실제 외부 Cast/DLNA 수신 기기의 통계 정확도/성능은 미검증이다. 기록 방식·제약 및 실제 화면은 [통계 구현 기록](UI-STATISTICS-WIP.md)에 있다.

---

# 2026-10-27 · 청취 통계 미배포 구현 검증

사용자의 최신 지시에 따라 통계 기능을 구현하고 실제 앱을 캡처했으며 **배포하지 않았다**. 로컬 앱 버전은 0.15.0/code19 유지이며 공개 0.15.0 APK와는 다른 미배포 빌드다. 빌드/검사/기존 서명 검증 성공, 관련 48개 검사 통과 후 상세 시간 변경 빌드의 17개, 마지막 수정 후 통계 7개를 별도로 통과했다. lint 오류 0 / 경고 47. 구현 범위·기록 기준·검증 제한·다크/라이트 캡처는 [통계 구현 기록](UI-STATISTICS-WIP.md)에 정리했다. NW-ZX507 실기기는 검증하지 않았다.

---

# 2026-10-27 · AURA 0.15.0 배포 검증

- Drive 캐스트 중계, 누락된 폴더 표지 보완, 로컬/Drive 선택 폴더 재검색, 폴더 목록 전체 재생, 파일명 번호·자연 정렬, 공통 선택/확인창 테마를 포함한다. [변경 내역](RELEASE-0.15.0.md).
- assembleDebug·assembleDebugAndroidTest·lintDebug 성공(오류 0 / 경고 46). 실제 APK versionName 0.15.0 / code19 / minSdk26 / 네 ABI 확인. 직전 공개 피드는 0.14.0/code18이다.
- API 36 ARM64 / CPU 2코어·RAM 2GB 에뮬레이터에서 관련 **63개 통과**, 190.538초. Drive 중계/범위·선택 폴더/정렬 5개, 중계 대기열/백그라운드 2개, 폴더 표지 5개, 선택창/폴더 재생 2개, Drive 기존 15개, 증분 확인 3개, 검색 정책 9개, 저사양 UI 8개, 폴더/가사/동작 7개, 끝까지 스크롤 3개, 기존 HTTP 파일/SoX 캐스트/기기 선택 3개, 환경 1개를 포함한다.
- 이후 캐스트 종료 시 수신 기기 Stop 요청, 대기열 곡 매핑 O(N) 및 Wi-Fi 주소 재사용만 추가한 최종 APK에서 영향받는 중계/범위/부분 검색/정렬 5개·백그라운드/정지 2개·선택창/폴더 재생/실제 폴더 검색 서비스 연결 3개를 다시 실행해 **10개 통과**, 22.575초. 과거 63개 전체를 마지막 수정 후 다시 실행했다고 주장하지 않는다.
- 가짜 Drive의 64MiB 음원을 HEAD·시작/중간/끝부분 Range로 요청해 지정된 바이트와 길이·MIME·Content-Range를 대조했다. HEAD/OPTIONS/잘못된 범위는 오디오를 다운로드하지 않으며, 범위 데이터 전송은 제한된 HTTP 창으로 이루어진다. 링크/수신 응답에 토큰이 없고, 계정 접근 취소 시 거부되며, 중계 종료 후 이전 URL이 무효인지 검사했다.
- 같은 제목의 여러 Drive 곡에 서로 다른 중계 ID와 올바른 MIME이 생성되고, Cast 전달 창이 제한되며 전체 대기열은 유지되는 것을 검사했다. 모의 DLNA 수신기에 실제 SetAVTransportURI/Play를 보내고 폰을 백그라운드로 옮긴 뒤 중계 URL에서 RIFF 데이터를 읽었다. 중계 알림이 유지되고 알림 종료 시 수신기 Stop 요청·서버 닫힘·알림 해제를 확인했다.
- 실제 Chromecast/Cast 스피커나 사용자의 Google 계정으로 재생을 검증하지는 않았다. Cast payload는 SDK 객체로 검사했고, LAN/Drive HTTP 및 DLNA 수신기는 로컬 모의 서버를 사용했다. 실제 수신 기기의 코덱/샘플레이트 호환성과 NW-ZX507 실기기는 미검증이다.
- 로컬 부분 검색에서 선택한 하위 폴더만 음원 열기 횟수가 증가하고 이웃 폴더의 열기 횟수/곡이 유지되는지 검사했다. Drive 부분 검색은 해당 폴더만 조회해 삭제 범위와 이웃 곡을 보존한다. 조회 실패 시 이전 인덱스 전체가 유지되는 것도 확인했다. 처음의 실패 응답 테스트는 가짜 서버가 없는 폴더를 루트로 잘못 처리했으므로 테스트 서버에 명시적인 실패 응답을 넣어 수정했다.
- 기존 인덱스의 누락 표지를 시작 확인에서 보완하면서 오디오 메타데이터 호출 수가 늘지 않는지 확인했다. 유효한 내장 표지 보존, 폴더 표지 공유·변경 갱신, 손상된 우선 사진에서 다음 후보 선택, 예전 4MiB보다 큰 사진 메타데이터의 허용 및 선택적 사진 실패 시 태그 보존을 검사했다.
- 파일명 2/10의 트랙 보조 번호 및 제목 자연 정렬, 실제 태그 번호 우선 적용, 폴더 버튼의 하위 폴더 포함·정렬된 재생 대기열 생성과 기존 폴더 위치 유지를 확인했다. 공통 선택창의 둥근 투명 모서리/색상·선택 위치·항목 클릭 보존을 두 테마로 검사하고 캡처했다: [다크](screenshots/0150/dialog-choice-dark.png) / [라이트](screenshots/0150/dialog-choice-light.png). 안드로이드 권한 창은 변경하지 않았다.
- 최종 APK 14,025,674바이트, SHA-256 `ce3aaa4fcd1b6c9e84b9de5adc437dba6cad0c78246d0afd0a4f0d97dc1e2e45`. apksigner 검증 성공, 기존 인증서 SHA-256 `d41fcccff7efb833e448e27aaac0b176454ee70772c358b678edb9a7ea5db89a` 유지.
- 공식 Cast [지원 형식](https://developers.google.com/cast/docs/media)과 [Android 대기열](https://developers.google.com/cast/docs/android_sender/queueing)을 확인했다. Drive는 원본 구간 중계라 EQ·SoX를 적용하지 않고, 로컬의 기존 WAV 렌더 경로는 유지한다. 음원 전체를 디스크에 복사하지 않는다. 폴더 사진만 제한된 임시 파일과 썸네일로 저장한다.

## 0.15.0 공개 배포 확인

2026-10-27 [GitHub v0.15.0](https://github.com/Mobil0010/AURA/releases/tag/v0.15.0)에 APK·update.json·SHA256SUMS.txt·대응 소스 ZIP 네 파일을 게시했다. 앱의 latest/download/update.json을 다시 내려받아 로컬 배포 정보 전체와 일치하는 0.15.0/code19를 확인했다. 공개 APK 재다운로드 결과 14,025,674바이트 및 SHA-256 `ce3aaa4fcd1b6c9e84b9de5adc437dba6cad0c78246d0afd0a4f0d97dc1e2e45` 일치, apksigner 검증 성공과 기존 서명 인증서 일치를 확인했다. 소스 ZIP 501개 파일·8,843,168바이트의 빌드 안내·네이티브 소스/라이선스 포함 및 개인 키·로컬 SDK·캐시·개인 설정 제외를 검사했다. 이 공개 후 기록만 로컬에 추가했으며 기존 공개 파일은 덮어쓰지 않았다.

# 2026-10-27 · AURA 0.14.0 배포 검증

- 저사양 목록/가사 갱신 최적화, 알림 전용 AURA 아이콘, 글자 영역 가사 탐색과 전체화면 재생 위치 바, 출력 기기 중복 정리, 디스크/트랙 번호 기본 정렬과 정렬 설정을 포함한다. 실제 동작과 한계: [RELEASE-0.14.0.md](RELEASE-0.14.0.md).
- 최종 assembleDebug·assembleDebugAndroidTest·lintDebug 성공(오류 0 / 경고 45). 실제 APK versionName 0.14.0 / versionCode 18 / minSdk 26 / 네 ABI 확인. 이전 공개 피드 0.13.0/code17보다 높다.
- 최종 APK를 API 36 ARM64 / CPU 2코어·RAM 2GB 에뮬레이터에서 실행해 **31개 통과**, 196.717초. 새 저사양 UI/정렬/터치/알림 검사 8개, 플레이리스트/가사 4개, 홈/네 라이브러리 탭 1개, 폴더/가사/동작 7개, 양 끝 스크롤 3개, 재생 안정성 7개, 환경 1개를 포함한다.
- 1,500줄 중 현재 줄 변경 시 TextView 스타일 갱신이 이전/현재의 2회, 같은 줄 진행 시 0회인지 확인했다. 폴더/곡 유형별 홀더 재바인딩 때 자식 뷰가 재사용되는지 검사했다. 저사양 설정과 실행 경로를 검증한 것이며 NW-ZX507의 실제 프레임률·발열·메모리 수치는 측정하지 않았다.
- 실제 글자 터치만 클릭되고 위아래 여백·오른쪽 빈 곳·단어 사이 공백은 클릭되지 않는지, 실제 재생 알림의 smallIcon 리소스가 투명 AURA glyph인지 확인했다. 재생 중 가사 프레임 루프 시작과 Activity 중지 시 해제, 재생 바를 잡은 채 3.3초 후에도 컨트롤이 보이며 놓으면 실제 재생 위치가 바뀌는지 확인했다.
- 같은 모델명의 수화부/일반 스피커/보호 스피커에서 일반 스피커만 보이는지, 같은 주소의 A2DP/SCO 묶음과 현재 선택 보존, 이름만 같은 서로 다른 USB 주소와 주소 없는 별도 기기의 보존을 검사했다.
- 실제 FLAC Vorbis comment의 TRACKNUMBER=02/12, DISCNUMBER=2/3을 로컬 스캐너로 읽어 번호 2/2를 얻었다. 번호의 JSON·폴더 경로 복사·MediaMetadata 유지, 숫자 2/10과 디스크 우선 정렬, 내림차순과 번호 없음 후순위, 제목/저장 순서 및 앨범 그룹 정렬을 검사했다. 실제 Drive 태그 읽기에는 같은 메타데이터 키/파서를 적용했으며 사용자 계정에서는 검증하지 않았다.
- 앞선 APK에서 Drive 15개·검색 정책 9개·새 곡 전용 검색 3개도 통과했다. 그 실행 전체 55개 중 새 검사용 곡을 라이브러리에 등록하지 않은 경우와 미부착 뷰 클릭 2개에 실패했으므로 전체 통과로 기록하지 않는다. 픽스처를 실제 목록/부착 뷰로 고친 뒤 최종 31개를 통과했다. 이후 앱의 고정 목록 크기/행 캐시 및 설정 안내 변경을 반영한 최종 APK에서 목록/가사/재생 영향을 다시 확인했다.
- 실제 APK 13,992,141바이트, SHA-256 `100cdaec64bfbebcf60ed38dffec0d0f9f63e67eb20c1c32c13e2abfb1873af5`. apksigner 검증 성공, 기존 인증서 SHA-256 `d41fcccff7efb833e448e27aaac0b176454ee70772c358b678edb9a7ea5db89a` 유지.
- 실제 [다크 가사 화면](screenshots/0140/lyrics-controls-dark.png) / [라이트 가사 화면](screenshots/0140/lyrics-controls-light.png). 캡처의 3초 음원과 가사는 자체 검사 데이터이며 앱에 샘플 라이브러리를 추가하지 않는다.
- 알림 아이콘 연결은 설치된 Media3 1.9.4 API 및 [공식 setSmallIcon 문서](https://developer.android.com/reference/androidx/media3/session/DefaultMediaNotificationProvider#setSmallIcon(int))를 확인했다. 오디오 엔진·USB 네이티브 코드는 변경하지 않았다. 실제 NW-ZX507·S25·USB DAC 실기기는 미검증이다.

## 0.14.0 공개 배포 확인

2026-10-27 [GitHub v0.14.0](https://github.com/Mobil0010/AURA/releases/tag/v0.14.0)에 APK·update.json·SHA256SUMS.txt·대응 소스 ZIP 네 파일을 게시했다. 앱의 latest/download/update.json을 다시 받아 로컬 배포 정보 전체와 일치하는 0.14.0/code18을 확인했다. 공개 APK 재다운로드 결과 13,992,141바이트, SHA-256 `100cdaec64bfbebcf60ed38dffec0d0f9f63e67eb20c1c32c13e2abfb1873af5`와 기존 인증서 일치 및 apksigner 검증 성공을 확인했다. 소스 ZIP 490개 파일·8,575,091바이트에 빌드 안내·libusb/soxr 소스 및 라이선스를 포함하고 개인 키·로컬 SDK·캐시·개인 설정을 제외한 것을 검사했다. 이 공개 후 기록만 로컬에 추가했으며 게시한 소스 ZIP/기존 릴리스는 덮어쓰지 않았다.

# 2026-10-27 · AURA 0.13.0 배포 검증

- 기존 0.12.0/code16 공개 피드를 확인한 후 0.13.0/code17로 올렸다. 보류했던 홈·라이브러리·설정·EQ·플레이리스트·전체화면 가사와 새 곡 전용 시작 검색을 함께 포함한다.
- 최종 버전 APK의 assembleDebug·assembleDebugAndroidTest·lintDebug 성공(오류 0 / 경고 41).
- API 36 ARM64 / CPU 2코어·RAM 2GB 에뮬레이터에서 환경·플레이리스트/가사·증분 검색·홈/네 라이브러리 탭·설정/EQ·재생 안정성 **19개 통과**, 112.375초. 다크/라이트 화면, 단어 강조/탐색/컨트롤 숨김, 새 파일만 읽기, 세로 EQ 조절과 응답 계산, USB 볼륨 모의 검사 및 연속 탐색/곡 전환/백그라운드 재생을 확인했다. 실제 USB DAC나 NW-ZX507 검증은 수행하지 않았다.
- 이전 코드 검토에서 통과한 코어 11개·단어 타이밍 8개·리모컨 HTTP 21개와 관련 회귀 53개 기록은 아래 WIP 절에 있다. 모든 과거 검사를 이번 버전 번호 변경 후 재실행했다고 주장하지 않는다. 플레이리스트 대화상자의 실제 생성/이름 변경/삭제 자동 조작은 검증 범위에 포함하지 않는다.
- 실제 APK 13,703,536바이트, SHA-256 `8c311a4f802f3262e843c973c0954fac4459fe113ef33bd11d7b2d42de6dc1ed`. apksigner 검증 성공, 기존 인증서 SHA-256 `d41fcccff7efb833e448e27aaac0b176454ee70772c358b678edb9a7ea5db89a` 유지. minSdk 26, 네 ABI, 실제 버전 0.13.0/code17 확인.

## 0.13.0 공개 배포 확인

2026-10-27 [GitHub v0.13.0](https://github.com/Mobil0010/AURA/releases/tag/v0.13.0)에 APK·update.json·SHA256SUMS.txt·대응 소스 ZIP 네 파일을 게시했다. latest/download/update.json을 다시 내려받아 로컬 0.13.0/code17 배포 정보 전체 일치를 확인했다. 공개 APK를 다시 내려받아 13,703,536바이트, SHA-256 `8c311a4f802f3262e843c973c0954fac4459fe113ef33bd11d7b2d42de6dc1ed`, apksigner 검증 성공과 기존 인증서 일치를 확인했다. 소스 ZIP은 482개 파일·8,321,021바이트로 빌드 안내·libusb/soxr 라이선스와 대응 소스를 포함하고 개인 키·로컬 SDK·캐시·설정을 제외한 것을 검사했다. 아래 ‘배포 보류’ 문서들은 당시의 검토 이력이며 변경은 이번 0.13.0에 포함된다. 이 공개 후 기록만 로컬에 추가했고 게시된 소스 ZIP/릴리스 파일은 덮어쓰지 않았다.

# 2026-10-27 · 플레이리스트·전체화면 가사·증분 확인 (배포 보류)

플레이리스트와 전체화면 가사를 공통 스타일로 다듬었다. 단어 강조 스위치와 단어 시간/줄 기반 추정 표시, 스크롤 후 약 3초 뒤 부드럽게 숨는 곡 정보·재생 컨트롤을 추가했다. 시작 시 검색은 기존 태그·표지를 그대로 두고 새 곡만 분석한다. Drive는 변경 피드와 폴더 캐시를 사용하고, 로컬은 문서 ID 비교 후 새 음원만 연다. 정상 확인된 삭제 제거와 실패 시 인덱스 보호를 유지한다.

assembleDebug·assembleDebugAndroidTest·lintDebug 성공(오류 0 / 경고 41). 코어 11개·타이밍 8개·리모컨 21개 통과. API 36 ARM64 / CPU 2코어·RAM 2GB 에뮬레이터에서 첫 새 기능·환경 8개(37.207초), 기존 관련 회귀 53개(168.993초) 통과. 이후 작은 표지 대체 문구 변경을 반영한 최종 앱에서 증분 검색 3개·출력/표지 UI 9개를 재확인했고 최종 플레이리스트·가사 4개(33.612초)를 통과했다. 중간 시스템 대화상자 입력 검사 실패와 테스트 클래스 지정 오류는 전체 통과로 세지 않았다. 최종 검사는 대화상자 조작을 제외하고 카드 상세 이동·복귀와 보존, 강조·컨트롤·타이머를 검사한다. 실제 NW-ZX507·실제 Drive 데이터 측정은 수행하지 않았다.

다크·라이트 네이티브 캡처 6장 및 상세 한계: [UI-PLAYLIST-LYRICS-SCAN-WIP.md](UI-PLAYLIST-LYRICS-SCAN-WIP.md). 로컬 버전 0.12.0/code16 유지, GitHub 게시·dist·공개 update.json 변경 없음.

# 2026-10-27 · 설정·EQ UI 로컬 검토 (배포 보류)

설정을 기능별 카드와 관리 페이지로 정리하고, EQ 그래프·조절부 통합 카드와 세로 7밴드, 파라메트릭 편집, 자동 프리앰프 카드에 공통 스타일을 적용했다. 같은 밴드 드래그 중 편집 뷰를 재사용하고 그래프 응답을 캐시한다. 오디오·SoX 컨트롤도 맞췄다. assembleDebug·assembleDebugAndroidTest·lintDebug 성공(오류 0 / 경고 43). API 36 ARM64 / CPU 2코어·RAM 2GB 설정에서 관련 16개 통과, 51.587초(환경 검사 1개 포함). 실제 손가락/접근성 슬라이더 조절, 응답 계산 대조, 기존 자동 보정과 설정 보존, 두 테마의 실제 화면 10개와 관리 화면 이동을 확인했다. 로컬 0.12.0/code16이며 새 릴리스·dist·공개 update.json 변경은 하지 않았다. 상세: [UI-SETTINGS-EQ-WIP.md](UI-SETTINGS-EQ-WIP.md).

# 2026-10-27 · 홈·라이브러리 UI 로컬 검토 (배포 보류)

사용자 요청에 따라 홈·라이브러리 네 탭과 미니 플레이어를 새 플레이어의 공통 스타일로 정리했다. assembleDebug·assembleDebugAndroidTest·lintDebug 성공(오류 0 / 경고 43). API 36 ARM64 / CPU 2코어·RAM 2GB 설정에서 관련 32개 검사 통과, 165.154초(환경 검사 1개 포함). 다크·라이트의 실제 화면 10개, 정사각형 표지, 앨범/곡 뷰 재사용, 탭 바로가기·계층 폴더·검색·상단 접힘·양 끝 스크롤을 확인했다. 버전은 로컬 0.12.0/code16이며 새 릴리스나 dist 파일, 공개 update.json을 변경하지 않았다. 검토 자료: [UI-HOME-LIBRARY-WIP.md](UI-HOME-LIBRARY-WIP.md). 실제 NW-ZX507에서 측정하지 않았다.

# 2026-10-27 · AURA 0.12.0 검증

- 최종 플레이어 변경을 포함한 `assembleDebug assembleDebugAndroidTest lintDebug` 성공. JDK 17 / SDK 36 / 네 CPU ABI, minSdk 26. Lint 오류 0 / 경고 43(반복 모드 switch의 default 처리에 대한 IntDef 경고 포함).
- Android 16 ARM64 에뮬레이터, CPU 2코어·RAM 2GB 설정에서 실행했다. 선행 APK의 실제 코덱·PCM·Drive·검색·재생 안정성 회귀를 실행했다. 시스템 UI 콜드 부팅 ANR 창이 손가락 입력을 가로막아 해당 드래그 검사에 실패했고, 이 창을 닫고 화면·제스처·업데이트·USB 관련 **45개 통과**, 184.741초. 이 환경 문제로 중단한 전체 실행을 ‘전체 통과’라고 기록하지 않는다.
- 이후 플레이어 구성을 다시 변경한 최종 APK에서 재생 배치 1개·EQ/테마/행 재사용 6개·폴더/가사/동작 7개·출력/표지 UI 9개를 검사했다. 21개가 통과했고 두 항목은 이전 ‘가사 두 줄’ 및 ‘큰 화면에서도 반드시 스크롤 발생’ 조건이었다. 새 한 줄 가사 요구에 맞추고, 작은 화면을 강제해 스크롤 복귀를 검사하도록 픽스처를 바꿨다. 최종 APK는 변경하지 않은 채 폴더/가사/동작 7개와 실제 표지/두 테마 플레이어 1개를 다시 실행해 **8개 통과**, 47.151초. 최종 APK에 대한 고유 실행 검사 범위는 24개다. 모든 이전 검사를 최종 APK에서 다시 실행했다고 주장하지 않는다.
- 전체 필터가 겹친 응답의 피크를 촘촘한 독립 주파수 계산과 대조했다. +12dB 저음 EQ에 0.98 크기 사인파를 입력해 자동 보정 시 클리핑하지 않는지, 끄면 클리핑이 재현되는지, 다시 켜면 즉시 감쇠되는지 확인했다. 수동 값 보존·더 낮은 수동 값 우선·자동 보정 해제·EQ/비트퍼펙트 우회와 두 EQ 모드의 독립 밴드 저장·고정 주파수/Q를 검사했다.
- 메인 스레드 디스크 읽기에 StrictMode 종료 정책을 걸고 표지 100회 재바인딩을 통과했다. 곡 행을 1,000번 재바인딩해 같은 표지 뷰가 재사용되는지 확인했다. 기존 12,000곡 최신 검색어 반영, 계층 폴더, 실제 손가락 드래그로 끝 행 접근 검사도 선행/최종 관련 실행에서 통과했다. 프레임 속도 실측이나 NW-ZX507의 지연 해소 보장은 아니다.
- 두 테마의 본문/보조/강조/버튼 글자 대비를 검사했다. 원본 기하학 테스트 표지를 생성해 실제 ArtworkView에서 로딩·정사각형을 확인하고, 무작위 켜기/끄기와 전체→한 곡→해제 반복을 실제 MediaController로 검사했다. 재생·가사·표지 닫기·재진입 스크롤 위치도 확인했다.
- 실제 실행 캡처: [다크 플레이어](screenshots/v0.12.0/player-dark.png), [라이트 플레이어](screenshots/v0.12.0/player-light.png), [홈](screenshots/v0.12.0/home-dark.png). Blue Hour / AURA Sessions / Quiet Tides 표지와 가사는 검사 전용 원본 샘플이며 앱에 데모 음악으로 포함하지 않았다. 설정 테마의 자동 검사 캡처 일부는 화면 전환 직후였으므로 완료 화면 자료로 사용하지 않는다.
- 최종 APK: dev.aura.player / 0.12.0 / code 16, 13,924,422바이트. SHA-256 `fedc72cc730936c0d7d61c9510a9f0478ecadac781e0b23b7a057bc47fe64ad1`. apksigner 검증 성공, 기존 서명 인증서 SHA-256 `d41fcccff7efb833e448e27aaac0b176454ee70772c358b678edb9a7ea5db89a` 유지.
- NW-ZX507/Android 9 및 실제 DAC는 연결되어 있지 않아 검증하지 않았다. 기존 Native DSD 등 미구현 범위는 이전 릴리스와 같다. 상세 변경: RELEASE-0.12.0.md.

## 0.12.0 공개 배포 확인

2026-10-27 [GitHub v0.12.0](https://github.com/Mobil0010/AURA/releases/tag/v0.12.0)에 APK·update.json·SHA256SUMS·대응 소스 ZIP 네 파일을 게시했다. 기본 latest/download/update.json을 다시 내려받아 0.12.0/code16 및 로컬 배포 정보 전체 일치를 확인했다. 공개 APK 13,924,422바이트를 다시 받아 SHA-256 `fedc72cc730936c0d7d61c9510a9f0478ecadac781e0b23b7a057bc47fe64ad1`, apksigner 검증 성공과 기존 인증서 일치를 확인했다. 소스 ZIP 445개 파일·5,068,217바이트에서 개인 키·로컬 SDK·캐시·설정 제외 및 빌드 안내·libusb/soxr 라이선스 포함을 확인했다. 이 공개 후 기록은 로컬에 추가했고 공개 파일은 덮어쓰지 않았다.

# 2026-10-26 · AURA 0.11.0 검증

- JDK 17 / AGP 8.11 / SDK 36 / 네 ABI의 `assembleDebug assembleDebugAndroidTest lintDebug` 성공. Lint 오류 0 / 경고 42.
- Android 16 ARM64, CPU 2코어 / RAM 2GB / 앱 heap 상한 192MiB에서 전체 회귀 **121개 통과**, 288.412초. 이후 복합 USB 경로 선택 제한만 추가한 최종 APK에서 USB 프로토콜·볼륨 키·시스템 볼륨 복귀·반복 재생 **11개 통과**, 43.646초. 최종 변경에 영향받는 USB 프로토콜과 통합 경로를 다시 실행했다.
- 실제 AAC를 인코딩·디코딩해 정수 PCM 협상을 확인했다. 직접 만든 24비트 FLAC을 실제 Android 디코더로 재생해 원본 샘플 6,144개와 일치하는지 확인했다. 별도로 16비트 전체 값과 24비트 100,000개·경계값의 float 무손실 복원, 비정상 float 거부, 실시간 처리 중 float 자료형 유지, 재사용 USB 버퍼 길이, 첫 샘플의 저장된 앱 볼륨 적용을 검사했다.
- 모의 UAC1/UAC2 Feature Unit에서 범위 조회·설정·검증·잘못된 설명자/읽기 전용/복합 경로 거부를 검사했다. 실제 MediaSession 플랫폼 볼륨 조절과 Activity의 볼륨 키를 통해 USB 전용 레벨이 바뀌고 휴대폰 STREAM_MUSIC 값이 유지되는지 확인했다. 직접 출력을 끄면 플랫폼 볼륨이 로컬 영역으로 돌아오는지 확인했다. 길게 누르는 중에도 하드웨어 요청이 반영되는지 검사했다.
- 8MiB의 메모리 압박을 유지하며 24회 곡 전환·탐색·일시정지/재개와 Activity를 배경으로 옮긴 재생을 확인했다. 측정 heap 상한 201,326,592바이트, 음원 버퍼 목표 3,145,728바이트. 전체 검사 중 해당 시점 native heap 할당 30,685,344바이트이며 피크나 프로세스 전체 PSS 수치는 아니다.
- 모의 Drive에서 폴더 표지 1회 다운로드/재사용, 내장 표지 우선·기존 버전 표지 플래그 누락 호환, 사진 변경 반영, 손상 사진의 반복 요청 방지, 연결 해제 시 캐시 제거를 검사했다. 두 정보 읽기 작업의 병렬 실행·상한 및 다음 검색의 재사용을 검사했다. SAF 테스트 제공자의 파일을 바꾼 뒤 앱 시작 자동 검색이 새 곡을 추가하고 삭제 곡을 제거하는지 확인했다. 읽기 실패 시 이전 목록 보존도 통과했다.
- Core 11개 / 원격 HTTP 21개 / USB 클록 6개 시나리오 / PCM 원형 버퍼 20,000회 / USB 슬롯 무손실 패딩 6,000개 검사 통과.
- 최종 APK: dev.aura.player / 0.11.0 / code 15, 14,586,236바이트. SHA-256 `3f068fe800353f72093c01fc7485e5f75d12c779d00fce82f2646ab7479b85dd`. 기존 서명 인증서 SHA-256 `d41fcccff7efb833e448e27aaac0b176454ee70772c358b678edb9a7ea5db89a` 유지.
- NW-ZX507/Android 9, Galaxy S25/Android 17, SIMGOT DEW4X 및 실제 USB 전송·하드웨어 볼륨·화면 꺼짐 키 동작은 실기기로 검증하지 않았다. 에뮬레이터 및 모의 제어 검사로 실제 기기 전체 호환성이나 무충돌을 보장하지 않는다. 자세한 변경과 제한은 RELEASE-0.11.0.md에 기록했다.

## 0.11.0 공개 배포 확인

2026-10-26 [GitHub v0.11.0](https://github.com/Mobil0010/AURA/releases/tag/v0.11.0)에 APK/update.json/SHA256SUMS/대응 소스 ZIP 네 파일을 게시했다. 최초 latest 주소에 0.10.0 캐시가 남았으나 재확인한 기본 latest/download/update.json과 캐시 분리 요청 모두 0.11.0/code15를 제공하며 로컬 정보 전체와 같았다. 공개 APK를 재다운로드해 14,586,236바이트, SHA-256 `3f068fe800353f72093c01fc7485e5f75d12c779d00fce82f2646ab7479b85dd`, apksigner 검증 성공과 기존 인증서 일치를 확인했다. 소스 ZIP 437개 파일에서 개인 키·인증 파일·SDK·캐시·로컬 설정 제외와 빌드 안내·네이티브 라이선스 포함을 확인했다. 이 게시 후 기록은 로컬 문서에 추가했으며 공개 파일은 덮어쓰지 않았다. 테스트 에뮬레이터는 종료했다.

# 2026-10-26 · AURA 0.10.0 검증

- `assembleDebug assembleDebugAndroidTest lintDebug` 성공. Lint 오류 0 / 경고 38.
- 화면·스크롤·Drive 관련 선행 33개 검사 통과. 최종 APK를 Android 16(API 36) ARM64 에뮬레이터에서 실행해 전체 **99개 검사 통과**, 283.251초. 반복 PCM 블록의 길이가 달라져도 32비트 샘플 값이 보존되고, 캐시 후 EQ 밴드를 변경했을 때 실제 출력 에너지가 바뀌는 검사도 통과했다.
- 실제 캡처: `screenshots/v0.10.0/header-partial.png`, `polish-library-thin.png`, `player-refined.png`. 합성 테스트 데이터다.
- 새 검색은 12,000곡에서 연속 검색 후 마지막 검색어에 해당하는 한 곡만 표시하는지 검사했다. compact header는 검색을 열지 않은 펼침 상태 104dp 이하이고, 손가락으로 부분 접힘 중 목록 높이가 바뀌지 않는지 검사했다.
- 플레이어를 아래로 스크롤하고 라이브러리에서 다시 열면 scrollY=0으로 시작한다. 전체화면 가사를 다녀온 후 플레이어 닫기가 라이브러리로 돌아가고 다시 열어도 맨 위인 것을 검사했다.
- 실제 터치 주입으로 곡·앨범·아티스트·폴더 양 끝 항목, 짧은 뷰포트의 그룹 목록, Drive 폴더 선택의 마지막 폴더와 페이지 하단·상단 이동을 검사했다. 첫 전체 실행 중 에뮬레이터 System UI ANR 창이 터치를 가리는 문제를 발견해 해소했으며, 해당 실행은 통과 결과로 사용하지 않았다. 기존 화면 구조를 가정하던 테스트와 작은 드래그의 끝 보정 조건도 갱신했다.
- 모의 Drive 목록에서 재생 호출 없이 메타데이터 읽기 단계를 거쳐 제목·아티스트·앨범·길이를 저장하는지, 변경되지 않은 파일은 두 번째 검색에서 재추출하지 않는지 검사했다. 별도로 범위 요청을 지원하는 로컬 HTTP WAV를 실제 MediaMetadataRetriever/DriveMetadata로 읽어 1초 길이를 얻고 전송 한도를 지키는지 검사했다. 실제 Google 계정의 모든 형식·태그·표지를 검증했다는 의미는 아니다.
- 기본 가사·인코딩 등 core 11개, 원격 HTTP 21개 검사 통과.
- 최종 APK: dev.aura.player / 0.10.0 / code 14, 14,004,082바이트. SHA-256 `91d9a930c55b7833f18c70706c19e3c11a4e1387c1fafa426f0eb17c6634a146`. apksigner 검증 성공, 기존 인증서 SHA-256 `d41fcccff7efb833e448e27aaac0b176454ee70772c358b678edb9a7ea5db89a` 일치.
- NW-ZX507 실기기/Android 9 검증 및 실제 USB 전송 검증은 수행하지 않았다. 구형 Android 자동 형식 구성 실패의 예외 처리 범위, 음원 버퍼·직접 버퍼·표지 메모리 및 중복 초기화를 개선했으나 보고된 기기 종료 원인을 확정하지 않는다. Java 종료 기록 복사는 추가 진단을 위한 기능이며 시스템 kill·네이티브 오류는 기록되지 않을 수 있다.

## 0.10.0 공개 배포 확인

2026-10-26 [GitHub v0.10.0](https://github.com/Mobil0010/AURA/releases/tag/v0.10.0)에 APK/update.json/SHA256SUMS/대응 소스 ZIP 네 파일을 게시했다. `latest/download/update.json`이 0.10.0/code14를 제공하며 로컬 배포 정보 전체와 동일한 것을 확인했다. 공개 APK를 재다운로드해 14,004,082바이트, SHA-256 `91d9a930c55b7833f18c70706c19e3c11a4e1387c1fafa426f0eb17c6634a146`, apksigner v2 검증 성공 및 기존 서명 인증서 일치를 확인했다. 소스 ZIP 419개 파일에서 개인 키·로컬 설정·SDK·빌드 산출물 제외와 대응 빌드 안내·SoX/libusb 라이선스 포함을 확인했다. 이 게시 후 검증은 로컬 문서에 추가했으며 공개 소스 ZIP은 덮어쓰지 않았다.

# 2026-10-26 · AURA 0.9.0 검증

- 최종 `assembleDebug assembleDebugAndroidTest lintDebug` 성공. Lint 오류 0 / 경고 40.
- Android 16(API 36) ARM64 에뮬레이터에서 **전체 92개 검사 통과**, 229.468초. 0.8.0의 86개에 동작/폴더/실시간 가사 4개와 가사 캐시 갱신 2개를 추가했다.
- 느린 하향 드래그에서 8px의 작은 움직임은 상단바를 바꾸지 않고, 더 움직이면 0과 1 사이의 실제 높이가 되는지 확인했다. 손을 유지하거나 놓은 뒤에도 중간 높이가 유지되고 목록 플링만으로 상단바가 바뀌지 않는지 확인했다. 펼치기/완전 접힘·반대 방향 터치의 기존 검사는 새 점진적 동작에 맞춰 갱신했다.
- 같은 이름의 등록 루트를 ID로 분리하고 직접 곡/자식 폴더 곡을 구분했다. 폴더 이름에 `/`가 있는 경로를 JSON 저장/복원해도 하나의 이름으로 유지되는지 확인했다. 실제 Activity에서 저장소→Music→Albums→Jazz로 진입하고 뒤로 가기로 부모 경로에 돌아왔다. 폴더 진입 자체로 재생 대기열이 바뀌지 않는지도 확인했다.
- 실제 SAF/모의 Drive 검색 회귀 검사와 Drive 검색 결과의 경로 저장 검사를 통과했다. 이전 JSON에 경로 목록이 없어도 읽으며, 완전한 폴더 ID 경로를 얻으려면 다시 검색한다.
- 실제 Media3/PcmSink로 3초 WAV와 합성 LRC를 재생해 현재 줄이 바뀌고 다음 줄이 미리보기에 붙지 않는지 확인했다. 일시정지 후 1.6초와 0.1초로 탐색해 미리보기/전체화면의 활성 줄이 해당 위치로 갱신되는지 검사했다. 긴 일반 가사 200줄을 넣어도 하단에 통째로 표시하지 않는지 확인했다.
- LRCLIB의 정확 조회가 일반 가사만 반환하고 검색 결과에는 동기화 가사가 있을 때 동기화 가사를 선택했다. 이전 일반 가사 캐시를 자동 갱신하고, 직접 선택한 일반 가사는 기본 유지하되 명시적인 새로 찾기로 동기화 가사로 바꾸는지 검사했다. 온라인 꺼짐 시 네트워크 요청 0회 및 저장된 가사 오프라인 읽기도 유지했다.
- 별도 공개 LRCLIB 검색에서 이전 보고 곡의 358.973333초 동기화 가사 후보를 확인했다. 실제 사용자 음원에 적용해 확인한 것은 아니며 원문은 문서·소스 ZIP에 넣지 않는다.
- Drive 사전 로딩/자동 전환, 네이티브 PCM/SoX/EQ, USB 정책, 대용량 목록/표지 비율, 플레이어 닫기, 업데이트 서명/변조 차단 회귀 검사도 통과했다. 실제 USB DAC 물리 전송 및 Google 계정의 실기기 네트워크 재생은 별도 검증 범위다.
- 최종 APK: dev.aura.player / 0.9.0 / code 13, 13,998,387바이트. SHA-256 `ede60ffd97ae0a068d4db6921fb01363c767b7a35ed678a3b9ec6b11db335c34`. apksigner v2 검증 성공. 기존 인증서 SHA-256 `d41fcccff7efb833e448e27aaac0b176454ee70772c358b678edb9a7ea5db89a` 유지.
- 캡처: `screenshots/v0.9.0/header-partial.png`, `folder-path.png`, `player.png`. 모두 합성 테스트 데이터다.

## 0.9.0 공개 배포 확인

2026-10-26 [GitHub v0.9.0](https://github.com/Mobil0010/AURA/releases/tag/v0.9.0)에 APK/update.json/SHA256SUMS/대응 소스 ZIP을 게시했다. 공개 latest/download/update.json이 0.9.0/code13을 제공하며 로컬 정보와 동일한 것을 확인했다. 공개 APK를 재다운로드해 13,998,387바이트, SHA-256 `ede60ffd97ae0a068d4db6921fb01363c767b7a35ed678a3b9ec6b11db335c34`, apksigner 검증 성공 및 기존 서명 인증서 일치를 확인했다. 소스 ZIP의 개인 키·로컬 설정 제외와 빌드 안내·네이티브 라이선스 포함도 확인했다. 이 게시 후 결과는 로컬 기록에 추가했으며 이미 공개한 소스 ZIP은 덮어쓰지 않았다.

이전 검증 기록은 아래에 보존한다.

# 2026-10-26 · AURA 0.8.0 검증

- JDK 17 / Gradle 8.14.3 / AGP 8.11.0 / SDK 36 / minSdk 26. `assembleDebug assembleDebugAndroidTest lintDebug` 성공. Lint 오류 0 / 경고 39.
- 최종 Android 16 ARM64 전체 **86개 검사 통과**, 205.682초. 0.7.0의 72개에 사용성 9개와 LRCLIB 전송 5개를 추가했다.
- 라이브러리 목록을 중간까지 이동한 후 그룹 진입/복귀와 탭 왕복에서 같은 첫 표시 항목을 복원했다. 검색 앞뒤 공백 처리·지우기·Drive 음원 없음 안내를 확인했다. 재활용 카드의 실제 상단 간격과 곡 메뉴가 재생 대기열을 바꾸지 않는지 검사했다.
- 대기열에서 곡을 선택해도 전체 대기열이 유지되고 삭제 후 표시 개수가 갱신되는지 실제 MediaController로 검사했다. 설정 하위 화면의 뒤로 가기와 SwitchCompat 통일, 상태 표시줄 여백, 화면 갱신 후 검색 포커스·커서 복원을 확인했다.
- 실제 UIAutomation 터치로 라이브러리를 내려 상단바가 48dp의 얇은 인터페이스로 바뀌는지, 반대 방향으로 움직이면 전체 제목/탭/필터/검색이 다시 보이는지 검사했다. 상단바는 숨겨 없애지 않으며 현재 탭·검색·펼치기 동작을 유지한다. 긴 목록·짧은 뷰포트·Drive 폴더 끝까지 스크롤하는 기존 검사도 통과했다.
- 완료 검색 안내 닫기, 진행 중 닫기 숨김, 성공 안내 8초 후 숨김을 확인했다.
- 모의 HTTP 서버에서 LRCLIB의 재생 시간/앨범 누락 요청, 현재 버전 User-Agent, 정확한 조회 404 후 구조화 검색, 가수·시간이 다른 후보 거부, null 동기화 가사에서 일반 가사 사용, 429 안내, 온라인 꺼짐 시 요청 0회, 저장한 가사의 오프라인 읽기를 검사했다. 같은 URI의 메타데이터가 보완되면 실제 가사 파일을 다시 읽는지 비동기 경로로 확인했다.
- 별도 실제 HTTPS 요청으로 LRCLIB `/api/search` 응답과 `/api/get?track_name=Believer&artist_name=Imagine%20Dragons`의 동기화 가사 응답을 확인했다. 가사 원문은 검증 문서·소스 ZIP에 포함하지 않는다. 실제 사용자의 개별 파일과 네트워크에서 동일 결과를 검증했다는 의미는 아니다.
- 기존 Drive 사전 로딩/자동 전환, PCM/SoX/EQ, USB 라우팅 정책, 플레이어 닫기, 정사각형 표지, 업데이트 정상/변조/다른 서명 차단 검사도 통과했다. 실제 USB DAC 전송 및 Google 계정의 실기기 스트리밍 호환성 범위는 확대하지 않았다.
- 최종 APK: dev.aura.player / 0.8.0 / code 12, 13,973,949바이트. SHA-256 `a6947ec36d77f8935baa41bda3f07e8f6d4f90b00d23cbebc1b4250a82b25f68`. apksigner v2 검증 성공, 기존 인증서 SHA-256 `d41fcccff7efb833e448e27aaac0b176454ee70772c358b678edb9a7ea5db89a` 유지.
- 실제 화면 캡처: `screenshots/v0.8.0/`의 home/library/settings/player/queue/library-thin/library-expanded. 합성 테스트 곡과 상태를 사용한다.

## 0.8.0 공개 배포 확인

2026-10-26 [GitHub v0.8.0](https://github.com/Mobil0010/AURA/releases/tag/v0.8.0)에 APK/update.json/SHA256SUMS/대응 소스 ZIP을 게시했다. 공개 latest/download/update.json이 0.8.0/code12를 제공하며 로컬 배포 정보와 동일한 것을 확인했다. 공개 APK를 다시 내려받아 13,973,949바이트, SHA-256 `a6947ec36d77f8935baa41bda3f07e8f6d4f90b00d23cbebc1b4250a82b25f68`, apksigner 검증 성공 및 기존 인증서 일치를 확인했다. 소스 ZIP의 개인 키·로컬 설정 제외와 빌드 안내·라이선스 포함도 확인했다. 게시 후 결과는 로컬 검증 기록에 추가했으며 공개 소스 ZIP은 덮어쓰지 않았다.

이전 검증 기록은 아래에 보존한다.

# 2026-10-19 · AURA 0.7.0 검증

- `assembleDebug assembleDebugAndroidTest lintDebug` 성공. Lint 오류 0 / 경고 41.
- Android 16(API 36) ARM64 에뮬레이터에서 최종 전체 **72개 검사 통과**, 150.859초. 기존 66개에 실제 터치 스크롤 3개와 Drive 사전 로딩 3개를 추가했다.
- UIAutomation의 터치 이벤트를 실제 화면에 주입해 곡·앨범·아티스트·폴더 목록의 처음/마지막 행을 완전히 볼 수 있는지 확인했다. 목록 위치로 직접 점프하는 방식의 검사가 아니다.
- 긴 그룹 곡 목록과 높이를 240dp로 제한한 짧은 화면에서도 끝 항목에 도달하고 돌아오는지 확인했다. 기존 5천 곡 행 재활용 검사도 통과했다.
- 등록한 Drive 폴더 8개와 하위 폴더 36개(긴 이름 포함)를 넣어 폴더 목록 끝, 그 아래 안내, 첫 폴더와 페이지 상단으로 왕복했다. 기존 플레이어 아래로 닫기/짧은 끌기 복원/스크롤 중 닫힘 방지 검사도 통과했다.
- 모의 Drive HTTP 서버 + 실제 DriveDataSource/ExoPlayer/PcmSink로 다음 곡이 현재 곡 재생 중 먼저 읽히는지, 수동 다음 곡 이동 시 시작 구간을 다시 요청하지 않는지 검사했다. 대기열 교체 시 새 다음 곡을 준비하고 빈 대기열에서 사전 로딩 설정을 해제하는지 확인했다.
- 3초 WAV 두 곡을 실제로 재생해 곡 종료 후 다음 Drive 곡으로 자동 전환되고 미리 받은 시작 데이터를 재다운로드하지 않는지 확인했다. 긴 파일 검사에서도 모든 HTTP 요청은 1 MiB 이하 범위를 유지했다.
- 최종 APK: dev.aura.player / versionName 0.7.0 / versionCode 11. 13,945,870바이트, SHA-256 `81f1f556296d7fafd0683c89d32343b0e9ae4ab014676cdd90108c30b0ba0d47`. apksigner v2 검증 성공, 기존 인증서 SHA-256 `d41fcccff7efb833e448e27aaac0b176454ee70772c358b678edb9a7ea5db89a` 유지. ARM64·ARMv7·x86·x86_64 라이브러리를 포함한다.
- 최종 캡처: `screenshots/v0.7.0/library.png`, `drive-bottom.png`, `drive-top.png`. 테스트 데이터이며 실제 개인 Drive 파일이나 계정 내용은 포함하지 않는다.
- 실제 Google 계정/실기기 네트워크 재생, Android 9 실물 및 USB DAC 물리 전송은 이번 에뮬레이터 검사로 검증하지 않았다. 이전 제한은 유지한다.

## 0.7.0 공개 배포 확인

[GitHub v0.7.0](https://github.com/Mobil0010/AURA/releases/tag/v0.7.0)에 APK/update.json/SHA256SUMS/대응 소스 ZIP을 게시했다. 공개 latest/download/update.json이 0.7.0/code11을 제공하며 로컬 배포 정보와 동일한 것을 확인했다. 공개 APK 재다운로드 후 13,945,870바이트, SHA-256 `81f1f556296d7fafd0683c89d32343b0e9ae4ab014676cdd90108c30b0ba0d47`, apksigner 검증 성공 및 기존 인증서 일치를 확인했다. 소스 ZIP의 키·로컬 설정 제외와 빌드 안내·라이선스 포함도 확인했다. 게시 후 결과는 이 로컬 기록에 추가했으며 공개 소스 ZIP을 덮어쓰지 않았다.

이전 검증 기록은 아래에 보존한다.

# 2026-10-19 · AURA 0.6.0 검증

- 최종 `assembleDebug assembleDebugAndroidTest lintDebug` 성공. Lint 오류 0 / 경고 41.
- Android 16 ARM64 최종 전체 66개 검사 통과(63.925초). 기존 54개 + Drive 12개.
- Drive 범위/탐색/잘못된 Range/잘린 본문/안전하지 않은 리디렉션/계정 불일치 차단, 401 갱신 1회, 폴더 페이지 처리/중복 제거/LRC 연결, 실패 시 이전 목록 및 로컬 유지, 메타데이터 읽기 예산을 검사했다.
- 실제 Media3와 PCM 출력으로 모의 서버의 64 MiB WAV를 전체 전송 전에 재생하고 120초 탐색했다. 모든 음원 HTTP 요청은 1 MiB 이하로 제한했다. 음원 디스크 캐시는 만들지 않는다.
- 로컬·Google Drive 동명 폴더 구분과 연결 화면을 실제 Activity에서 확인했다. 캡처: `screenshots/v0.6.0/folders.png`, `connect.png`.
- 토큰 갱신은 Google의 기존 토큰 제거가 끝난 뒤 재요청하도록 순서를 보장했다. 네트워크 대기는 작업 스레드에서 제한 시간으로 수행한다.
- 최종 APK 패키지 dev.aura.player / 0.6.0 / code 9. apksigner 검증 성공, v2 서명 및 기존 인증서 SHA-256 d41fcccff7efb833e448e27aaac0b176454ee70772c358b678edb9a7ea5db89a 유지.
- Google Cloud에 앱/Android 클라이언트/개인 테스트 계정/Drive 읽기 전용 범위를 등록했다. 실제 Google 계정 OAuth 승인과 원격 파일 재생은 에뮬레이터에 계정이 없어 수행하지 않았다. 공개 OAuth 검증 및 모든 계정 허용은 완료되지 않았다. USB DAC 물리 검증은 수행하지 않았다.

## 0.6.0 공개 배포 확인

2026-10-19 [v0.6.0 릴리스](https://github.com/Mobil0010/AURA/releases/tag/v0.6.0)에 APK/update.json/SHA256SUMS/대응 소스 ZIP을 게시했다. latest/download/update.json이 0.6.0/code9를 제공하고 로컬 배포 정보와 동일한 것을 확인했다. 공개 APK를 다시 내려받아 13,945,045바이트, SHA-256 `513c1ec2e0e9ff3df0cc10b663cca456e59902810bfe4bb73ab44251f1fc7c82`, apksigner 검증 성공 및 기존 인증서 일치를 확인했다. 이 게시 후 검증 기록은 로컬 문서에 추가했으며 이미 공개된 소스 ZIP은 덮어쓰지 않았다.

이전 검증 기록은 아래에 보존한다.

# 2026-10-18 · AURA 0.5.1 검증

## 빌드

- JDK 17 / Gradle 8.14.3 / AGP 8.11.0 / compile·target SDK 36 / min SDK 26.
- NDK 27.2.12479018 / CMake 3.22.1. ARM64·ARMv7·x86·x86_64 네이티브 라이브러리를 APK에 포함한다.
- `assembleDebug assembleDebugAndroidTest lintDebug`: 성공. Lint 오류 0, 경고 38. 경고에는 UI 접근성/번역/그리기 할당, 인라인 오디오 상수, 의존성 버전 안내, 서비스 exported/백업 설정 등이 있다.
- APK 서명 확인: 성공. 기존 네이티브 구성의 16 KB 페이지 정렬 설정을 유지했다.

## 실행 검사

Android 16(API 36) ARM64 에뮬레이터에서 **54개 검사 통과**.

- 실제 libsoxr JNI: 44.1→96 kHz 프레임 수/RMS, alias 억제, 품질/위상 옵션.
- EQ 그래프와 실제 필터의 응답 일치, PCM16/24/32와 빅엔디언 경계 값, PCM32 최하위 비트 보존.
- 기기 프리셋 저장/복원, 잘못된 USB FD 거부.
- Enhanced LRC 단어 타이밍, DSF→PCM 및 DoP marker 포장.
- 실제 앱 화면 전환과 Cast 선택창, 업데이트 화면.
- 업데이트 메타데이터 왕복/잘못된 HTTPS·해시·크기·버전 차단.
- 정상 새 버전 APK 허용, 다른 서명·변조·버전 불일치 APK 거부.
- FileProvider가 내부 업데이트 폴더만 공유하는지 확인.
- 다운로드 기록이 사라져도 내부 APK를 재검증해 설치 준비 상태를 복원.
- Media3→SoX→96 kHz AudioTrack 재생 및 일시정지·탐색 후 재개.
- **압축 AAC 파일을 생성한 뒤 MediaCodec으로 실제 디코딩/AudioTrack 재생.**
- AudioTrack 생성 실패 주입 후 자동 형식 재시도. 명시한 형식은 조용히 대체하지 않는지 확인.
- flush 직후 출력 장치를 다시 열지 않고 다음 데이터 도착 시 여는지 확인.
- HTTP Range/잘못된 토큰, 정상 DLNA XML/외부 entity 차단, 캐스트용 SoX WAV 생성.
- 5천 곡 목록의 행 재활용과 마지막 곡 접근, 고해상도 앨범아트 축소 디코딩.
- 하위 화면 뒤로 가기, EQ 그래프 밴드 선택 표시 동기화.
- Android 13 공식 비트퍼펙트 거부/USB 직접 출력 허용, 권한 없는 USB 거부.
- 지원 USB 하나 자동 선택/여러 기기 명시 선택/미지원 경로 거부.
- 지원되지 않는 비트퍼펙트 토글이 기존 설정을 변경하지 않는지 확인.
- 긴 하향 스와이프 닫기, 짧은 끌기 원복, 스크롤 중 닫힘 방지.
- 큰 재생 버튼의 실제 아이콘 중심과 이전/다음 버튼 간격.
- 비정사각형 원본의 정사각형 CENTER_CROP 및 동일한 가로/세로 배율, 실제 화면의 표지 크기.
- 재생 화면 미니 플레이어 숨김, 화면 위로 밀림 방지와 실제 화면 캡처.

JVM LRC/리모컨 HTTP 32개 검사는 0.5.0에서 통과했으며 관련 코드가 바뀌지 않은 0.5.1에서는 다시 실행하지 않았다. 0.5.0의 별도 C++ 호스트 검사에서 UAC2 클록 협상 6개 시나리오(이미 일치, 읽기 전용 불일치, 정상 변경, 읽힌 주파수 불일치, SET 실패 뒤 적용 확인, 연결 실패)를 통과했다.

추가 검사에서는 테스트 APK에만 설치한 SAF 계약 파일 제공자를 사용했다. 두 루트와 겹치는 하위 폴더의 중복 제거, 변경 없는 파일 재검색 시 파일 재열기 0회, 읽기 실패 시 기존 목록 유지, 단일 폴더 설정 이관/복수 폴더 저장을 확인했다. Activity를 종료해도 검색 완료/진행 알림이 유지되는지, 취소 후 곡 목록과 알림 정리가 맞는지 검사했다. 실제 ExoPlayer 상태로 알림 볼륨 감소/일시정지/재개 선택을 확인했고, USB 분리 처리 함수를 호출해 폰 스피커 선택/비트퍼펙트·DoP 해제/일시정지 선택을 확인했다. USB 연결 이벤트 자체와 물리 전송은 가짜 검사로 검증했다고 주장하지 않는다.

## Android 9 서명 조회 수정

NW-ZX507의 Android 9에서 앱 내부 업데이트가 서명 정보를 읽지 못하는 보고를 확인했다. Android 9 원본 PackageManager 구현의 GET_SIGNATURES 조건을 근거로 조회 플래그를 수정했다. API 26/27/28/29/36용 플래그 정책, 실제 정상 APK 서명 수집, 레거시 서명 필드만 있을 때의 처리/모든 서명 필드가 비면 거부, 다운로드 임시 파일(download.partial) 경로에서의 검증을 추가했다. 기존 다른 키·변조·버전 불일치 거부 검사도 통과했다. 현재 키는 유지했다.

Android 9나 NW-ZX507 실물에서 실행한 결과로 표현하지 않는다. 추가 3개 검사를 포함한 54개는 API 36 ARM64 에뮬레이터의 결과다. Android 9 원본 구현은 [AOSP 소스](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-9.0.0_r47/core/java/android/content/pm/PackageManager.java)로 확인했다.

## 실제 화면

[홈](screenshots/ui-home.png) · [라이브러리](screenshots/ui-library.png) · [앨범](screenshots/ui-albums.png) · [0.5.0 설정](screenshots/v0.5.0/settings.png) · [0.5.0 재생](screenshots/v0.5.0/player.png) · [하단 가사](screenshots/v0.5.0/player-lyrics.png) · [EQ](screenshots/eq.png) · [SoX](screenshots/sox.png)

앱을 에뮬레이터에서 실행해 캡처했다. 홈·라이브러리·재생 화면의 AURA Sessions 목록은 검사에서 주입한 샘플이며 일반 앱에 데모 곡을 추가한 것은 아니다. 표지 없는 곡에는 앱의 기본 표지를 표시한다.

## 한계

사용자가 제공한 SIMGOT DEW4X의 USB format configuration: LIBUSB_ERROR_IO 화면은 확인했다. 실물 연결과 USB 제어 전송 로그는 확보하지 않았다. 에뮬레이터 재시도/디코딩 검사 통과만으로 그 폰에서 같은 오류가 해결됐다고 단정하지 않는다. 실패 시 앱에서 하위 원인을 표시하며 기본 출력 복구와 오류 정보 복사를 제공한다.

안드로이드 설치 확인 버튼을 자동으로 누르는 검사는 수행하지 않았다. 0.4.0 배포에서는 GitHub 공개 첨부 파일과 latest 메타데이터, APK 재다운로드 후 해시·서명을 확인했다. 0.4.1도 같은 검증을 완료했다. 0.5.0도 공개 latest JSON이 로컬 배포 정보와 일치하는지 확인했다. 공개 APK를 다시 받아 13,700,809바이트·SHA-256·v2 서명을 검증했으며, 서명 인증서 SHA-256은 기존 d41fcccff7efb833e448e27aaac0b176454ee70772c358b678edb9a7ea5db89a와 일치한다. 공개 릴리스: https://github.com/Mobil0010/AURA/releases/tag/v0.5.0 . APK/update.json/SHA256SUMS/대응 소스 ZIP 4개가 게시됐다. 소스 ZIP에는 공개 직전 검증 기록이 포함되고 이 단락은 게시 후 로컬에 추가했다.

실물 USB DAC 전송·비트 동일성·Cast/DLNA 스피커 재생은 미검증이다. Android 8~15와 다른 CPU ABI는 빌드/정적 호환성 검사만 수행했다. Native DSD, ReplayGain, FIR 컨볼루션, 크로스페이드, 완전한 재생 기기 이전/클라우드 원격 제어는 구현 범위에 포함되지 않는다.

## 산출물

버전: 0.5.1 / versionCode 8 / 디버그 서명

APK: `app/build/outputs/apk/debug/app-debug.apk`

SHA-256: `44eb7c4ce9e05c00378a8bca66de163f68ef826056aafccce57cb5c56d01be46`

## 0.5.1 공개 배포 확인

https://github.com/Mobil0010/AURA/releases/tag/v0.5.1 에 APK·update.json·SHA256SUMS·대응 소스 ZIP 4개를 게시했다. 공개 latest JSON이 로컬 정보와 같으며 versionCode 8 / 0.5.1을 제공하는지 확인했다. APK를 다시 내려받아 13,536,226바이트·SHA-256 44eb7c4ce9e05c00378a8bca66de163f68ef826056aafccce57cb5c56d01be46 및 v2 서명을 검증했다. 인증서 SHA-256 d41fcccff7efb833e448e27aaac0b176454ee70772c358b678edb9a7ea5db89a는 이전 버전과 같다. 이 배포 후 확인 기록은 로컬에 추가했으며 공개 소스 ZIP은 게시 직전 기록을 포함한다.

## 2026-10-30 release 0.20.1 verification

Restored preserved signing keys from user-provided archive; both certificates match registered public certificates. Frozen bridge SHA-256 and lineage match. Windows release build, lintDebug and lintVitalRelease passed. Core 11, karaoke timing 8 and remote HTTP 21 checks passed. Signed APK verifies v2/v3/v3.1 and zipalign 16KB check. No Android device was connected; fresh installation and playback tests were not performed. Windows packaging now uses SDK environment paths, UTF-8 metadata and ASCII temporary paths for aapt.

## 2026-10-30 published APK emulator playback

Installed the downloaded public 0.20.1 APK on Pixel_Tablet Android 15 x86_64. Same-certificate instrumentation passed repeatedSeekPauseAndTrackChangesRemainStableWithHeapPressure (18.673 seconds): WAV playback position advances, 24 seek/track transitions, pause/resume, background playback and 8 MiB retained heap pressure, with no player error. Initial run failed because Android storage cleanup deleted the cache fixture; moved only the test fixture to filesDir and reran successfully. Public application APK unchanged. This verifies emulator playback state, not physical speaker sound quality or USB hardware. Logs and metrics: .local/release-0201-emulator-playback.log and .local/release-0201-emulator-metrics.json.

## 2026-10-30 output routing development (not published)

Added automatic Bluetooth/wired switching from a phone fallback, conservative Bluetooth reconnection behavior while another external output is selected, stable route identity restoration and USB/wired/Bluetooth/speaker startup fallback. Output removal pauses playback. Non-USB transitions disable USB perfect/DoP. New-output automatic switching is configurable; USB attach pause defaults to false and USB detach always pauses.

Bluetooth codec configuration broadcasts are monitored on a best-effort basis. Restricted or missing system codec information is shown as unavailable; PCM/source encoding is never used to guess the Bluetooth codec. Manual versus automatic Bluetooth connection intent cannot be distinguished reliably by AudioDeviceCallback: external outputs are conservatively preserved. No physical Bluetooth/USB device was connected, so hardware switching, codec broadcasts and audible output are unverified.

Final debug APK and lintDebug passed. Android 15 Pixel_Tablet instrumentation: OK (15 tests), 30.62 seconds: 5 new route policy tests, 9 routing/UI regressions and actual WAV playback with 24 transitions/pause/resume/background playback/8 MiB pressure. Log: .local/output-routing-verification.log. No publication or push was performed for this work.

## 2026-10-30 release 0.20.2/code28

Release build, lintDebug and lintVitalRelease passed. Preserved v2/v3/v3.1 rotation signing and 16KB ZIP alignment verified. Signed release APK installed on Android 15 Pixel_Tablet; 5 output policy checks and actual playback/24 transitions/pause/resume/background/8 MiB pressure passed: OK (6 tests), 19.837 seconds. Log: .local/release-0202-playback.log. Prior final debug routing/UI suite: 15 passed. Physical Bluetooth/USB switching and codec reception remain unverified; restricted codec information displays unavailable.
