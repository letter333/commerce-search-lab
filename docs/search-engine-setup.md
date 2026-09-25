# 검색 엔진 버전과 로컬 실행 계약

결정일: 2026-09-25. 작업: [M1-01 / 이슈 #3](https://github.com/letter333/commerce-search-lab/issues/3). 기준선: `main`의 `5c328794b2a9b835f101dee11dbb2d0c3202dff0`.

**M1-01에서 버전·실행 계약을 정했고 M1-02에서 이미지 빌드와 실제 ES/Nori 기동을 검증했다. Client 연결은 아직 미검증이다.** 실행 방법과 Windows 환경 보완은 [로컬 실행 안내](local-elasticsearch.md), 실제 결과는 [작업 기록](worklog.md)을 따른다. [세부계획](m1-01-plan.md), [전체 작업 순서](implementation-plan.md)도 함께 확인한다.

## 1. 선정 버전

| 항목 | 고정값 | 적용 위치·근거 |
|---|---|---|
| 앱 JDK | Java 21 | 기존 IntelliJ 프로젝트·Gradle 실행 JDK 유지 |
| Spring Boot | 4.1.1 | 기존 `build.gradle.kts` 유지 |
| Gradle Wrapper | 9.7.1 | 기존 Wrapper 유지 |
| Elasticsearch | **9.4.7** | `docker.elastic.co/elasticsearch/elasticsearch:9.4.7`, 로컬 플랫폼 `linux/amd64` |
| Nori | **9.4.7** | 위 이미지 안에서 `bin/elasticsearch-plugin install --batch analysis-nori` |
| 공식 Java Client | **9.4.5** | `co.elastic.clients:elasticsearch-java:9.4.5`, Boot 4.1.1 관리 버전 |
| HTTP 전송 | **Rest5Client 9.4.5** | `co.elastic.clients:elasticsearch-rest5-client:9.4.5`, Client의 전이 의존성. legacy RestClient는 추가하지 않음 |
| JSON mapper | `Jackson3JsonpMapper` | `tools.jackson.databind.json.JsonMapper`를 명시적으로 연결 |

서버는 같은 9.4 계열의 후속 patch를 채택하고 앱은 Boot가 관리하는 Client 9.4.5를 유지한다. 공식 호환 정책의 같은 minor 내 클라이언트보다 같거나 높은 서버 patch 방향을 따른다. 이것은 서버의 새 API를 클라이언트가 자동 지원하거나 실제 연결 검증까지 끝났다는 뜻은 아니다. [Java Client 호환 정책](https://www.elastic.co/docs/reference/elasticsearch/clients/java)

공식 9.4.7 이미지의 amd64/arm64 배포와 Nori 9.4.7 ZIP의 존재를 확인했다. M1-02에서 실제로 가져온 이미지의 digest·플랫폼·엔진 버전을 기록한다. 앱의 JDK를 ES 컨테이너에 주입하지 않고 이미지에 포함된 JDK를 사용한다. [공식 이미지](https://www.docker.elastic.co/r/elasticsearch/elasticsearch:9.4.7), [서버 배포](https://www.elastic.co/downloads/past-releases/elasticsearch-9-4-7), [Nori 배포](https://artifacts.elastic.co/downloads/elasticsearch-plugins/analysis-nori/analysis-nori-9.4.7.zip)

비교한 대안은 서버·Nori까지 9.4.5로 맞추는 조합이다. 서버 9.4.7의 분석 필터 입력·메모리 제한과 조회 오류 수정 등을 반영하기 위해 이를 채택하지 않았다. Client 9.4.5는 알려진 Rest5 네트워크 지연 회귀의 수정 버전이기도 하다. [서버 릴리스 기록](https://www.elastic.co/docs/release-notes/elasticsearch), [Client 알려진 문제](https://www.elastic.co/docs/release-notes/elasticsearch/clients/java/known-issues)

## 2. 의존성 확인 결과와 M1-03의 검증 조건

현재 프로젝트에서 기존 캐시로 아래 명령을 실행했다. 세 보고서는 모두 성공했고 unresolved dependency의 `FAILED` 표시는 없었다. runtime과 testRuntime 모두 `tools.jackson.core:jackson-databind/core:3.1.5`를 사용하며, ES Client와 Rest5는 아직 없다.

```powershell
./gradlew.bat --offline --no-daemon dependencies --configuration runtimeClasspath
./gradlew.bat --offline --no-daemon dependencies --configuration testRuntimeClasspath
./gradlew.bat --offline --no-daemon dependencyInsight --dependency jackson-databind --configuration testRuntimeClasspath
```

IntelliJ 사용자는 같은 Gradle 태스크를 IDE에서 실행할 수 있다. 에이전트의 터미널 조사에는 기존 JDK 21과 Gradle 캐시를 해당 프로세스에만 지정했다. 시스템 `JAVA_HOME` 변경은 개발 선행 조건이 아니다. 임시 보고서는 `build/m1-01/`에 있으며 Git에서 제외된다.

아래는 배포 POM과 Boot 4.1.1 BOM을 비교한 결과다. **오른쪽은 Client를 추가한 뒤 실제 resolve한 결과가 아니라 관리 규칙에 따른 예상**이다.

| 의존성 | Client/Rest5 배포 메타데이터 | Boot 관리값·예상 | M1-03에서 확인할 점 |
|---|---|---|---|
| Client / Rest5 | 9.4.5 / 9.4.5 | 9.4.5 / 9.4.5 | 실제 선택 버전 |
| HttpClient5 | 5.6.3 | 5.6.4 | 연결·TLS 경로의 API 호환 |
| HttpCore5 / httpcore5-h2 | 5.4.3 | 5.4.3 | 단일 버전으로 선택되는지 |
| Jackson 3 core/databind | 3.1.0 | 3.1.5 | 현재 관찰값과 추가 후 결과 비교 |
| Jackson 2 core/databind | 2.22.0 | 2.21.5로 하향 예상 | 기본 Jackson 2 mapper를 잘못 사용하는지, linkage 오류가 있는지 |
| Jakarta JSON API | 2.1.3 | 2.1.3 | `jakarta.json` 사용과 공급자 연결 |
| Parsson | Client 1.1.9 / Rest5 1.1.7 | Boot 직접 관리 없음, 1.1.9 선택 예상 | 실제 충돌 해결 결과 |
| commons-logging | 1.3.5 | 1.3.6 | 실제 runtime 선택 결과 |
| OpenTelemetry API | 1.63.0 | 1.62.0으로 하향 예상 | 요청 실행 시 linkage 오류 여부 |
| OpenTelemetry semconv | 1.41.1 | 실제 선택 결과 확인 필요 | API와의 runtime 조합 |

근거: [Client POM](https://repo.maven.apache.org/maven2/co/elastic/clients/elasticsearch-java/9.4.5/elasticsearch-java-9.4.5.pom), [Client Gradle module](https://repo.maven.apache.org/maven2/co/elastic/clients/elasticsearch-java/9.4.5/elasticsearch-java-9.4.5.module), [Rest5 POM](https://repo.maven.apache.org/maven2/co/elastic/clients/elasticsearch-rest5-client/9.4.5/elasticsearch-rest5-client-9.4.5.pom), [HttpClient5 parent POM](https://repo.maven.apache.org/maven2/org/apache/httpcomponents/client5/httpclient5-parent/5.6.4/httpclient5-parent-5.6.4.pom), [Boot 관리표](https://docs.spring.io/spring-boot/appendix/dependency-versions/coordinates.html). Boot BOM은 로컬의 정확한 4.1.1 POM과 관리표를 함께 확인했다.

선정 버전의 `Jackson3JsonpMapper`는 `tools.jackson.databind.json.JsonMapper`를 받는다. 일반 `ObjectMapper` 인수라고 가정하지 않는다. `JacksonJsonpMapper`는 별도 Jackson 2 경로다. 현재 `com.fasterxml.jackson.annotation`이 존재하는 것만으로 Jackson 2 databind가 잘못 유입됐다고 판단하지 않는다. [9.4.5 mapper API](https://artifacts.elastic.co/javadoc/co/elastic/clients/elasticsearch-java/9.4.5/co/elastic/clients/json/jackson/Jackson3JsonpMapper.html), [전송·mapper 설명](https://www.elastic.co/docs/reference/elasticsearch/clients/java/transport)

M1-03에서는 Client 추가 후 compile/runtime/testRuntime과 관련 `dependencyInsight`를 다시 확인한다. 제품 코드가 `JsonMapper`를 직접 참조할 때 필요한 생산 코드 의존성도 확인하며 기존 `testImplementation`만으로 충분하다고 단정하지 않는다. JSON 직렬화·역직렬화 테스트와 실제 엔진 정보 응답 파싱을 수행한다. Jackson 2·OpenTelemetry 하향 예상은 조사할 위험이며 이미 발생한 오류가 아니다. 구체적인 재현 없이 전역 버전 override·exclude나 legacy HTTP 전송을 추가하지 않는다. 실패하면 원인을 해결하고 실제 연결 완료 전에는 이 조합의 실행 호환성을 통과 처리하지 않는다.

## 3. 로컬 실행 설정

다음 값은 **M1-02가 구현한 프로젝트 실행 계약**이다. M1-01의 문서 선정 이후 실제 기동에서 필요한 Windows 보완을 반영했다.

| 항목 | 결정값 |
|---|---|
| Compose | 루트 `compose.yaml`, project `commerce-search-lab`, 서비스 `elasticsearch` |
| Nori 이미지 | `infra/elasticsearch/Dockerfile`에서 고정 ES 9.4.7 이미지에 Nori 설치. 로컬 결과 태그 `commerce-search-lab/elasticsearch-nori:9.4.7` |
| 노드 | `cluster.name=commerce-search-lab`, `node.name=es01`, `discovery.type=single-node`. `cluster.initial_master_nodes` 미사용 |
| HTTP | 컨테이너 `http.host=0.0.0.0`, 호스트 공개는 `127.0.0.1:9200:9200`만 사용 |
| 앱 접속 | `https://localhost:9200`. 인증서 SAN에 `localhost`와 `127.0.0.1` 포함 |
| Transport | `transport.host=127.0.0.1`, TLS 사용, 호스트 9300 포트 미공개 |
| 보안 | `xpack.security.enabled=true`, `xpack.security.autoconfiguration.enabled=false`, HTTP·transport TLS를 명시적으로 구성 |
| HTTP TLS | `xpack.security.http.ssl.enabled=true`; `key`, `certificate`, `certificate_authorities`에 노드 key/crt와 CA crt 경로 지정 |
| Transport TLS | `xpack.security.transport.ssl.enabled=true`, `verification_mode=full`; 같은 노드 key/crt와 CA crt 사용 |
| 메모리 | 컨테이너 `mem_limit: 2g`, ES 자동 힙 선정. `ES_JAVA_OPTS`로 Xms/Xmx를 별도 지정하지 않음 |
| 데이터 | named volume `commerce-search-lab-es-data` → `/usr/share/elasticsearch/data` |
| 인증서 보관 | Git 제외 경로 `.local/elasticsearch/certs/`. CA 개인키는 인증서 생성 시에만 사용하고 실행 서비스에는 제공하지 않음 |
| 노드 인증서 경로 | 노드 `es01.crt`, `es01.key`, 공개 `ca.crt`만 `/usr/share/elasticsearch/config/certs/` 아래로 읽기 전용 마운트 |
| 비밀번호 입력 | `.local/elasticsearch/elastic-password.txt`를 읽기 전용 Compose secret `elastic_password`로 전달 |
| 비밀번호 권한 | Windows 마운트의 777 모드로 ES 시작 검사가 실패했다. 로컬 wrapper가 원본을 `/run/local-secrets` tmpfs(0700, uid 1000)에 모드 400으로 복사하고 `ELASTIC_PASSWORD_FILE=/run/local-secrets/elastic_password`로 원래 entrypoint에 전달 |
| 상태 시간 | 연결 timeout 5초, cluster health API timeout 30초, 클라이언트 최대 40초, 초기 준비 대기 상한 180초 |
| 도달성 healthcheck | 10초 간격, 각 5초 timeout, start period 30초, retries 12. CA 검증이 통과한 무인증 HTTPS의 정확한 401 응답 검사 |

2GiB는 작은 개발 데이터용 프로젝트 시작값이며 성능 보장이 아니다. 공식 개발 예제의 메모리 제한과 자동 힙 설정 원칙을 참고했다. 실제 기동 실패/OOM이면 관찰 결과에 근거해 계약을 갱신하며 조용히 제한을 바꾸지 않는다. [Docker 단일 노드](https://www.elastic.co/docs/deploy-manage/deploy/self-managed/install-elasticsearch-docker-basic), [JVM 메모리 설정](https://www.elastic.co/docs/reference/elasticsearch/jvm-settings)

단일 노드 발견과 네트워크 설정은 다른 ES 클러스터에 연결하지 않는 이 실험의 범위다. 포트가 사용 중이면 해당 프로세스를 임의 종료하지 않고 호스트 포트 변경과 문서·앱 URL을 함께 검토한다. [discovery 설정](https://www.elastic.co/docs/reference/elasticsearch/configuration-reference/discovery-cluster-formation-settings), [네트워크와 bootstrap 검사](https://www.elastic.co/docs/deploy-manage/deploy/self-managed/bootstrap-checks)

## 4. 인증서·비밀값 준비 계약

M1-02에서 고정 ES 이미지의 `elasticsearch-certutil ca --silent --pem`과 `cert --silent --pem --in ... --ca-cert ... --ca-key ...`를 사용해 개발 전용 CA·노드 인증서를 만든다. 입력 SAN은 DNS `elasticsearch`, `es01`, `localhost`, IP `127.0.0.1`로 정한다. CA 개인키와 노드 개인키를 저장소에 추가하지 않는다. 기존 인증서가 있으면 유효성·SAN·CA 대응을 확인해 재사용하고 무조건 덮어쓰지 않는다. [certutil](https://www.elastic.co/docs/reference/elasticsearch/command-line-tools/certutil), [TLS 설정](https://www.elastic.co/docs/reference/elasticsearch/configuration-reference/security-settings)

CA/노드 파일 생성은 일회성 준비 단계로 분리한다. `--pem` 출력 ZIP을 압축 해제하면 `ca/ca.crt`, `ca/ca.key`, `es01/es01.crt`, `es01/es01.key`가 생긴다. 공개 CA·노드 인증서·노드 키는 각각 `.local/elasticsearch/certs/ca.crt`, `es01.crt`, `es01.key`로 배치한다. CA 개인키와 생성 ZIP은 별도 `.local/elasticsearch/ca-private/`에 보존하고 런타임에 마운트하지 않는다. 런타임 마운트는 위 세 파일만 허용하며, UID/GID `1000:0`이 필요한 파일을 읽을 수 있는지 확인한다. 앱은 공개 CA만 신뢰하고 `-k`, trust-all 또는 hostname 검증 해제를 사용하지 않는다. 인증서 파일의 지문은 민감한 원문 없이 재기동 전후 동일성을 확인하는 증거로 사용할 수 있다.

비밀번호는 로컬에서 생성해 Git 제외 파일에 저장하고 Compose에는 파일 경로만 넣는다. M1-02에서 Windows file secret이 777로 보여 거부되는 오류를 재현했고, 읽기 전용 원본과 별도로 tmpfs에 만든 모드 400 복사본을 ES에 제공했다. `ELASTIC_PASSWORD_FILE`은 최초 bootstrap 입력이므로 기존 data volume의 비밀번호가 파일 편집만으로 바뀐다고 가정하지 않는다. 환경 예시에는 빈 값/placeholder 또는 안내만 둔다. 파일 내용이나 펼쳐진 전체 Compose 설정을 로그·문서·이슈에 복사하지 않는다. [Docker 비밀값 파일 설정](https://www.elastic.co/docs/deploy-manage/deploy/self-managed/install-elasticsearch-docker-configure), [내장 사용자와 bootstrap password](https://www.elastic.co/docs/deploy-manage/users-roles/cluster-or-deployment-auth/built-in-users)

`elastic` 계정은 격리된 로컬 개발 환경의 초기 검증에 한정한다. 공유/배포 환경에서 사용할 권한 설계나 외부 접속 허용은 이 계약에 포함하지 않는다.

## 5. 호스트 관찰값과 기동 전 조치

2026-09-25 읽기 전용 확인 결과:

| 항목 | 관찰값 | 처리 |
|---|---|---|
| Docker Client/Engine | 28.0.1 / 28.0.1 | Linux 엔진 연결 성공 |
| Compose | v2.33.1-desktop.1 | 기존 설치 사용 |
| 플랫폼·자원 | linux / x86_64, CPU 20개, 메모리 12,423,901,184 bytes(약 11.6GiB) | 컨테이너 시작 제한 2GiB 결정의 환경 근거 |
| 9200·9300 호스트 포트 | 조회 시 LISTEN 없음 | 기동 직전 다시 확인 |
| `vm.max_map_count` | **262144** | M1-02에서 프로젝트 권장 기준 **1048576 이상** 적용 여부를 확인·조정 |

위 표는 M1-01 관찰이다. 권장값 1048576과 bootstrap 최소값 262144를 구분하며, 현재 값만으로 반드시 ES 기동이 실패한다고 단정하지 않는다. M1-02에서 `/etc/sysctl.conf`와 WSL 부팅 인자를 각각 확인했지만 Docker 시작 후 실제 값이 262144로 돌아왔다. 이번에 추가한 영구 설정만 되돌리고 **Docker 시작 후 1048576을 적용하는 절차**로 확정했다. 실제 기동 시 1048576을 확인했으며 Docker/WSL 재시작 후에는 다시 적용한다. `node.store.allow_mmap=false`로 우회하지 않는다. [실제 환경 기록](local-elasticsearch.md), [Docker 시스템 설정](https://www.elastic.co/docs/deploy-manage/deploy/self-managed/install-elasticsearch-docker-prod), [가상 메모리](https://www.elastic.co/docs/deploy-manage/deploy/self-managed/vm-max-map-count)

```powershell
# 읽기 전용 점검 명령
docker version
docker compose version
docker info --format 'os={{.OSType}} arch={{.Architecture}} cpus={{.NCPU}} memory_bytes={{.MemTotal}}'
wsl -d docker-desktop -u root -- sysctl vm.max_map_count
```

## 6. 실행 명령과 판정

아래 명령은 **[최초 준비 절차](local-elasticsearch.md)를 마친 뒤 실행**한다. Windows에서는 `curl` 별칭 대신 `curl.exe`를 사용한다. Schannel의 개발 CA 폐기 정보 부재는 `--ssl-revoke-best-effort`로 처리하며 CA·hostname 검증은 유지한다.

```powershell
# 구성을 검증하되 비밀값이 펼쳐진 전체 설정은 출력하지 않는다.
docker compose -p commerce-search-lab -f compose.yaml config --quiet
docker compose -p commerce-search-lab -f compose.yaml build elasticsearch
docker compose -p commerce-search-lab -f compose.yaml up -d elasticsearch
docker compose -p commerce-search-lab -f compose.yaml ps

# --user elastic은 비밀번호를 인자에 넣지 않고 대화형으로 입력받는다.
curl.exe -q --noproxy '*' --ssl-revoke-best-effort --cacert .local/elasticsearch/certs/ca.crt --user elastic --fail --silent --show-error --connect-timeout 5 --max-time 40 'https://localhost:9200/?filter_path=cluster_name,cluster_uuid,version.number'
curl.exe -q --noproxy '*' --ssl-revoke-best-effort --cacert .local/elasticsearch/certs/ca.crt --user elastic --fail --silent --show-error --connect-timeout 5 --max-time 40 'https://localhost:9200/_nodes/plugins?filter_path=_nodes.total,_nodes.successful,_nodes.failed,nodes.*.version,nodes.*.plugins.name,nodes.*.plugins.version'
curl.exe -q --noproxy '*' --ssl-revoke-best-effort --cacert .local/elasticsearch/certs/ca.crt --user elastic --fail --silent --show-error --connect-timeout 5 --max-time 40 'https://localhost:9200/_cluster/health?wait_for_status=yellow&timeout=30s&filter_path=cluster_name,status,timed_out,number_of_nodes'

# 일반 중지는 개발 데이터를 보존한다.
docker compose -p commerce-search-lab -f compose.yaml down
```

자동 검증은 [Test-Elasticsearch.ps1](../scripts/Test-Elasticsearch.ps1)을 사용한다. 비밀번호를 프로세스 인자로 확장하지 않고 curl의 stdin으로 전달한다. raw Authorization 헤더·비밀번호·원본 디버그 로그는 증거에서 제외한다. 기동 초반 TLS handshake와 보안 인덱스 복구 중 401은 전체 180초 안에서 대기하며, 인증된 응답·버전·노드·green 확인 전에는 통과하지 않는다.

| 검사 | 성공 조건 | 실패 시 처리 |
|---|---|---|
| 구성 | `config --quiet` 종료 코드 0, 필요한 파일·값 존재 | 구문·경로·누락 설정 확인. 실제 비밀값 출력 금지 |
| TLS/API 도달성 | 신뢰한 CA·hostname 검증 통과, 무인증 요청에 정확히 401 | 연결/TLS/HTTP 문제를 구분. 401만으로 readiness 완료 처리 금지 |
| 인증된 엔진 정보 | 정상 JSON, `cluster_name=commerce-search-lab`, `version.number=9.4.7` | 인증 실패·버전/클러스터 불일치 실패 |
| Nori | `_nodes.total=1`, successful=1, failed=0; 해당 노드에 `analysis-nori` 9.4.7 존재 | 노드 실패·플러그인 누락·버전 불일치 실패 |
| 준비 상태 | `timed_out=false`, 노드 1개, primary 정상, `green` 또는 원인이 확인된 `yellow` | red·timeout 실패. yellow면 shard/할당 진단으로 replica 미할당만인지 확인 |
| 재기동 | down → up 후 버전·Nori·cluster UUID·CA 지문·기존 인증이 유지됨 | 데이터/인증서 마운트와 bootstrap 동작 조사 |

컨테이너 `running`이나 HTTP 200은 준비 상태의 충분조건이 아니다. 새 클러스터에서 설명되지 않는 yellow를 그대로 승인하지 않으며, 필요하면 인증된 `_cat/shards?format=json`과 `_cluster/allocation/explain`으로 근거를 확인한다. 초기 대기 180초를 넘으면 종료하지 않는 무한 재시도 대신 실패와 원인을 기록한다. Nori의 실제 토큰 품질 검증은 M1-09다. [Cluster health API](https://www.elastic.co/docs/api/doc/elasticsearch/operation/operation-cluster-health), [Nodes Info API](https://www.elastic.co/docs/api/doc/elasticsearch/operation/operation-nodes-info), [플러그인 관리](https://www.elastic.co/docs/reference/elasticsearch/plugins), [단일 노드의 red/yellow](https://www.elastic.co/docs/troubleshoot/elasticsearch/red-yellow-cluster-status)

`docker compose down -v`와 인증서·비밀번호 파일 삭제는 일반 중지/재기동에 포함하지 않는다. 데이터 초기화가 필요하면 실제 대상과 필요한 권한을 별도로 확인한다. [Compose 실행·데이터 보존](https://www.elastic.co/docs/deploy-manage/deploy/self-managed/install-elasticsearch-docker-compose)

## 7. 완료와 후속 검증

| 작업 | 현재/후속 결과 |
|---|---|
| M1-01 | 버전·공식 근거·기존 의존성 관찰·로컬 실행/검증 계약 확정 |
| M1-02 | Dockerfile·Compose·초기화/검증 스크립트 구현, ES/Nori 실제 기동 검증. 재기동 증거와 호스트 제약은 작업 기록 참고 |
| M1-03 | 아직 미실행: Client 의존성 추가 후 실제 resolved graph, JSON mapper·전송·인증/TLS 경로, 실제 연결과 종료 처리 |
| M1-04 | 아직 미실행: 테스트 인덱스의 생성·삭제 격리 |
| M1-09 | 아직 미실행: Nori 토큰·분석기 동작 확인 |

M1-01은 문서·환경 계약, M1-02는 설정 전 검사 준비와 실제 컨테이너 검증으로 진행했다. 제품 코드·시드·Gradle 의존성은 바꾸지 않았다. 기존 하네스 118개 통과와 실제 ES/Nori 기동 증거는 구분하며, Client 조합의 연결 검증은 M1-03에 남아 있다.
