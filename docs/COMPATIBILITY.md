# 호환성 원칙

어떤 앱도 기기가 제공하지 않는 샘플레이트, 비트 깊이, Native DSD, USB Host, 전용 제조사 출력 API를 새로 만들 수 없다. AURA는 특정 모델명에 의존하지 않고 표준 기능을 활용한다. 그러나 모든 폰·DAC의 모든 출력 모드를 보장하지 않는다.

| 경로 | 조건 | 미지원 시 |
|---|---|---|
| Android PCM | Android 8+, 시스템 디코더/AudioTrack 지원 | 원본 자동 비트 깊이는 실제 생성 실패도 감지해 16bit 재시도. 원본 레이트 자동 모드는 필요 시 48 kHz로 변환. 명시한 미지원 설정은 오류 |
| Hi-Res | AudioTrack이 요청 포맷을 열 수 있음 | 사용자가 지원 형식으로 변경; 하드웨어 내부 리샘플링은 보장하지 않음 |
| 공식 비트퍼펙트 | Android 14+, 해당 USB 장치의 BIT_PERFECT mixer attribute 및 원본 포맷 | 강제 변환 없이 오류로 중단 |
| USB 직접 PCM | USB 권한, UAC1/UAC2 표준 PCM AS 인터페이스, 호환 클럭·패킷 구성 | 오류 안내, Android 경로로 선택 변경 |
| DSD→PCM | 비압축 DSF / DSDIFF, 최대 2채널, 지원 비트레이트, 캐시 공간 | DST 압축/비표준/큰 파일은 오류 |
| DoP | DSD 가능 DAC, USB 직접 + 비트퍼펙트, 필요한 PCM24 전송률 | 미지원 형식 오류. 포장 바이트는 검증했지만 실제 DAC 재생 검증은 남아 있음 |
| Native DSD | 제조사별 인터페이스/포맷/모드 전환 규격 필요 | 이 버전에서는 **미지원**. PCM / DoP 사용 |
| Cast | Google Play 서비스, 같은 네트워크의 Google Cast 수신기, 지원 코덱 | 발견/로드 오류 안내 |
| DLNA | UPnP MediaRenderer + AVTransport, 지원 코덱 | 검색/제어 오류 안내 |

## USB 범위

표준 UAC1 및 UAC2 PCM alternate interface를 descriptor로 검색한다. 채널/해상도/컨테이너 크기, UAC1의 샘플레이트, UAC2 clock source, 등시성 OUT endpoint, 피드백 정보를 확인한다. 비동기 전송에서 명시적 피드백이 없는 implicit-feedback 장치, 제조사 전용 제어, 현재 선택된 clock source 이외로의 자동 클록 변경, clock multiplier, native DSD quirk는 지원하지 않는다. libusb는 앱이 권한을 얻어 연 USB FD를 사용하며 루트 권한을 요구하지 않는다.

직접 출력은 실기기 검증 전 실험 단계다. 오류가 없었다는 사실을 실제 DAC에서 비트가 동일하다는 증거로 사용하지 않는다. USB 전송 실패 시 시스템 경로로 몰래 바꾸지 않는다.

## 캐스트와 DSP

Cast/DLNA는 로컬 음원의 원본 또는 SoX/EQ 설정으로 미리 렌더링한 WAV를 Wi-Fi로 제공한다. WAV 준비에는 시간과 캐시 공간이 필요하며 설정은 렌더링 시작 시 고정된다. Drive 음원은 폰이 인증된 원본 구간을 중계하며 EQ·SoX·ReplayGain을 적용하지 않는다. 이미 캐스트 중인 곡에 설정을 즉시 갱신하는 실시간 인코딩 경로는 없다. 수신 기기의 EQ/리샘플링도 앱에서 검증할 수 없다. 재생 기기를 옮길 때 로컬 파일 접근성과 대기열 상태를 이전하는 완전한 Spotify Connect 구현은 아니며, LAN 리모컨은 재생 명령/상태 제어를 제공한다.

## Google Drive

Google Play 서비스 및 Google 계정, 인터넷 연결, 등록된 앱 서명이 필요하다. Android OAuth 클라이언트는 배포 APK의 패키지와 SHA-1로 연결하며, 현재 개인 테스트 등록은 허용된 테스트 계정만 사용할 수 있다. 파일은 1 MiB 이하 Range 요청으로 스트리밍한다. 디코더 지원은 기기에 따라 다르다. Google Drive의 DSF/DFF와 바로가기는 지원하지 않는다. Cast/DLNA 전송은 0.15.0부터 원본 중계로 지원하며 폰과 수신 기기는 같은 Wi-Fi에 있어야 한다. 0.19.0부터 사용자가 선택한 음원을 앱 내부에 오프라인 보관할 수 있다. 완료한 사본은 로그인/네트워크 없이 기기에서 재생할 수 있지만 미완료 다운로드의 프로세스 종료 후 자동 재개는 없다. Google 계정 없는 에뮬레이터의 모의 HTTP 검사와 실제 계정/물리 기기 검증은 구분한다.
