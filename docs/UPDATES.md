# 앱 업데이트

현재 구현은 0.18.1의 서명 전환 검사기와 두 피드 방식을 포함한다. 아래 사용 흐름과 배포 규약은 현재 코드 기준이며, 마지막의 0.4.0 항목은 최초 배포 이력이다.

## 사용 흐름

1. 처음에는 공식 공개 APK를 설치한다. 0.3 이하 앱에는 업데이트 기능 자체가 없으므로 첫 설치는 수동으로 진행한다. 서명 전환 전 앱은 전환용 0.18.1을 먼저 설치하고 앱을 다시 열어 업데이트를 한 번 더 확인한다. 0.18.2 이상은 최신 버전으로 바로 업데이트한다.
2. 앱을 열 때 GitHub Releases의 `update-v2.json`을 최대 6시간 간격으로 확인한다. 전환 전 앱은 `update.json`을 사용한다. 설정 → 앱 업데이트에서 자동 확인을 끄거나 수동 확인할 수 있다.
3. 새 버전이면 홈·설정에 안내 배너가 표시된다. 업데이트 화면에서 변경 내용과 크기를 확인하고 다운로드한다.
4. 시스템 DownloadManager로 다운로드하므로 앱 화면을 닫아도 다운로드 작업이 유지된다. 앱을 다시 열면 진행 상황을 복원한다.
5. 앱 내부 저장소로 복사하면서 크기·SHA-256·패키지명·versionCode/versionName·Android 최소 버전·서명을 검사한다. 동일 서명 또는 Android 13 이상에서 플랫폼이 확인한 정방향 서명 교체 이력만 허용한다. 무관한 키나 이전 서명으로의 되돌림 등 검사에 실패하면 설치하지 않는다.
6. 다운로드를 시작한 화면이 열려 있으면 검증 후 설치 화면을 연다. 앱을 종료했다가 다시 열었으면 ‘업데이트 설치’를 누른다.
7. 최초 한 번 AURA의 ‘이 앱에서 설치 허용’을 켠 뒤 안드로이드 설치 화면에서 확인한다. 무인·무확인 설치 기능은 제공하지 않는다.

앱이 닫혀 있을 때 새 버전을 주기적으로 검색하는 백그라운드 알림은 포함하지 않는다. 자동 확인은 앱을 열었을 때 동작한다. APK 다운로드는 사용자가 업데이트를 눌렀을 때만 시작한다. 로밍 중에는 다운로드를 대기한다.

## 배포 규약

저장소: https://github.com/Mobil0010/AURA

현재 검사기의 확인 주소: `https://github.com/Mobil0010/AURA/releases/latest/download/update-v2.json`

전환 전 앱의 확인 주소: `https://github.com/Mobil0010/AURA/releases/latest/download/update.json` — 동결 전환용 0.18.1/code23을 안내한다. 이 피드의 버전을 현재 본 버전으로 해석하지 않는다.

최신 릴리스에는 다음 파일을 첨부한다.

- `AURA-버전.apk`: 단일 universal APK
- `AURA-0.18.1-bridge.apk`: 보존한 고정 전환용 APK
- `update.json`: 전환용 APK의 버전·주소·크기·해시·최소 Android 버전·변경 내용
- `update-v2.json`: 현재 본 APK의 같은 정보
- `SHA256SUMS.txt`: 수동 확인용 해시
- 대응 소스 ZIP: 앱·네이티브 구성 요소·빌드 안내·라이선스. 서명 키와 개인 설정은 제외한다.

`tools/prepare-release.py --bridge .local/signing-migration/AURA-0.18.1-bridge.apk --notes '<이번 변경>'`는 서명 빌드한 APK에서 배포 묶음을 만든다. 공개 릴리스의 태그는 `v버전`, 두 APK 주소는 해당 태그의 고정 주소를 사용한다. 전환 APK 내용과 해시는 유지한다. GitHub 초안·pre-release는 `latest` 경로에서 제공되지 않으므로 이 채널의 업데이트가 되지 않는다.

APK를 빌드한 버전과 `app/build.gradle`의 versionCode/versionName이 반드시 같아야 한다. 버전 번호는 증가시켜야 한다.

## 서명 유지

공식 배포는 debuggable이 아닌 release APK다. Android 8–12는 기존 인증서를 유지하고 Android 13 이상은 새 배포 인증서와 v3.1 교체 증명을 사용한다. 기존/새 개인 키·암호·lineage는 `.local/signing/`에서 보존하며 소스나 릴리스에 공개하지 않는다. 키나 전환 APK가 없는 체크아웃에서는 백업을 복원해야 하며 새 키를 생성하거나 다른 키로 대체하지 않는다. 상세 절차와 기존 앱 검사기의 전환 이유는 [SIGNING-MIGRATION.md](SIGNING-MIGRATION.md)를 참고한다.

## 검증 범위

새 버전의 정상 APK, 다른 서명, 같은 크기로 변조한 APK, 버전 불일치, 잘못된 메타데이터/HTTPS 주소, FileProvider 노출 범위, 다운로드 기록이 사라졌을 때 내부의 검증 가능한 파일 복원을 검사한다. 시스템 설치 확인을 생략하지 않는다. 실물 폰에서의 제조사별 설치 화면과 다운로드 중 재부팅은 추가 검증 대상이다.

## 첫 공개 릴리스 확인 · 2026-10-18

0.4.0 릴리스: https://github.com/Mobil0010/AURA/releases/tag/v0.4.0

GitHub 배포 API로 APK·update.json·SHA256SUMS.txt·대응 소스 ZIP을 게시했다. 공개 latest 업데이트 주소에서 받은 메타데이터가 로컬 파일과 일치하며, 공개 APK를 다시 내려받아 크기·SHA-256·APK 서명을 확인했다. 브라우저 파일 업로드는 사용하지 않았다. 인증 정보는 Git의 기존 GitHub 인증 도우미에서 프로세스 내로 받아 GitHub API에만 사용하며 파일에 기록하지 않는다.

추가 배포 도구: `tools/publish-release.py --publish`. 같은 버전의 이미 공개된 릴리스를 덮어쓰지 않는다. 새 버전은 먼저 버전 코드 증가·빌드·검증·prepare-release.py 실행을 마친 뒤 게시한다.
