# App-local WireGuard preview — 비공식 Morphe 수정본

Morphe Patches v2.1.0에 YouTube와 YouTube Music용 앱 내부 WireGuard 프록시를
추가한 **비공식 실험 배포판**입니다. MorpheApp 또는 Proton의 공식 제품이 아닙니다.
기존 기반 커밋 `8fa2c7a1a832498de8f179748998a3ca87e198eb`과 해당 버전의
다른 패치는 유지합니다. upstream 기여용 브랜치에서 확인한 WireGuard 관련 수정만
백포팅했으며 최신 upstream 전체를 덮어쓰지 않았습니다.

관리자는 v2.1.1을 실제 Android 기기에서 잘 사용했다고 확인했습니다(2026-11-07).
이 경험과 v2.1.5 자동화 검증은 구분합니다. 실제 YouTube/Music 앱의 UI 확인, 서버 handshake, 재생, DNS 경로 및
Wi-Fi/모바일 전환은 독립된 화면 하네스 검증과 구분하며 새로 실행한 것으로
간주하지 않습니다. [이번 변경 및 검증 기록](docs/wireguard-i18n-2.1.5.md)과
[기기 검증 절차](docs/wireguard-device-acceptance.md)를 참고하세요.

## v2.1.5 변경

- 진입 메뉴와 내부 화면의 언어가 달라질 수 있던 문자열 조회 경로를 수정합니다.
  내부 제목·설정 행·상태·대화상자·토스트는 현재 화면의 언어 컨텍스트를 사용하며
  다른 화면에서 먼저 캐시한 영어 문자열을 재사용하지 않습니다.
- 기존 Morphe 언어/재시작 규칙, 8개 언어 번역, 암호화된 프로필과 전송 구조는 유지합니다.

## v2.1.4 변경

- 한국어·일본어·중국어 간체/번체·스페인어·프랑스어·독일어·브라질 포르투갈어의
  WireGuard 설정 및 오류 문자열 60개를 추가했습니다. 다른 Morphe 설정과 같은
  Android 리소스와 앱 언어 설정을 사용하며, 언어 변경에는 기존 재시작 규칙이 적용됩니다.
- 네이티브 검증 오류 19개를 고정 코드로 전달하고 Java에서 허용된 리소스 키로만
  변환하여 번역합니다. 알 수 없거나 이전 형식인 오류는 일반 오류로 표시하며
  원시 백엔드 메시지나 프로필 값을 화면에 노출하지 않습니다.
- 이 번역은 관리자가 요청한 커스텀 배포판용 추가입니다. 공식 기여 브랜치는
  영어 원문 및 Crowdin 번역 정책을 유지하며 이 번역 파일을 포함하지 않습니다.
- 암호화된 프로필 형식·키 별칭·전송 구조와 아래의 v2.1.3 수정 사항을 유지합니다.

## 유지된 v2.1.3 수정

- 가져오기 실패를 파일 접근·UTF-8/크기·설정 검증·엔진 로딩·암호화 저장 단계로
  구분합니다. v2.1.4에서는 네이티브 검증 사유도 리소스로 번역합니다.
- `PersistentKeepalive = off`와 CIDR을 생략한 숫자 `Address`·`AllowedIPs`를
  지원합니다. 생략한 경로는 IPv4 `/32`, IPv6 `/128`인 단일 호스트 경로입니다.
- 다른 Morphe 설정 페이지와 같은 테마·툴바·Preference 행과 활성화 스위치를
  사용합니다. 기존 프로필 가져오기·검증·암호화 저장·확인 대화상자·재시작 동작은
  유지합니다. 스위치는 저장된 모드만 표시하며 확인 없이 설정을 저장하지 않습니다.
- 잘린 plain HTTP 응답 본문을 정상 응답으로 끝내지 않고 연결을 중단합니다.
  WireGuard 종료 전 peer를 제거하여 수신 처리와 TUN 종료 사이에서 관측된 데이터 경쟁을 수정합니다.
- 프로필 존재 확인이 AtomicFile의 백업 복구를 사용하고, 네이티브 시작 실패의
  소켓·엔진 정리와 임시 인증 난수 버퍼 처리를 보강합니다. 암호화 형식과 키 별칭은
  바뀌지 않으므로 기존 프로필을 계속 읽도록 구성했습니다.
- Kotlin/Java/Go 소스에서 정상 Gradle 경로로 새 번들을 빌드합니다. Go·NDK 버전을
  고정하고 두 ABI의 ELF/JNI를 검사하며 필요한 외부 고지를 APK 설정 화면에 제공합니다.
  이전 릴리스의 컴파일된 Boolean 값을 바꾸는 배포 방식을 사용하지 않습니다.

YouTube 및 Music의 `WireGuard proxy (experimental)` 패치는 **기본 선택**입니다.
앱 내부 `WIREGUARD_ENABLED`의 기본값은 계속 `false`입니다. 각 앱에서 호환되는
`.conf`를 가져와 명시적으로 활성화하고 앱을 재시작해야 연결을 시작합니다.

## 구현과 지원 범위

기존 `Network proxy` 패치가 가로채는 Cronet 및 확장 Requester 요청을,
`127.0.0.1`의 인증된 앱 내부 HTTP 프록시로 보냅니다. 프록시는 사용자 공간
TCP/IP 스택과 공식 `wireguard-go`를 통해 WireGuard 서버로 전달합니다.
`VpnService`, 시스템 TUN, root, 별도 WireGuard 앱은 사용하지 않으며 시스템 VPN
슬롯을 점유하지 않습니다. `.mpp`는 설치 앱이 아니므로 Morphe에서 대상 앱을 패치하세요.

