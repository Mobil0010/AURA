# 홈·라이브러리 UI 정리 — 배포 보류

2026-10-27 사용자 요청에 따라 로컬 앱 변경과 검증까지만 완료했다. GitHub 릴리스 게시·배포 파일 준비·공개 업데이트 정보 변경은 하지 않았다. 버전 번호는 공개판과 같은 0.12.0/code16인 로컬 검토 빌드이며, 다음 실제 배포 전에 증가해야 한다. 이 문서는 새 공개 릴리스 안내가 아니다.

## 변경

- 홈: 작은 AURA 표기와 화면 제목, 정사각형 표지의 이어 듣기 카드, 원형 파란 재생 버튼, 가로 앨범 목록과 모두 보기, 곡·앨범·아티스트·폴더 바로가기. 폴더 추가와 Drive 연결은 가벼운 보조 링크로 제공한다.
- 라이브러리: 기본 높이 약 100dp의 상단과 저장소 선택·검색 아이콘. 네 탭은 공통 바탕과 은은한 선택 색을 사용한다. 검색창과 스크롤에 따라 얇아지는 상단의 동작은 유지한다.
- 곡: 52dp 정사각형 표지, 제목·아티스트, 오른쪽 재생 시간과 곡 메뉴를 분리한다. 기존 행과 표지 뷰를 재사용한다.
- 앨범: 표지 중심의 두 열 배치, 간결한 앨범명·아티스트·곡 수. 스크롤 시 앨범 셀과 표지 뷰도 재사용한다.
- 아티스트·폴더: 벡터 아이콘, 곡 수·하위 폴더 수와 둥근 카드. 폴더 경로·로컬/Drive 구분·상위 폴더 이동을 유지한다.
- 미니 플레이어: 테두리 없는 둥근 카드와 원형 재생 버튼을 새 전체 플레이어와 맞춘다. 다크·라이트 모두 적용한다. 실시간 블러나 추가 이미지 읽기 작업은 넣지 않았다.

## 실제 화면

아래는 API 36 ARM64 에뮬레이터에서 실제 네이티브 앱을 실행한 캡처다. 표지·곡명은 원본 검사 픽스처이며 앱에 데모 음악으로 포함하지 않는다.

| 화면 | 다크 | 라이트 |
|---|---|---|
| 홈 | [보기](screenshots/home-library-wip/home-dark.png) | [보기](screenshots/home-library-wip/home-light.png) |
| 곡 | [보기](screenshots/home-library-wip/library-songs-dark.png) | [보기](screenshots/home-library-wip/library-songs-light.png) |
| 앨범 | [보기](screenshots/home-library-wip/library-albums-dark.png) | [보기](screenshots/home-library-wip/library-albums-light.png) |
| 아티스트 | [보기](screenshots/home-library-wip/library-artists-dark.png) | [보기](screenshots/home-library-wip/library-artists-light.png) |
| 폴더 | [보기](screenshots/home-library-wip/library-folders-dark.png) | [보기](screenshots/home-library-wip/library-folders-light.png) |

## 검증

- JDK 17 / 네 ABI의 assembleDebug·assembleDebugAndroidTest·lintDebug 성공. Lint 오류 0 / 경고 43.
- CPU 2코어·RAM 2GB 설정의 Android 16 ARM64 에뮬레이터에서 관련 32개 검사 통과, 165.154초. 시스템 UI 콜드 부팅 창 정리용 환경 검사 1개를 포함한다.
- 홈 바로가기 네 개가 올바른 탭을 여는지, 두 테마의 네 탭과 홈, 표지의 1:1 비율, 앨범 바인딩 때 셀·표지 재사용, 곡 행 1,000회 재사용을 검사했다.
- 실제 손가락 입력으로 곡·앨범·아티스트·폴더·Drive 폴더 목록의 양 끝 접근, 상단 접힘과 복귀, 검색어와 커서 보존, 계층 폴더 이동, 재생·가사·출력 화면 회귀를 검사했다.
- NW-ZX507 실기기 성능을 측정한 결과는 아니다. 오디오 엔진은 수정하지 않았으며 이번 UI 작업에서 전체 오디오 회귀를 반복 실행하지 않았다.
