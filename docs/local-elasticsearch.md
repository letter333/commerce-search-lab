# 로컬 Elasticsearch 실행

M1-02는 [실행 계약](search-engine-setup.md)의 Elasticsearch·Nori 9.4.7을 실행한다. Java Client 연결은 M1-03이다. 아래 명령은 Windows PowerShell에서 저장소 루트를 기준으로 실행한다. IntelliJ의 프로젝트·Gradle JDK는 기존 Java 21을 사용한다.

## 1. 호스트 확인

Docker Desktop의 Linux 엔진을 시작하고 다음을 확인한다.

```powershell
docker version
docker compose version
docker info --format 'os={{.OSType}} arch={{.Architecture}} memory_bytes={{.MemTotal}}'
Get-NetTCPConnection -State Listen -LocalPort 9200,9300 -ErrorAction SilentlyContinue
wsl -d docker-desktop -u root -- sysctl vm.max_map_count
```

다른 프로세스가 9200을 사용하면 소유 작업을 확인한다. 공개 주소는 `127.0.0.1:9200`이며 9300은 호스트에 공개하지 않는다. 컨테이너 메모리는 2GiB이고 힙은 Elasticsearch가 선택한다.

프로젝트의 `vm.max_map_count` 권장 기준은 1048576 이상이다. **Docker Desktop을 시작한 뒤, Elasticsearch를 기동하기 전에** 현재 값을 확인하고 필요한 경우 다음 명령으로 적용한다. Docker/WSL을 다시 시작하면 이 단계를 반복한다.

```powershell
wsl -d docker-desktop -u root -- sysctl -w vm.max_map_count=1048576
```

이번 Windows 11·WSL 2.4.13·Docker Desktop 4.39 환경에서는 `/etc/sysctl.conf` 항목이 남아 있어도 재시작 후 실제 값이 262144였다. `.wslconfig` 부팅 인자도 커널 명령줄에 적용됐지만 Docker 시작 후 값은 같았다. 두 영구 설정 시도는 이번에 추가한 내용만 되돌렸으며, Docker 시작 후 명시적으로 적용하는 위 절차를 사용한다. 262144를 이유로 실제 ES 기동 실패가 관찰됐다고 주장하지 않는다.

