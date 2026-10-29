# 코드 스타일

직접 관리하는 앱·테스트·빌드 코드에 적용한다. `app/src/main/cpp/vendor/`의 외부 라이브러리 원본과 Gradle wrapper, 자동 생성 산출물은 해당 도구의 형식을 유지한다.

## 공통

- 들여쓰기는 공백 4칸, 일반적인 줄 길이는 100자를 기준으로 한다.
- 메서드·조건문·반복문·예외 처리·콜백의 구문을 여러 줄로 배치한다. 서로 다른 작업을 세미콜론 하나로 이어 붙이지 않는다.
- 필드와 생성자, 메서드 사이에는 빈 줄을 두어 경계를 구분한다.
- 이벤트 콜백 안에 화면 구성·작업 실행·다음 콜백을 계속 중첩하지 않는다. 검색, 선택창, 백그라운드 작업, 결과 반영을 이름 있는 메서드로 나누고 콜백에는 짧은 호출을 둔다.
- 연속 삼항 연산과 깊은 메서드 체인은 지역 변수나 분기 함수로 풀어 쓴다. 포매터 적용 후에도 코드가 화면 오른쪽으로 밀리면 구조를 먼저 정리한다.
- 문자열·앱 문구·외부 프로토콜·처리 순서를 서식 정리 과정에서 바꾸지 않는다. 긴 문자열이나 임베디드 데이터는 줄 길이 기준의 예외다.
- 큰 클래스의 책임 분리나 함수 추출은 별도의 동작 검증을 포함한 리팩터링으로 수행한다. 서식 정리와 섞지 않는다.

## 포매터와 설정

| 대상 | 포매터 | 저장소 설정 |
| --- | --- | --- |
| Java | google-java-format 1.28.0, AOSP | 아래 옵션 |
| C/C++ | Android NDK 27.2의 clang-format 18 | `.clang-format` |
| Python | Black 25.1.0 | `pyproject.toml` |
| Android XML | Prettier 3.6.2 + @prettier/plugin-xml 3.4.2 | `.prettierrc.json` |
| CMake | cmake-format 0.6.13 | `.cmake-format.json` |
| Gradle·PowerShell·shell | 공백 4칸과 구문별 줄바꿈 | `.editorconfig` |

Java는 import·문자열·Javadoc 내용을 보존하는 다음 옵션으로 정리한다.

```text
--aosp
--skip-sorting-imports
--skip-removing-unused-imports
--skip-reflowing-long-strings
--skip-javadoc-formatting
```

Python 파일을 실행해서 포매팅하지 않는다. Black으로 소스만 처리한다. XML은 `xmlWhitespaceSensitivity=preserve`를 사용해 텍스트 값을 유지한다. shell 파일은 LF 줄바꿈을 사용한다.

## 2026-10-29 정리 범위

Java 133개, C/C++ 9개, Python 5개, XML 18개, Gradle 3개, CMake 1개, PowerShell 1개, shell 1개, 총 171개 파일을 정리했다. 기존 개발 후보의 기능과 0.20.1/code27 버전을 유지하며 공개 배포하지 않았다.

가독성 정리의 동작 보존은 Python AST, XML 요소/속성/텍스트, PowerShell 토큰, 디버그 정보 제거 후 Java 클래스, 네 ABI 네이티브 실행 코드 비교로 확인한다. 빌드·회귀 검사 결과는 [VALIDATION.md](VALIDATION.md)에 기록한다.
