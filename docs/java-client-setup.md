# 공식 Java Client 연결

M1-03은 Spring Boot에서 Elasticsearch Java Client·Rest5 9.4.5를 구성한다. 엔진은 [로컬 실행 안내](local-elasticsearch.md)의 Elasticsearch·Nori 9.4.7이다. 상품 색인과 검색 API는 후속 작업이다. 실제 검증 결과는 [작업 기록](worklog.md)을 기준으로 확인한다.

## 실행 설정

기본 실행은 ES 연결을 활성화하지 않는다. 이 상태의 `check`는 엔진이나 로컬 인증 파일을 요구하지 않는다. ES 연결을 활성화하면 URL·사용자명·비밀번호 파일·CA 파일을 모두 검증한다.

IntelliJ의 Project SDK와 Gradle JVM은 JDK 21로 지정한다. 실행 구성의 환경변수에 아래 값을 설정하며, 비밀번호 원문 대신 파일 경로만 전달한다. 작업 디렉터리는 저장소 루트다.

| 환경변수 | 로컬 실행에서 지정할 값 |
|---|---|
| `ES_ENABLED` | `true` |
| `ES_URL` | `https://localhost:9200` |
| `ES_USERNAME` | `elastic` |
| `ES_PASSWORD_FILE` | `.local/elasticsearch/elastic-password.txt` |
| `ES_CA_CERT` | `.local/elasticsearch/certs/ca.crt` |
| `ES_CONNECT_TIMEOUT` | 기본 `5s`, 연결·풀 대기·TLS handshake 제한 |
| `ES_REQUEST_TIMEOUT` | 기본 `40s`, 응답·socket 제한 |

제한 시간은 각각 1ms 이상 5분 이하다. 연결 주소에는 HTTPS origin만 허용한다. 사용자 정보·query·fragment·하위 경로를 URL에 넣지 않는다. CA 신뢰와 호스트명 검증은 유지한다.

`.env.example`은 필요한 변수 이름을 보여 주는 템플릿이다. Spring Boot와 Gradle이 루트 `.env`를 자동으로 읽는다고 가정하지 않는다. IntelliJ 실행 구성이나 해당 터미널 프로세스의 환경변수로 전달한다. 개인용 실행 구성·실제 환경 파일은 Git에서 제외한다.

새 PC에서는 로컬 엔진 초기화 스크립트로 인증서와 비밀번호를 새로 만든다. 앱에는 공개 CA와 비밀번호 파일만 필요하고 노드 개인키나 CA 개인키는 제공하지 않는다. 공유 설정에 비밀번호를 저장하거나 실행 인자로 넣지 않는다.

## 검증 순서

먼저 로컬 엔진을 시작하고 `scripts/Test-Elasticsearch.ps1` 검증을 통과시킨다. 그다음 별도 터미널 프로세스에 위 환경변수를 지정하고 실행한다.

```powershell
./gradlew.bat check
./gradlew.bat integrationTest
```

IntelliJ에서도 같은 Gradle 태스크를 실행할 수 있다. 터미널에서는 해당 프로세스가 JDK 21을 찾을 수 있어야 하며 시스템 `JAVA_HOME` 변경은 필요하지 않다. 새 의존성 다운로드가 끝나기 전에는 `--offline`을 사용하지 않는다.

`integrationTest`는 `elasticsearch` 태그 테스트만 실행하며 이전 실행 결과를 캐시로 재사용하지 않는다. 엔진이 없거나 필수 설정이 빠지면 실패한다. 테스트 0개 또는 전부 건너뛴 실행은 검증 성공이 아니다. 결과는 `build/reports/tests/integrationTest/`, XML은 `build/test-results/integrationTest/`에서 확인한다.

실제 호출은 제품 설정이 만든 클라이언트의 엔진 정보 조회다. 통합 테스트에서는 클러스터명·버전, 잘못된 비밀번호·CA 거절, CRLF 비밀번호 파일을 확인한다. 자원 종료와 mapper의 JSON 쓰기/읽기는 `check`의 엔진 없는 테스트로 확인한다. 이 단계에서 인덱스를 생성하거나 상품을 적재하지 않는다.

## 구성과 오류

- ES 연결은 `search.elasticsearch.enabled=true`일 때 활성화한다. 클라이언트 생성 자체가 엔진 정보 조회를 자동 실행하지는 않으며, 실제 연결 검증은 별도 통합 테스트로 수행한다.
- JSON은 `Jackson3JsonpMapper`와 Jackson 3 `JsonMapper`를 명시적으로 사용한다. 실제 의존성 선택과 직렬화·전송 실행을 함께 확인한다.
- 연결 자원은 Spring이 소유한 Rest5Client가 닫는다. 상위 transport/client는 같은 자원을 중복 종료하지 않는다.
- 설정·파일 오류는 값이나 파일 내용을 출력하지 않는 메시지로 거절한다. 인증 실패·TLS 실패·연결 실패는 각각 실패로 관찰하며 성공 응답으로 바꾸지 않는다.
- 원문 HTTP 통신 로그나 인증 헤더를 활성화해 공유하지 않는다. 검증 보고서와 Git에 남길 내용도 민감정보를 검토한다.

구현 API는 [Rest5 9.4.5 소스](https://repo.maven.apache.org/maven2/co/elastic/clients/elasticsearch-rest5-client/9.4.5/elasticsearch-rest5-client-9.4.5-sources.jar), [Java Client 9.4.5 소스](https://repo.maven.apache.org/maven2/co/elastic/clients/elasticsearch-java/9.4.5/elasticsearch-java-9.4.5-sources.jar)를 기준으로 확인한다. [TLS 구성](https://www.elastic.co/docs/reference/elasticsearch/clients/java/transport/rest5-client/config/encrypted_communication), [제한 시간](https://www.elastic.co/docs/reference/elasticsearch/clients/java/transport/rest5-client/config/timeouts) 안내도 참고한다.
