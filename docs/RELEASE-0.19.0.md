# 2026-10-28 · AURA 0.19.0 공개 배포 완료

사용자 승인으로 https://github.com/Mobil0010/AURA/releases/tag/v0.19.0 에 code25를 게시했다. 홈 모음/백업/오프라인 보관/음향/재생 편의 기능과 태블릿·폴더블 UI, 기존 스타일 복원 및 오른쪽 패널 하단 확장까지 포함한다. 아래 미배포 표기는 당시 검사 시점의 기록이다.

동일한 보존 키와 v3.1 lineage로 release 빌드 및 lintRelease 성공. 공개 0.18.2를 검사용 API28/API36 에뮬레이터에 설치한 뒤 0.19.0을 -r로 무삭제 업데이트하고 실행 성공(Status ok). 실제 NW-ZX507/S25/USB 장치·실계정 Drive는 이번 배포 검사에서 연결하지 않았다. 기능·재생·UI 검사는 아래 개발 빌드 기록을 참고한다.

6개 공개 자산(APK, 전환 APK, 두 피드, 체크섬, 대응 소스 ZIP)을 게시했다. 소스 ZIP 616개 파일 무결성 및 개인 키/로컬 파일 제외 검사 성공. latest/update.json은 동결 전환용 0.18.1/code23, latest/update-v2.json은 0.19.0/code25이며 두 피드 전체를 준비 파일과 대조했다. 두 공개 APK 재다운로드 후 크기·SHA256·서명 검증 성공. 현재 APK 10,622,611바이트, SHA256 `eda93aa97b4a2acde8e5bf038f0e07c91970e8e83008e63a06ddc24f216d9447`. Android 13+ 새 인증서와 Android 8–12 기존 인증서 일치 및 v2/v3/v3.1 검증 성공. 동결 전환 APK 해시는 기존 기록과 동일하다.

로그: `.local/build-release-0.19.0.log`, `.local/lint-release-0.19.0.log`, `.local/release-upgrade-api28.log`, `.local/release-upgrade-api36.log`, `.local/publish-0.19.0.log`, `.local/public-0.19.0-*-signature.log`. 이 게시 후 기록은 로컬에 추가했고 공개 소스 ZIP을 덮어쓰지 않았다.
