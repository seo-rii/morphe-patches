# App-local WireGuard preview — 비공식 Morphe 패치 실험

이 저장소는 Morphe의 공식 배포판이 아닌 **로컬 실험용 수정본**입니다. Proton의
공식 제품이나 플러그인도 아닙니다. 관리자는 기존 v2.1.1의 실제 Android 기기
사용 성공을 확인했습니다(2026-11-07). v2.1.2는 기본 선택만 수정했으며 새
기기 실행·누출 측정은 수행하지 않았습니다. 시스템 수준의 kill switch는 제공하지 않습니다.

- 기반 저장소: <https://github.com/MorpheApp/morphe-patches>
- 기반 커밋: `8fa2c7a1a832498de8f179748998a3ca87e198eb` (v2.1.0)
- 추가 패치: `WireGuard proxy (experimental)` — YouTube/Music 각각 하나, **기본 선택**
- Music 확장 검증: [docs/wireguard-music-verification.md](docs/wireguard-music-verification.md)
- 이전 YouTube 전용 빌드 기록: [docs/wireguard-verification.md](docs/wireguard-verification.md)
- 공개 소스 빌드 구성: [build-support/PUBLIC_SOURCE_BUILD.md](build-support/PUBLIC_SOURCE_BUILD.md)
- 기기 검증 체크리스트: [docs/wireguard-device-acceptance.md](docs/wireguard-device-acceptance.md)

## v2.1.2 — Android 기본 선택 수정

기존 v2.1.1은 JVM 클래스와 목록 JSON만 기본 선택으로 바뀌고 Android DEX는
`default = false`로 남아 있었습니다. v2.1.2는 실제 Manager가 읽는 DEX의
YouTube 및 YouTube Music 기본 선택 인자를 모두 `true`로 수정합니다.

`scripts/set-wireguard-defaults.py`는 배포된 v2.1.1의 SHA-256을 확인한 뒤 두
DEX Boolean, DEX 헤더 해시 및 번들 버전/시각만 변경합니다. 네이티브 엔진과
앱 확장 DEX는 기존 배포본과 같습니다. Kotlin 소스, 메타데이터 테스트 및 JVM
로더 검사의 기대값도 기본 선택과 일치합니다. 이 갱신분은 전체 Gradle 재빌드나
새 기기 실행 시험을 수행한 결과가 아닙니다. 기존 v2.1.1의 기기 사용 성공은
관리자가 확인했습니다(2026-11-07).

앱 안의 `WIREGUARD_ENABLED`는 계속 `false`이므로 프로필을 가져와 직접
활성화할 때까지 WireGuard 연결은 시작하지 않습니다. 전문가 모드의 기존
저장 선택이 있으면 **선택 항목 → 추천 패치**를 한 번 적용합니다.

- v2.1.2 번들 SHA-256: `179132ea77ee7ce8f5afc3c595f0aff2edf9ff19362ba4d40e3396c4cbe0864f`
- 검증 및 재현 명령: [docs/wireguard-default-verification.md](docs/wireguard-default-verification.md)

## YouTube Music 지원 확장 — 2026-11-07

YouTube용 기능을 유지하면서 Music용 패치와 공통 설치 코드를 추가했습니다.
네이티브 엔진과 Java WireGuard 설정 화면은 동일하며, 각 앱의 네트워크 초기화와
설정 화면에 각각 연결합니다. 전달 파일은
`morphe-wireguard-youtube-music-preview-2.1.0.mpp`이며 별도 설치용 APK가 아닙니다.
새 소스 디렉터리에서 Kotlin·Android Java 컴파일, 단위 테스트 13개, 실제 Morphe
로더 검사와 Android SDK 기반 최종 번들 검사를 완료했습니다. 로더는 패치 202개와
앱별 WireGuard 패치 2개를 확인했습니다. Music 확장 DEX에는 `Requester` 연결 호출
36개가 있고, 직접 `URL.openConnection/openStream` 호출은 없었습니다. 이 수치는
확장 모듈에 대한 검사이며 원본 앱 전체의 통신을 검증했다는 뜻은 아닙니다.

- 번들 크기: 24,127,235 bytes
- SHA-256: `c9fb32a42ff7d6228c86d3bd113398f2e21b253b0e3b292d741a4d3b5bbbbf19`

상세한 실행 결과와 미검증 범위는 Music 검증 문서에 기록했습니다.

Music 확장 기능 8개 파일에서 프록시를 우회하던 직접 HTTP 요청 16곳도 기존
`Requester`를 사용하도록 변경했습니다. 가사 제공자·메타데이터·발음 사전·AI 가사
요청 등이 포함되며, 요청 헤더·본문·타임아웃은 유지했습니다.

빌드 검증은 **실제 APK에 대한 fingerprint 매칭·패치 실행이나 Android에서의 DEX
실행 검증이 아닙니다.** 원본 APK와 테스트 기기·프로필이 제공되지
않아 그 단계는 남아 있습니다. Morphe Manager에서 이 unsigned 로컬 번들을
가져오는 동작도 실행하지 않았습니다. 소스 ZIP은 수정본과 사용한 공개 빌드
의존성 소스, 두 네이티브 파일을 포함하며, 빌드 도구나 YouTube APK는 제외합니다.