WSL 커널 설정은 같은 커널을 사용하는 다른 작업에도 영향을 주므로 Desktop 재시작 전 실행 중인 컨테이너를 확인한다. 다른 환경의 지속 설정 방법과 실제 적용 결과는 구분한다. [Elastic의 WSL별 안내](https://www.elastic.co/docs/deploy-manage/deploy/self-managed/install-elasticsearch-docker-prod), [WSL 설정 범위](https://learn.microsoft.com/en-us/windows/wsl/wsl-config).

## 2. 이미지 빌드와 최초 준비

```powershell
docker compose -p commerce-search-lab -f compose.yaml config --quiet
docker compose -p commerce-search-lab -f compose.yaml build elasticsearch
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File scripts/Initialize-Elasticsearch.ps1
```

명령의 실행 정책은 해당 PowerShell 프로세스에만 적용된다. 전역 정책이나 시스템 `JAVA_HOME`을 바꾸지 않는다. 구성 검사는 구문 검사이며 인증서·비밀번호 준비나 엔진 기동 성공을 의미하지 않는다.

[초기화 스크립트](../scripts/Initialize-Elasticsearch.ps1)는 고정 이미지의 `elasticsearch-certutil`을 네트워크 없는 일회성 컨테이너에서 실행한다. [인증서 입력](../infra/elasticsearch/instances.yml)의 DNS 이름은 `elasticsearch`, `es01`, `localhost`, IP는 `127.0.0.1`이다.

| 로컬 경로 | 역할 |
|---|---|
| `.local/elasticsearch/certs/ca.crt` | 접속 시 신뢰할 공개 CA |
| `.local/elasticsearch/certs/es01.crt`, `es01.key` | 노드 인증서와 개인키 |
| `.local/elasticsearch/ca-private/` | CA 개인키·생성 ZIP 보관, 실행 서비스에 마운트하지 않음 |
| `.local/elasticsearch/elastic-password.txt` | 로컬에서 무작위 생성한 초기 비밀번호 |

실행 서비스에는 CA·노드 인증서·노드 키 세 파일만 읽기 전용으로 마운트한다. 비밀번호 원본도 Compose secret으로 읽기 전용 마운트한다. Windows에서는 이 파일의 모드가 777로 보여 ES 9.4.7의 시작 검사가 실패했다. [로컬 시작 스크립트](../infra/elasticsearch/local-entrypoint.sh)는 원본을 `uid=1000`, 모드 0700인 `/run/local-secrets` tmpfs에 복사하고 파일 모드를 400으로 설정한다. 원래 Elasticsearch entrypoint에는 이 복사본의 경로만 `ELASTIC_PASSWORD_FILE`로 전달한다. Compose와 로컬 wrapper는 비밀번호 원문을 환경변수 값이나 컨테이너 writable layer에 기록하지 않는다.

빌드 컨텍스트에는 Dockerfile과 로컬 시작 스크립트만 포함한다. 루트 `.env.example`은 안내용이며 기본 Compose 실행에 실제 `.env`가 필요하지 않다.

초기화 스크립트는 Git 제외·미추적 상태를 확인하며 기존 파일을 덮어쓰지 않는다. 파일이 전부 있으면 그대로 보존하고, 일부만 있으면 실패해 진단을 요구한다. 실패한 초기화를 복구할 때도 기존 CA·키·비밀번호를 먼저 보존한다.

기존 인증서를 재사용할 때는 유효기간과 SAN 전체를 별도로 검사한다. 다음 명령은 컨테이너에 포함된 JDK로 공개 인증서 정보만 읽는다. DNS `elasticsearch`, `es01`, `localhost`와 IP `127.0.0.1`이 모두 있어야 한다. 노드 키 대응은 엔진 기동, CA 신뢰와 `localhost` 일치는 아래 HTTPS 접속 검사로 확인한다.

```powershell
$certificatePath = Join-Path (Get-Location) '.local/elasticsearch/certs/es01.crt'
docker run --rm --network none --mount "type=bind,source=$certificatePath,target=/node.crt,readonly" commerce-search-lab/elasticsearch-nori:9.4.7 jdk/bin/keytool -printcert -file /node.crt
```

## 3. 기동과 검증

```powershell
docker compose -p commerce-search-lab -f compose.yaml up -d elasticsearch
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File scripts/Test-Elasticsearch.ps1 -AsJson
docker compose -p commerce-search-lab -f compose.yaml ps
```

[검증 스크립트](../scripts/Test-Elasticsearch.ps1)는 CA·hostname 검증을 유지하고 비밀번호를 curl의 표준 입력으로만 전달한다. 개인 curl 설정과 프록시를 읽어 TLS나 접속 경로가 바뀌지 않도록 한다. 요청 최대 40초·연결 최대 5초, 검증 전체 대기 상한은 180초다.

Windows Schannel은 개발 CA에 폐기 조회 정보가 없어 `revocation status is unknown`으로 실패했다. 스크립트는 Schannel을 감지한 경우에만 `ssl-revoke-best-effort`를 사용한다. 조회 정보 부재를 처리하되 CA 체인·호스트명 검증은 유지한다. 실제 잘못된 CA와 SAN에 없는 호스트가 curl 코드 60으로 거부됨을 확인했다. 기동 초반 연결·TLS handshake 미준비는 180초 안에서 재시도하며 인증서 검증 실패·인증 실패는 통과시키지 않는다. [curl 옵션 설명](https://curl.se/docs/manpage.html#--ssl-revoke-best-effort).

검증 순서는 무인증 HTTPS의 정확한 401 → 인증된 클러스터·버전 → Nori → cluster health다. 버전은 ES/Nori 모두 9.4.7, 클러스터명은 `commerce-search-lab`, 정상 노드는 1개·실패 노드는 0개여야 한다. 결과에는 cluster UUID·버전·Nori 버전·CA 파일 SHA-256·health만 출력한다.

컨테이너 healthcheck의 `healthy`는 TLS와 API 도달성이다. 인증과 shard 준비를 포함한 검증 스크립트 성공 여부를 별도로 확인한다. 스크립트는 `timed_out=false`인 green만 자동 통과한다. yellow가 계속되면 replica 미할당만인지 별도 진단하고 기록한다. 원인을 확인하지 않고 replica 수나 필터를 바꿔 통과시키지 않는다.

필요한 경우 아래처럼 비밀번호를 대화형으로 입력해 진단한다. 비밀번호를 명령 인자로 붙이거나 CA 검증을 해제하지 않는다. 원문 진단 출력은 로컬에서 검토하고 비밀값이 없는 요약만 작업 기록에 남긴다.

```powershell
curl.exe -q --noproxy '*' --ssl-revoke-best-effort --cacert .local/elasticsearch/certs/ca.crt --user elastic --fail --silent --show-error --connect-timeout 5 --max-time 40 'https://localhost:9200/_cat/shards?format=json'
curl.exe -q --noproxy '*' --ssl-revoke-best-effort --cacert .local/elasticsearch/certs/ca.crt --user elastic --fail --silent --show-error --connect-timeout 5 --max-time 40 'https://localhost:9200/_cluster/allocation/explain'
```

| 실패 | 확인할 대상 |
|---|---|
| Docker 연결 실패 | Desktop Linux 엔진·WSL 상태. 검색 로직의 Red 증거가 아님 |
| 구성 또는 파일 누락 | 경로·초기화 결과·컨테이너 UID/GID `1000:0`의 읽기 권한 |
| TLS 오류 | CA·유효기간·SAN·노드 키 대응 |
| 인증 401/403 | 기존 데이터 볼륨과 비밀번호 파일의 대응 |
| 버전·Nori 불일치 | 실제 이미지와 노드 플러그인 목록 |
| red·시간 초과·설명되지 않은 yellow | 자원·shard 할당·기동 로그를 로컬에서 진단 |

비밀번호 파일은 최초 bootstrap 입력이다. 기존 데이터 볼륨을 둔 채 파일만 바꿔 비밀번호가 변경됐다고 가정하지 않는다.

## 4. 재기동과 종료

최초 검증 결과를 로컬 메모리에 보관한 뒤 컨테이너를 다시 만들고 비교한다.

```powershell
$beforeJson = powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File scripts/Test-Elasticsearch.ps1 -AsJson
if ($LASTEXITCODE -ne 0) { throw 'Initial verification failed.' }
$before = $beforeJson | ConvertFrom-Json
docker compose -p commerce-search-lab -f compose.yaml down
if ($LASTEXITCODE -ne 0) { throw 'Stop failed.' }
docker compose -p commerce-search-lab -f compose.yaml up -d elasticsearch
if ($LASTEXITCODE -ne 0) { throw 'Startup failed.' }
$afterJson = powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File scripts/Test-Elasticsearch.ps1 -AsJson
if ($LASTEXITCODE -ne 0) { throw 'Restart verification failed.' }
$after = $afterJson | ConvertFrom-Json
foreach ($field in @('clusterUuid', 'version', 'nori', 'caSha256')) {
    if ($before.$field -cne $after.$field) { throw "Restart changed $field" }
}
```

일반 중지는 `docker compose -p commerce-search-lab -f compose.yaml down`이다. `commerce-search-lab-es-data` 볼륨과 로컬 인증 파일은 유지한다. `down -v`나 인증 파일 삭제는 일반 종료에 포함하지 않는다.

실제 검증 결과·제약·다음 작업은 [작업 기록](worklog.md)에 남긴다. 이 단계의 성공은 Java Client 연결, 상품 색인, Nori 토큰 품질이나 검색 성능 검증을 뜻하지 않는다.

## 이번 호스트의 복구 이력과 제약

- Docker Desktop 4.39가 `%LOCALAPPDATA%/Docker/run/userAnalyticsOtlpHttp.sock`의 stale socket을 정리하지 못해 종료됐다. 정상 종료·재시작에서도 재현됐다. Docker가 멈춘 상태와 `run` 폴더에 이 소켓 하나만 있음을 확인하고 폴더를 `run.stale-m1-02`, 재현 후에는 `run.stale-m1-02-restart`로 보존해 복구했다. 기존 이미지·볼륨은 유지했다. 영구 해결을 확인한 것은 아니며 자동 삭제·일괄 초기화 절차로 사용하지 않는다. 유사한 Windows build 26200 사례는 [Docker 저장소의 사용자 보고](https://github.com/docker/desktop-feedback/issues/527)에 있으나 공식 확정 원인과 구분한다.
- Docker 내부에서 공식 이미지 CDN 전송이 시간 초과됐지만 호스트 curl은 같은 공개 파일을 정상 수신했다. 공식 linux/amd64 manifest `sha256:e083ef4f6b3d5d49115f2893e384d07b94c02c17093f6b7f05e9b2d2821a079c`와 config, 압축 layer 10개의 SHA-256·크기 및 압축해제 `diff_ids`를 검증한 archive를 `docker load`로 불러온 뒤 Nori 이미지를 정상 빌드했다. 다른 이미지로 교체하거나 Docker 인증·프록시 설정을 바꾸지 않았다.
- 위 다운로드 archive와 검증 증거는 `build/m1-02/`의 임시 산출물이다. 일반 절차는 앞의 공식 이미지 빌드이며, 캐시가 없는 환경에서 동일 CDN 제약이 생기면 전송 경로를 진단하고 원본 digest 확인 없이 대체 archive를 사용하지 않는다.
