# 네이티브 소스와 재빌드

libsoxr는 `libsoxr.so` 공유 라이브러리로, USB 백엔드는 libusb를 포함한 `libaura_audio.so`로 빌드한다. 각 라이브러리의 원본 소스와 라이선스는 `app/src/main/cpp/vendor/`에 포함돼 있다. AURA JNI/USB 코드와 CMake 파일도 함께 제공한다.

- Android NDK 27.2.12479018, CMake 3.22.1
- `./gradlew assembleDebug`로 ARM64 / ARMv7 / x86 / x86_64를 재빌드할 수 있다.
- 빌드 산출물은 app/build/intermediates/cxx의 각 ABI obj 디렉터리에 생성된다.
- 사용자가 수정한 LGPL 라이브러리로 재링크/재빌드한 APK를 자신의 디버그 키로 설치할 수 있다. AURA는 변경된 라이브러리 실행을 막는 검증을 하지 않는다.
- 16 KB 페이지를 사용하는 Android 기기를 위해 ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON으로 빌드한다.
- SoX CMake 수정: 공백이 있는 경로에서 실패하는 LSR test 조건 비활성화, 하위 프로젝트 기준 CMake module 경로 사용.
- APK에도 THIRD_PARTY_NOTICES.txt를 포함했다.