## 구현 범위

사용자가 가져온 WireGuard `.conf`를 이용해, 기존 Network proxy 패치가 가로채는
Cronet 및 확장 Requester 요청을 앱 내부의 인증된 HTTP 프록시로 보냅니다.
프록시는 사용자 공간 TCP/IP 스택과 공식 `wireguard-go` 엔진을 통해 해당 요청을
암호화하여 지정한 WireGuard 서버로 전달합니다.

Android `VpnService`, 시스템 TUN 인터페이스, root, `wg-quick`, 별도 VPN 앱은
사용하지 않습니다. 시스템 VPN 슬롯을 점유하지 않으며 VPN 연결 승인 화면을
추가하지 않습니다. 파일 선택은 시스템 문서 선택기이고, 광범위한 저장소 권한을
추가하지 않습니다. 대상 앱의 기존 인터넷 권한은 필요합니다.

Proton 앱 전체를 이식하지 않았습니다. Proton이 내보낸 표준 WireGuard 프로필이나
다른 서버의 호환 프로필을 사용하며, 로그인·서버 목록·구독 관리·자동 서버 선택은
범위 밖입니다. 계정이나 실제 프로필은 이 프로젝트에 포함하지 않았습니다.

## 현재 제약

| 항목 | 현재 범위 |
| --- | --- |
| YouTube | Morphe의 호환 목록 중 20.47.62 이상 |
| YouTube Music | 선언된 일반 대상 9.15.51 / 9.20.53 / 9.30.53 |
| Music 실험 대상 | 9.42.52 / 9.43.51 / 9.44.52 — 실제 APK 검증 결과가 아닌 메타데이터 |
| APK ABI | `arm64-v8a`, `x86_64`; 32비트 ABI를 포함한 APK는 거부 |
| 설치 | 일반 APK 패치·설치; root mount 설치 방식은 미지원 |
| 프로필 | `[Interface]` 하나와 `[Peer]` 하나, UTF-8, 최대 64 KiB |
| Endpoint | 숫자 IPv4 또는 `[IPv6]:port`; 호스트명 미지원 |
| DNS | 숫자 IP를 명시해야 하며 `AllowedIPs`와 주소 계열 조건을 만족해야 함 |
| 앱 트래픽 | HTTP 및 TCP CONNECT; QUIC는 해당 Cronet builder에서 강제 비활성화 |
| 실패 정책 | 가로챈 요청은 직접 연결로 fallback하지 않음 |
| 설정 변경 | 활성화·비활성화·프로필 교체 후 앱 재시작 필요 |

`Address`, `PrivateKey`, `PublicKey`, `Endpoint`, `AllowedIPs`, `DNS`를 지원하고,
`MTU`, `ListenPort`, `PresharedKey`, `PersistentKeepalive`는 선택 항목입니다.
`PostUp` 등의 셸 명령과 알 수 없는 필드는 실행하거나 무시하지 않고 거부합니다.
정확한 문법은 [native/wireguard/README.md](native/wireguard/README.md)를 참고하세요.

**앱의 모든 통신을 포괄한다는 보장은 없습니다.** 패치되지 않은 네이티브 경로,
WebView, 외부 GmsCore 프로세스, Cast 등의 요청은 별도 검증이 필요합니다. DNS
터널링과 직접 연결 금지는 이 프록시가 실제로 받은 요청에 대한 정책입니다.
기존 시스템 VPN이 있으면 WireGuard 바깥쪽 UDP 연결이 그 VPN을 통과할 수 있습니다.