| 항목 | 범위 |
| --- | --- |
| YouTube | 기반 버전의 선언된 대상 중 20.47.62 이상; 더 오래된 버전은 WireGuard 설치를 건너뜀 |
| YouTube Music | 9.15.51 / 9.20.53 / 9.30.53; 실험 9.42.52 / 9.43.51 / 9.44.52 |
| APK ABI | arm64-v8a, x86_64; 32비트 ABI를 포함한 APK와 root mount 설치 미지원 |
| 프로필 | 단일 Interface/Peer, UTF-8, 최대 64 KiB |
| Endpoint 및 DNS | 숫자 IP; DNS가 AllowedIPs 및 인터페이스 주소 계열과 일치해야 함 |
| 통신 | HTTP 및 TCP CONNECT; 해당 Cronet builder의 QUIC 비활성화 |
| 실패 | 가로챈 요청을 일반 인터넷 직접 연결로 fallback하지 않음 |
| 설정 변경 | 프로필 변경·활성화·비활성화 후 앱 재시작 |

선언된 호환 버전은 실제 기기 검증 결과가 아닙니다. 모든 앱 요청을 가로챈다거나
완전한 kill switch를 제공한다고 보장하지 않습니다. 패치하지 못한 네이티브 요청,
WebView, Cast, GmsCore 및 별도 프로세스는 추가 검증 대상입니다. 기존 시스템 VPN이
있으면 WireGuard의 바깥쪽 UDP 연결이 그 VPN을 통과할 수 있습니다.

각 앱은 독립된 설정·암호화 프로필을 사용합니다. 두 앱을 함께 사용할 때는 서버에서
각각 발급한 **개인키가 서로 다른 프로필**을 사용하세요. 같은 서버에서 같은 peer
키를 동시에 사용하면 서버의 endpoint 갱신이 서로 충돌할 수 있습니다.

## 보안과 수명주기

- `.conf`는 구문 검증 후 AndroidKeyStore의 AES-256-GCM으로 암호화하여
  `noBackupFilesDir`에 원자적으로 저장합니다. Morphe 설정 내보내기에 넣지 않습니다.
  선택한 원본 문서는 그대로 남으며 Java/Go 문자열의 완전한 메모리 삭제는 보장하지 않습니다.
- loopback 프록시는 프로세스마다 새 인증값을 사용합니다. 키·프로필·인증정보는 로그에
  남기지 않습니다. 실패 시 로컬 차단 프록시를 유지하고 Cronet 설정 적용에 실패하면
  해당 엔진 생성을 실패시킵니다.
- 비활성화 상태에서는 네이티브 엔진이나 WireGuard 네트워크 작업을 시작하지 않습니다.
  프로필 가져오기/검증은 명시적인 사용자 동작입니다. 저장된 활성화 값만으로
  WireGuard 패치를 제거한 앱에서 엔진을 다시 시작하지 않습니다.
- `local proxy ready`는 로컬 리스너가 준비되었다는 뜻이며 원격 handshake 성공이 아닙니다.
  연결 실패가 직접 연결로 모드를 바꾸지 않습니다. 자동 네트워크 전환은 보장하지 않으며,
  바깥쪽 UDP 소켓이 복구되지 않으면 앱을 재시작해야 할 수 있습니다.

## 소스 빌드와 검증

[공개 소스 빌드 문서](build-support/PUBLIC_SOURCE_BUILD.md)에 고정 도구·의존성,
일반 Gradle 빌드 및 GitHub 토큰이 필요 없는 composite build 명령을 기록했습니다.
네이티브 payload는 Gradle의 `processResources` 전에 소스에서 자동으로 만듭니다.
Go 1.26.10, Android NDK 28.2.13676358, API 26이 필요합니다. 산출물은
`native/wireguard/build/android/<abi>/libmorphewireguard.so`와
`patches/build/libs/patches-2.1.5.mpp`입니다.

최종 번들 생성 뒤 JVM/Android DEX 기본 선택을 모두 확인합니다.
`scripts/set-wireguard-defaults.py verify`는 검사용으로만 사용할 수 있습니다.
역사적인 v2.1.2 재패키징용 `repair`는 v2.1.5 빌드 경로가 아닙니다.

[네이티브 설명](native/wireguard/README.md)에 프로필 문법, Go/JNI 테스트 및
실패 정책을, [기기 체크리스트](docs/wireguard-device-acceptance.md)에 **예상 결과**와
수동 재현 절차를 기록했습니다. 실행하지 않은 항목은 검증 완료로 표시하지 않습니다.
기존 [v2.1.2 기본 선택 수정 기록](docs/wireguard-default-verification.md),
[v2.1.0 Music 검증](docs/wireguard-music-verification.md),
[초기 YouTube 검증](docs/wireguard-verification.md)은 역사 기록입니다.

## 라이선스와 배포

기반 프로젝트의 [GPL-3.0 LICENSE](LICENSE)와 추가 Section 7 [NOTICE](NOTICE)를
보존합니다. WireGuard(MIT), gVisor(Apache-2.0), Go/BSD 계열 의존성의 정확한 버전과
고지는 [native/wireguard/licenses/SOURCES.md](native/wireguard/licenses/SOURCES.md)에
있습니다. 고지는 번들과 패치된 APK에 포함되며 WireGuard 설정의 Open source licenses에서
열 수 있습니다. 구현 의존성을 Git 패치에 vendoring하지 않고 go.mod/go.sum으로 고정합니다.
전체 소스 ZIP에는 해당 소스와 빌드용 공개 의존성 및 필요한 고지를 함께 제공합니다.
YouTube APK, SDK/도구체인, 계정·개인키·실제 프로필은 배포하지 않습니다.
