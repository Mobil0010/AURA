> 2026-10-28 후속 상태: 사용자 배포 승인 후 **0.18.2를 공개 게시하고 두 업데이트 피드와 두 APK를 검증했다.** 아래의 미배포 문구는 사전 검증 당시 기록이다. 최신 게시 결과는 VALIDATION.md 및 RELEASE-0.18.2.md를 참고한다.

# AURA 서명 전환 — 미배포 검증

2026-10-28. 사용자가 배포를 보류했다. 공개 버전/업데이트 피드는 0.18.0/code22를 유지한다. 아래 버전은 로컬 후보와 테스트 전용이며 공개하지 않았다.

## 서명과 업데이트 경로

- 전용 RSA4096/SHA256 배포 키를 생성했다. 공개 인증서는 `signing/release.pem`, 기존 인증서는 `signing/legacy.pem`, 공개 서명 교체 증명은 `signing/aura.lineage`다.
- APK 하나에 SDK 대상 서명을 넣는다: Android 13(API33)+는 새 키와 v3.1 교체 증명, Android 8–12는 기존 키(v2/v3). NW-ZX507의 Android 9에서는 새 키로 강제 교체하지 않는다.
- 기존 0.18.0의 앱 내 검사 로직은 현재 인증서의 완전 일치만 허용한다. 최신 Android에서 새 키 APK를 바로 앱 내 업데이트로 전달하면 거절된다. 기존 서명으로 서명한 0.18.1/code23 전환용 APK로 업데이트 검사기를 먼저 바꾼다. 이후 0.18.2/code24 새 서명 APK를 설치한다.
- `AppUpdater`는 동일 서명 또는 Android 13+에서 PackageManager가 확인한 후보 서명 이력에 설치된 현재 서명이 있는 정방향 교체만 허용한다. 단순 공통 조상·이전 서명으로의 되돌림·무관한 키는 허용하지 않는다. 해시/크기/버전/패키지 검사는 유지한다.
- Android 9는 같은 APK의 기존 서명이 적용되어 0.18.0 → 0.18.2 직접 업데이트도 가능하다.
- `update.json`은 기존 앱이 계속 읽는 고정 전환용 0.18.1 APK를 안내하고, 전환 이후 앱은 `update-v2.json`에서 최신 버전을 확인한다. 미래 릴리스에도 두 피드와 고정 전환용 APK를 함께 보관해야 뒤늦게 업데이트하는 사용자도 막히지 않는다. 동결한 전환용 APK 크기/해시는 `signing/bridge.json`에 기록했다.

## 빌드와 비밀 키

`python3 tools/build-signed-release.py`는 디버그 가능 플래그가 없는 release APK를 빌드하고 기존 키/새 키/동일한 lineage로 서명한다. 결과: `app/build/outputs/apk/release/app-release.apk`.

개인 키·암호·기존 키 백업은 **`.local/signing/`** 안에만 있다. 디렉터리 0700, 개인 파일 0600. 이 폴더는 소스/릴리스 ZIP에 포함하지 않는다. 공개 인증서·lineage에는 개인 키가 없다. 이 폴더와 고정 전환 APK는 소유자가 별도 안전한 장소에 백업해야 하며, `.local` 정리/작업 폴더 삭제 전에 반드시 보존해야 한다. 임의로 새 키를 다시 생성하거나 기존 키를 지우면 업데이트 경로를 잃는다. 구형 기기의 미래 업데이트에 **기존 개인 키도 계속 필요**하다.

보관된 전환용 APK: `.local/signing-migration/AURA-0.18.1-bridge.apk`.
`python3 tools/prepare-release.py --bridge .local/signing-migration/AURA-0.18.1-bridge.apk --notes '<이번 변경>'`로 로컬 패키징한다. 도구는 release 서명/v3.1·실제 버전·debuggable 해제·동결 전환 APK를 확인한다. `publish-release.py`는 두 피드와 두 APK를 지원하도록 수정했지만 이번에는 실행하지 않았다. 기존 공개 릴리스는 변경하지 않았다.

새 체크아웃에서 개인 키가 없는데 이 빌드를 실행해 새 키를 만들지 말 것. `docs/signing/release.pem`이 있으면 등록된 기존 키를 복원해야 한다. 기존 키 백업과 새 키·암호·lineage를 함께 복원한다.

## 실제 설치 검증

기존 공개 0.18.0 APK를 설치하고 앱 내부 설정/폴더 경로/파일에 보존 확인 데이터를 넣었다. 앱 삭제나 데이터 초기화 없이 `adb install -r`로 업그레이드했다. 각 단계의 실제 앱 내 APK 검사도 별도 instrumentation으로 실행했다.

- API36 ARM64: 기존 앱이 새 서명 후보를 거절하는 현상 재현 → 기존 서명 전환 APK 검증/설치 → 새 서명 APK 검증/설치 → 테스트 전용 다음 버전(code25) 검증/설치. 새 인증서와 서명 이력이 실제 적용됨을 확인했다. 8개 검사 통과.
- API28 ARM64(Android 9): 기존 앱에서 동일한 새 APK를 검증하고 직접 업데이트 → 테스트 전용 다음 버전(code25)으로 업데이트. 기존 인증서가 실제 유지됨을 확인했다. 5개 검사 통과.
- 각 단계에서 앱 UID, 설정, 폴더 경로, 앱 내부 파일 내용이 유지됐다. 무관한 서명과 최신 Android의 폐기된 서명으로 되돌림을 거절했다.
- 두 환경에서 업데이트 후 실제 WAV 재생·24회 탐색/곡 전환·일시정지/재개·백그라운드 재생 및 8MiB 메모리 부하 검사를 각각 1개 통과했다. 전체 13개 전환 검사 + 초기 데이터 준비 2개 + 실제 재생 2개.
- release 빌드/lint 성공. APK는 v2/v3/v3.1 검증 성공, debuggable 없음. Android9/13/16 별 서명 검증은 로컬 검증 기록을 참고한다.
- 실제 S25(Android17)·NW-ZX507 하드웨어/Google 로그인·Drive 스트리밍은 이번 에뮬레이터 검사로 대체했다고 주장하지 않는다. Google 로그인용 새 SHA1 등록은 사용자 승인 후 완료했다. 기존 클라이언트와 권한은 유지했다. 실제 계정 로그인/스트리밍은 미검증이다.

로그: `.local/signing-modern-migration.log`, `.local/signing-api28-migration.log`, `.local/signing-modern-playback.log`, `.local/signing-api28-playback.log`.

## 주의

이 구성은 GitHub 직접 배포용 호환 서명 전환이다. 구형 플랫폼용 서명에는 기존 개발용 인증서를 유지하므로 Play Store 심사에 적합하다는 뜻이 아니다. Play 등록/Play App Signing의 키 전환 허용 여부는 별도로 확인해야 한다. 정식 키 생성은 Play 프로텍트 또는 삼성 외부 출처 안내 제거를 보장하지 않는다.