두 앱의 설정·프로필은 별도입니다. **두 앱을 동시에 사용할 때는 서버/서비스에서
각각 발급한, 개인키가 서로 다른 프로필을 사용하세요.** 같은 서버에 같은 키로
두 앱이 동시에 연결하면 WireGuard의 peer endpoint 갱신 특성상 서로의 접속 주소를
번갈아 덮어쓸 수 있습니다. 이는 [공식 roaming 설계](https://www.wireguard.com/#built-in-roaming)에
근거한 주의사항이며, 이번에 실기기로 재현한 결과는 아닙니다.

## 설정과 보안 동작

- `.conf` 가져오기는 구문 검증 후 AndroidKeyStore의 AES-256-GCM 키로 암호화하여
  `noBackupFilesDir`에 원자적으로 저장합니다. 설정 내보내기에 포함하지 않습니다.
- 원본 `.conf`는 삭제하지 않습니다. 문서 선택기에 제공한 원본 파일은 사용자가
  별도로 관리해야 합니다. 실행 중 메모리의 Java/Go 문자열까지 완전 삭제한다고
  보장하지는 않습니다.
- 프록시는 `127.0.0.1`의 임시 포트만 사용하고, 프로세스마다 새 무작위 인증값을
  만듭니다. loopback 포트는 다른 앱도 접근할 수 있으므로 인증은 필수입니다.
- 네이티브 엔진이나 프로필 로딩 실패 시, 로컬 거부 전용 프록시를 유지해
  가로챈 요청을 차단합니다. 프록시 설정 자체를 적용하지 못하면 해당 Cronet
  엔진 생성을 실패시킵니다.
- 비활성화 시 WireGuard 네트워크 작업이나 네이티브 엔진을 시작하지 않습니다.
  패치를 제거한 APK에서는 저장된 활성화 값만으로 엔진이 재활성화되지 않습니다.
- UI의 `local proxy ready`는 로컬 리스너가 준비되었다는 뜻입니다. 실제 서버와의
  handshake 또는 인터넷 연결 성공을 뜻하지 않습니다.

## 빌드

네이티브 라이브러리는 Go와 Android NDK로, 패치 번들은 upstream의 Gradle 빌드로
각각 만듭니다. 패치 번들을 먼저 빌드하면 라이브러리가 포함되지 않으므로 순서에
주의하세요.

```sh
# 저장소 루트, Linux/macOS. 설치한 도구의 실제 경로를 사용합니다.
export ANDROID_NDK_HOME=/path/to/android-ndk-r28c
export WIREGUARD_GO_BIN=/path/to/go/bin/go
bash scripts/build-wireguard-android.sh

# 기존 Morphe 빌드 환경과 의존성 접근 권한을 준비한 뒤 실행합니다.
./gradlew :patches:buildAndroid

# 번들 생성과 실제 Morphe JVM 로더 검사, Android 입력 파서 단위 테스트
./gradlew :patches:verifyWireGuardBundleLoading \
  :extensions:shared-youtube:library:testDebugUnitTest

# Android SDK가 제공하는 dexdump로 최종 번들 내용 검사
python3 scripts/verify-wireguard-bundle.py patches/build/libs/patches-2.1.1.mpp \
  --dexdump "$ANDROID_HOME/build-tools/36.0.0/dexdump"
```

출력 네이티브 파일은
`native/wireguard/build/android/<abi>/libmorphewireguard.so`입니다.
`processResources`가 이 파일을 번들에 넣고, 패치는 입력 APK에 원래 존재하는
지원 ABI에 맞춰 복사합니다. 누락되었거나 잘못된 ELF 파일이면 패치를 중단합니다.
`.mpe`에 네이티브 라이브러리가 자동 병합될 것이라고 가정하지 않습니다.

이 환경에서는 GitHub Packages 자격 증명 대신 공개 소스 composite build로
실제 빌드했습니다. 전달 ZIP을 풀면 `morphe-wireguard`의 형제 디렉터리에 사용한
공개 의존성 소스가 들어 있습니다. 이 경우 위 Gradle 명령에
`--init-script build-support/public-sources.init.gradle`을 추가합니다.
정확한 revision, SDK 및 재빌드 절차는
[빌드 구성 문서](build-support/PUBLIC_SOURCE_BUILD.md)에 명시합니다.
다른 환경에서 다운로드 접근성이나 산출물의 바이트 단위 동일성까지 보장하지는
않습니다. ZIP에 포함한 `.so`를 그대로 쓸 때는 네이티브 빌드를 생략할 수 있습니다.

## 테스트 명령

```sh
cd native/wireguard
go test -race -count=1 ./...

# Linux netlink가 제한된 환경에서 독립적으로 실행 가능한 부분.
# 이 명령의 성공은 실제 UDP 통합 테스트 성공을 의미하지 않습니다.
go test -race -count=1 -run '^(TestMemory|TestIdle|TestValidate|TestAllowedIPs|FuzzValidate)' ./...

# 저장소 루트에서 JDK 17+ 및 호스트 C 컴파일러를 사용합니다.
WIREGUARD_JAVA_HOME=/path/to/jdk bash scripts/test-wireguard-jni.sh --validate-only
# UDP/netlink 사용이 허용된 일반 Linux 호스트에서는 전체 JNI 수명주기도 검사합니다.
WIREGUARD_JAVA_HOME=/path/to/jdk bash scripts/test-wireguard-jni.sh
```

실제로 실행하지 않은 기기 동작은 **예상 결과**로만 취급하세요. 적용 후 예상
진입 경로는 각 앱의 Morphe 설정 → Miscellaneous → WireGuard proxy입니다.
프로필을 가져온 뒤 `Enable and restart`를 누르도록 구현되어 있으나, 실제 APK에서
이 메뉴와 동작이 정상인지 검증하는 단계는 남아 있습니다.

## 라이선스와 출처

upstream [LICENSE](LICENSE)와 [NOTICE](NOTICE)를 그대로 보존합니다. 이 수정본은
공식 Morphe 배포판과 구별되도록 표시했습니다. WireGuard 엔진은 MIT,
gVisor는 Apache-2.0 등 각 의존성의 라이선스가 별도로 적용됩니다.
제공된 `native/wireguard/vendor`에는 네이티브 라이브러리 재빌드에 필요한 Go
의존성 소스와 해당 고지가 포함됩니다. 도구체인과 Android SDK는 별도입니다.
이 프로젝트는 YouTube APK를 포함하거나 그 재배포 권리를 부여하지 않습니다.
