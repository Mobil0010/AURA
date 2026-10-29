# AURA 0.5.1 · Android 9 업데이트 서명 조회 수정

NW-ZX507에서 앱 내부 업데이트 중 `APK 서명을 확인할 수 없습니다.`가 표시된 보고에 대응했다. Sony는 이 모델의 OS를 [Android 9](https://www.sony.com/electronics/support/audio-systems-nw-nwzx-series/nw-zx507/specifications)로 명시한다.

기존 코드는 API 28 이상에서 GET_SIGNING_CERTIFICATES만 요청했다. [Android 9 PackageManager 소스](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-9.0.0_r47/core/java/android/content/pm/PackageManager.java)의 getPackageArchiveInfo는 GET_SIGNATURES 플래그가 있어야 collectCertificates를 수행한다. 따라서 유효하게 서명된 APK도 조회 결과에 서명 정보가 없어 앱에서 거부할 수 있다. 최신 Android에서만 수행한 이전 검사는 이 차이를 검증하지 못했다.

API 28 이상에서 두 플래그를 함께 요청하고, 현재 서명 정보가 있으면 그것을 우선 사용한다. 현재 서명 필드가 비어 있는 호환 구현에서는 함께 요청한 레거시 서명 필드를 읽는다. 두 필드가 모두 비어 있으면 기존처럼 거부한다. APK 크기·SHA-256·패키지·버전·최소 Android 버전·설치 앱과 같은 서명 확인은 유지한다. 서명 키를 바꾸거나 서명 확인을 생략하지 않는다.

업데이트 검사 코드를 포함한 이전 버전은 이 수정판도 앱 안에서 거부할 수 있다. 이 경우 [GitHub 릴리스](https://github.com/Mobil0010/AURA/releases/tag/v0.5.1)의 AURA-0.5.1.apk를 브라우저로 내려받아 기존 앱 위에 설치한다. 앱을 삭제할 필요는 없다. 이 수정판 설치 후의 업데이트에 새 검사 코드가 적용된다.

NW-ZX507 실물 검증은 수행하지 못했다. 원인 근거는 사용자의 기기별 증상, 앱의 동일 오류 문구, Android 9 원본 구현과 앱 조회 플래그의 불일치다. 현대 Android의 회귀 검사와 레거시 필드만 있는 PackageInfo 검사 결과는 검증 문서에 기록한다.
