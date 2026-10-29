# 네이티브 소스와 재빌드

libsoxr는 `libsoxr.so` 공유 라이브러리로, USB 백엔드는 libusb를 포함한 `libaura_audio.so`로 빌드한다. 각 라이브러리의 원본 소스와 라이선스는 `app/src/main/cpp/vendor/`에 포함돼 있다. AURA JNI/USB 코드와 CMake 파일도 함께 제공한다.

- Android NDK 27.2.12479018, CMake 3.22.1
- `./gradlew assembleDebug`로 ARM64 / ARMv7 / x86 / x86_64를 재빌드할 수 있다.
- 빌드 산출물은 app/build/intermediates/cxx의 각 ABI obj 디렉터리에 생성된다.
- 사용자가 수정한 LGPL 라이브러리로 재링크/재빌드한 APK를 자신의 디버그 키로 설치할 수 있다. AURA는 변경된 라이브러리 실행을 막는 검증을 하지 않는다.
- 16 KB 페이지를 사용하는 Android 기기를 위해 ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON으로 빌드한다.
- SoX CMake 수정: 공백이 있는 경로에서 실패하는 LSR test 조건 비활성화, 하위 프로젝트 기준 CMake module 경로 사용.
- APK에도 THIRD_PARTY_NOTICES.txt를 포함했다.

## Windows의 한글 경로에서 빌드

프로젝트 또는 CMake의 현재 작업 디렉터리에 한글이 있으면 Android 플러그인의 경로 검사에 걸리며, 검사만 해제해도 CMake가 NDK 도구 검색 중 비정상 종료할 수 있다. 소스/출력 경로만 영문으로 지정해도 실행 작업 디렉터리가 한글이면 해결되지 않는다.

프로젝트 루트에서 다음 명령을 실행한다. JDK 17 이상과 Android SDK가 필요하다. `JAVA_HOME`이 없으면 Android Studio의 JBR, `ANDROID_HOME`이 없으면 `ANDROID_SDK_ROOT` 또는 기본 Android SDK 위치를 사용한다.

```powershell
.\tools\build-windows.ps1
# 디버그 APK만 필요할 때
.\tools\build-windows.ps1 -Variant Debug
# 계측 테스트 APK도 함께 빌드
.\tools\build-windows.ps1 -Variant Debug -AndroidTests
```

스크립트는 영문 TEMP 아래 고유한 빌드 폴더에 app/Gradle 입력만 복사해 실행한다. 원본 경로와 gradle.properties는 변경하지 않고, .local·개인 키·기존 빌드 캐시는 복사하지 않는다. 네 ABI와 16KB 페이지 설정, CMake 3.22.1/NDK 버전도 유지한다. 기본 실행은 debug/release APK와 lintDebug를 빌드한다. 실패 시 재시도를 위해 임시 폴더와 `.local/build-windows.log`를 유지한다.

성공하면 APK를 원본의 `app/build/outputs/apk/debug/app-debug.apk`와 `app/build/outputs/apk/release/app-release-unsigned.apk`로 복사하고 SHA-256을 출력한다. lint 보고서는 `app/build/reports/`로 복사한다. 디버그 APK는 로컬 디버그 인증서이며 공개 앱의 업데이트 호환성을 보장하지 않는다. release APK는 미서명이다. 공개 배포에는 기존 보존 키를 사용한 별도 서명과 검증이 필요하다. 이 스크립트는 배포 키를 생성하거나 공개 배포하지 않는다.
