# 작업 상태와 인계

갱신일: 2026-09-25

## 현재 상태

- 목표: 한국어 상품 검색 품질을 같은 데이터와 질의에서 비교하는 검색·백엔드 포트폴리오.
- 현재 단계: H0·M1-01~M1-03 구현·검증 완료. 다음은 M1-04 실제 엔진 테스트의 인덱스 격리다. 실제 검색 API·상품 색인은 아직 미구현.
- M0 데이터: 가상 상품 50개와 개발 시나리오 30개. 사람의 관련성 판정 재검토는 대기.
- 기존 스캐폴드: Java 21, Spring Boot 4.1.1, Gradle 9.7.1. 이 버전을 변경하지 않고 하네스를 추가한다.
- 검색 엔진·Nori·공식 Java Client 버전: ES·Nori 9.4.7, Java Client·Rest5 9.4.5의 실제 TLS·인증 연결 확인. [실행 계약](search-engine-setup.md), [로컬 실행 안내](local-elasticsearch.md), [Client 설정](java-client-setup.md)을 따른다.
- 개발 방식: 기능·버그·동작 변경은 테스트를 먼저 작성하는 TDD(Red → Green → Refactor)로 진행한다.
- 상세 구현 순서: [구현 실행 계획 v1](implementation-plan.md). M0 관련성 검토 후속과 M1~M4를 작은 단위로 구분했다. M1-02는 머지 완료, M1-03은 로컬 구현·검증 완료이며 커밋·푸시·PR 전달은 아직 수행하지 않았다. 제품 검색 기능 구현은 아직 시작하지 않았다.

## H0 · 하네스 완료

- 완료 기준: 작업 규칙·검증 명령·평가 계약을 문서화하고, 개발 시드와 평가 도구의 테스트를 통과한다.
- 변경: `AGENTS.md`, 하네스 안내, 평가 계약, 시드 계약 테스트, HTTP 평가 실행기와 테스트.
- 검증 환경: Windows 11, Microsoft OpenJDK 21.0.11, Gradle 9.7.1. 이 세션은 설치된 JDK와 Gradle 캐시를 지정하고 `--offline --no-daemon`으로 실행했다.
- 검증: `./gradlew.bat --offline --no-daemon check --warning-mode all` 성공. 시드 84개 + 평가 실행기 33개 + 앱 컨텍스트 1개 = **118개 통과**, 실패·오류·건너뜀 0개. 새 태스크의 Gradle 비권장 문법도 해소했다.
- 진입점 검증: `evaluateSearch --args="--help"` 실행 성공. 문서의 저장소 내부 링크와 `git diff --check` 확인 완료.
- 실패 경로 검증: `evaluateSearch --args="--base-url http://127.0.0.1:1 --warmup 0 --timeout-ms 100 --output build/evaluations/harness-unavailable-api-check"`는 의도대로 종료 코드 1. 요청 30개를 모두 실패로 기록하고 `valid: false`, 품질 지표 `null`, 입력 SHA-256 일치를 확인했다. 이 결과는 API 연결 실패 검사이며 r검색 품질 측정이 아니다.
- 한계: 하네스 테스트용 응답은 검색 엔진 실측값이 아니다. 실제 품질·p95 성능 수치는 아직 없다.
- 산출물: `build/reports/tests/test/index.html`, `build/reports/tests/verifySeeds/index.html`. 실패 경로 검증 결과는 `build/evaluations/harness-unavailable-api-check/`에 있으며 `clean`으로 삭제되는 임시 산출물이다.

## H0 후속 · 프로젝트 스킬

- 사용자 지시: 하네스에 필요한 스킬을 만들어 사용하도록 허용.
- 추가: `.agents/skills/commerce-search-evaluate/SKILL.md`, `.agents/skills/commerce-search-seeds/SKILL.md`. 평가 실행·해석과 시드 변경 절차를 프로젝트에 함께 보관한다.
- 연결: `AGENTS.md`에 상황별 사용 경로를 추가하고 `docs/harness.md`에 호출 예시를 기록했다.
- 검증: 공식 `skill-creator/scripts/quick_validate.py`로 두 설치본의 형식 검증 통과. 스킬·문서의 링크 존재와 `git diff --check` 확인 완료.
- 독립 사용 검증: 별도 에이전트가 두 스킬을 읽고 기존 결과와 시드를 감사했다. 연결 실패 보고서를 baseline 성적으로 채택하지 않았고, Q012의 적합 ID P041·P046과 Q029의 빈 기대 집합을 확인했다. Q016 단독 final 이동은 Q001·Q021과의 의도 그룹 격리를 깨뜨리며 현재 실행기 지원 밖임을 판정했다. 원본 데이터·코드·버전 변경은 없었다.
- 검증 산출물: `build/skill-validation/forward-check.md`는 스킬 사용 검증 기록이며 실제 검색 측정 결과가 아니다. 이번 변경은 지침·문서에 한정되어 앱 테스트를 재실행하지 않았다. 직전 하네스 코드 검증은 위의 118개 통과 기록을 따른다.
- 런타임: 스킬 자체는 Markdown 지침이며 기존 Gradle 명령을 사용한다. 형식 검사에는 제공된 Python 런타임을 사용하고, 필요한 PyYAML만 임시 `build/skill-validation/deps/`에 내려받았다. 앱이나 Gradle 의존성은 바꾸지 않았다.

## H0 후속 · 작업 단위와 Git 전달 스킬

- 사용자 요청: 한 번에 많은 작업을 하지 않는 스킬과 커밋·푸시 전 주의사항 스킬 작성.
- 추가 완료: `.agents/skills/commerce-search-small-steps/SKILL.md`, `.agents/skills/commerce-search-git-delivery/SKILL.md`. 작업 단위별 검증과 기존 승인 유지, staged 변경 보존, 작성자 정보, 비밀정보·산출물·실제 push 목적지·전송 이력 확인을 다룬다.
- 연결: `AGENTS.md`와 하네스 스킬 목록에 작업별 사용 조건과 호출 예시를 추가했다.
- 검증: 공식 `quick_validate.py` 형식 검사, 설치본과 검토 초안 해시 일치, 상대 링크 확인, `git diff --check` 통과. 작성 전후 staged diff 해시가 같아 기존 staged 변경 보존을 확인했다.
- 독립 사용 검증: 전체 M1 요청은 작은 단위로 끝까지 진행, 환경만 요청하면 해당 범위에서 완료, 커밋만 요청하면 기존 staged 변경을 보존하고 푸시 제외, 전송 이력의 비밀정보·서로 다른 fetch/push 대상을 구분하는 네 가지 상황을 확인했다.
- 검증 산출물: `build/skill-validation/workflow-skills-forward-check.md`. 관찰 당시 새 스킬은 초안 상태였으며 이후 검토본을 `.agents/skills/`에 설치했다. 이 보고서는 실제 개발·커밋·푸시 실행 결과가 아니다.
- 이번 변경은 스킬·문서에 한정되어 앱 테스트를 재실행하지 않았다. 실제 staging·커밋·푸시·원격 설정 변경은 수행하지 않았다.

## H0 후속 · TDD 개발 방식

- 사용자 요청: 개발을 TDD로 진행하고 테스트를 먼저 작성하도록 지침 수정.
- 변경: `AGENTS.md`, 작은 단위 개발 스킬, Git 전달 스킬, 하네스 작업 루프·작업 기록 양식에 테스트 선행과 Red·Green 실제 실행 기록을 반영했다.
- 적용: 기능·버그·동작 변경은 Red → Green → Refactor. 순수 리팩터링은 기존 동작 테스트를 먼저 확보하고, 문서·스킬·단순 데이터 편집은 필요한 검증만 수행한다.
- 검증: 공식 `quick_validate.py`로 수정 스킬 두 개의 형식 검사 통과, 설치본과 검토본의 해시 일치 확인. 문서 링크·TDD 절 앵커·`git diff --check` 및 독립 일관성 검토를 통과했다. 이번 요청은 지침 변경으로 앱 구현·테스트 코드는 수정하지 않았고 앱 테스트도 재실행하지 않았다.
- 기록의 한계: 기존 118개 통과는 당시 검증 결과다. Red 실행 기록이 없는 과거 작업을 TDD로 수행했다고 소급해 표시하지 않는다.

## 계획 수립 · 상세 구현 실행 계획

- 사용자 요청: 구현에 앞서 작업을 작게 나누고 가능한 자세한 계획 작성.
- 산출물: [구현 실행 계획 v1](implementation-plan.md). H0를 재사용하고 M1 기본 검색, M2 질의 개선·랭킹·화면, M3 데이터 확대·동결 평가, M4 복구·이벤트·재현 자료를 계획했다.
- 작업별 기록: 선행 조건, 관찰 가능한 결과, 먼저 검증할 사례, 변경 범위, 완료 증거. 예정 명령/파일과 현재 존재하는 하네스를 구분했다.
- 검토 반영: 실제 엔진 테스트 준비 순서, 최초 alias 연결 전 대표 검색, 필터/실행 경로의 세분화, snapshot과 후보·보호 어휘 연결, 이벤트 오류 정책, final 성적의 코드/설정 귀속.
- 검증: M0 후속 2개·M1 36개·M2 20개·M3 23개·M4 20개로 총 101개 작업 ID의 유일성, 수정 문서의 로컬 링크·공백·충돌 표시, `git diff --check` 확인. 선행 ID 존재·의존 순환 없음·각 단계 마지막 작업까지의 전체 작업 연결을 검사했다. 작성 전후 staged diff SHA-256이 같아 기존 staged 변경 보존을 확인했다. 독립 검토 2건의 지적을 반영했다.
- 이번 변경은 계획 문서와 탐색 링크에 한정된다. 앱·테스트·시드·의존성은 변경하지 않았고 구현·검색 평가·Gradle 테스트도 실행하지 않았다. 과거 118개 통과를 이번 검증으로 표시하지 않는다.

## Git 민감정보 제외 기준

- 사용자 요구: 환경변수 등 민감한 데이터가 있는 파일은 Git에 포함하지 않는다.
- 변경: `.gitignore`에 `.env` 계열, 자격증명·개인키·키 저장소, 로컬 Spring 설정과 백업, 개인용 Gradle 설정, JSONL·로그·runtime 경로를 추가했다. 루트 `.env.example`과 `docs/results/**/runs.jsonl`만 해당 범주의 공유 예외이며 실제 내용 검토가 필요하다.
- 지침/계획: `AGENTS.md`에 비밀값의 환경 주입, placeholder 예시, 강제 추가 금지, staged/전송 이력 점검, 추적·이력에 이미 남은 값의 처리 원칙을 기록했다. 구현 계획의 공통 기준·M1-02·M4-09·결과 보존 조건에도 반영했다.
- 점검: Git 대상 작업 트리 텍스트 27개와 index 파일 4개를 비밀값 출력 없이 확인했다. 개인키 헤더·일부 토큰 형식·URL 자격증명·민감 설정 대입 패턴에서 후보는 없었다. 이는 제한된 패턴 검사이며 과거 전체 이력이나 모든 비밀정보의 부재를 입증하지 않는다.
- 검증: `git check-ignore --no-index -- <경로들>`로 제외 42개/허용 11개 통과. 실제 비밀 파일이나 검증용 파일을 만들지 않았다. 최초 PowerShell 파이프/`--stdin` 검사는 반환 경로를 얻지 못해 검증으로 채택하지 않고 명시적 경로 인수로 확인했다. `git ls-files -ci --exclude-standard`의 기존 추적 제외 대상은 0개다.
- 보존/문서: staged diff SHA-256이 작업 전후 같고, 문서 링크·공백·101개 작업 ID 유지와 `git diff --check`를 확인했다. 이번 변경은 제외 규칙과 문서에 한정되어 Gradle 테스트는 실행하지 않았다. staging·커밋·푸시는 수행하지 않았다.

## Git 스킬 후속 · 작업 이슈와 단일 PR

- 사용자 요청: 실제 작업 시작 전에 이슈를 만들고 이슈 하나당 PR 하나만 사용하며, PR이 머지되면 이슈를 종료하는 절차를 Git 스킬에 추가.
- 변경: Git 전달 스킬에 최종 작업 ID·이슈·PR 대응, 시작 전 중복 조회와 이슈 등록, 모든 상태의 기존 PR 재사용, 생성 타임아웃 후 상태 확인, 실제 머지 후 이슈 종료 확인을 추가했다. 작은 단위 개발 스킬·`AGENTS.md`·하네스·구현 계획에도 착수 시 호출과 상태 기록을 연결했다.
- 경계: 스킬·계획 작성 요청은 원격 실행과 구분한다. 실제 작업 착수에는 이슈 등록을 포함하며, 커밋·푸시·PR 전달은 요청 범위, 머지는 사용자의 해당 지시를 따른다. 닫힌 미머지 PR이나 자동 머지 예약을 이슈 완료로 처리하지 않는다. 비기본 브랜치 또는 자동 종료 비활성 시에도 실제 머지 후 이슈 상태를 확인한다.
- 검증: 공식 `quick_validate.py`로 초안과 설치본 두 스킬의 형식 검사 통과, 검토본/설치본 SHA-256 일치, 상대 링크·공백·`git diff --check` 확인. 기존 staged diff SHA-256이 같고 계획의 101개 작업 ID를 유지했다.
- 독립 사용 검증: 스킬 수정만 요청, 실제 M1-01 착수, 기존 draft 재사용, 미머지 close, 비기본 브랜치 머지, 생성 timeout/조회 실패, 자동 머지 예약, 머지 후 새 버그의 8개 가상 사례를 검토했다. 보고서: `build/skill-validation/issue-pr-policy/forward-check.md`.
- 실제 실행 범위: 스킬·문서 수정과 검증만 수행했다. 이슈/PR 생성·커밋·푸시·머지·이슈 종료는 실행하지 않았고 Gradle 테스트도 재실행하지 않았다. 이번 변경은 에이전트 작업 절차이며 GitHub에서 두 번째 PR 생성을 차단하는 서버 자동화는 아니다.

## Git 스킬 후속 · 커밋 작성 규칙

- 사용자 요청/선택: 커밋 규칙 추가, Conventional Commits 형식 선택.
- 변경: Git 전달 스킬에 `type(scope): 한국어 요약`, 선택 가능한 scope, 타입별 용도, 실제 작업 이슈의 `Refs #번호`, 본문·호환성 변경 표시를 추가했다. `AGENTS.md`와 하네스에서 같은 규칙을 참조한다.
- 작업 단위: 한 커밋은 하나의 검증 가능한 목적이며 필요한 코드·테스트·문서를 함께 담는다. 한 이슈/PR에 여러 작은 검증 완료 커밋을 허용한다. Red 증거를 위해 실패 상태를 전달하지 않으며 문서·데이터·코드의 기존 검증 범위는 유지한다.
- 이슈/이력: 종료 키워드는 PR 본문에 두고 커밋에서는 `Refs`를 사용한다. squash가 선택됐을 때 최종 메시지도 확인한다. 새 규칙을 이유로 기존 staged 변경이나 과거 커밋을 수정하지 않고, 단순 커밋/푸시 지시를 이력 재작성 권한으로 확대하지 않는다.
- 검증: 공식 Conventional Commits 1.0.0 문서 확인, `quick_validate.py` 초안/설치본 통과, 검토본·설치본 해시 일치, 로컬 링크·커밋 규칙 앵커·공백·`git diff --check` 확인. staged diff SHA-256이 작업 전후 동일하다.
- 독립 검토: 기능+테스트, 문서만 변경, 호환성을 깨는 API 변경, 같은 PR의 여러 작은 커밋, 타 작업 staged/이미 푸시한 이력의 5개 사례를 검토했다. 예시는 실제 이슈·구현·검증 결과가 아님을 명시했다.
- 실행 범위: 스킬과 지침·기록만 수정했다. 실제 커밋·이슈/PR 생성·푸시·머지, 자동 hook/commitlint 설치, Gradle 테스트 실행은 하지 않았다.

## Git 작성 템플릿

- 사용자 요청: 커밋·이슈·PR 작성에 사용할 템플릿 추가.
- 추가: 커밋용 `.gitmessage.txt`, GitHub 작업 이슈용 `.github/ISSUE_TEMPLATE/task.md`, PR용 `.github/pull_request_template.md`, [사용 안내](git-templates.md). README·하네스·Git 전달 스킬에서 연결했다.
- 반영한 계약: Conventional Commits와 한국어 요약, 커밋의 `Refs`와 PR의 단일 `Closes` 구분, 작업 ID·이슈·PR 대응, 착수 전 검증 계획과 실제 수행 결과 구분, TDD 비대상 이유, 민감정보 제외, 실제 머지 후 이슈 종료 확인.
- 검증: 이슈 YAML frontmatter, 미선택 체크박스, 로컬 링크·공백·충돌 표시와 Git 제외 대상 여부를 확인했다. Git `stripspace --strip-comments`에서 미작성 커밋 템플릿이 빈 메시지로 정리되고 합성 메시지의 제목·본문 참조는 유지됨을 확인했다. 합성 입력은 실제 이슈나 커밋이 아니다.
- 스킬/보존: `quick_validate.py` 초안·설치본 형식 검사, 검토본과 설치본 SHA-256 일치, `git diff --check`와 `git diff --cached --check` 통과. staged diff SHA-256은 변경 전과 같다. 독립 검토에서 필수 수정 사항은 없었다.
- 적용 범위: 작성 양식과 문서만 변경했다. GitHub 자동 노출은 기본 브랜치 반영 후 적용되며 서버에서 PR 개수를 강제하는 자동화는 아니다. 실제 커밋·이슈/PR 생성·푸시·머지·Git 설정 변경과 Gradle 테스트는 실행하지 않았다.

## 구현 전 실행 환경 점검 · 2026-09-25

- 요청 범위: Java·Gradle, Docker·Compose·WSL, GitHub 접근과 기존 하네스 실행 확인. 구현 착수나 영구 환경 설정 변경은 포함하지 않았다.
- 기본 셸: `./gradlew.bat --version`은 `JAVA_HOME` 미설정 및 PATH의 Java 부재로 실패했다. 환경 오류이며 동작 테스트의 Red가 아니다.
- Java/Gradle: 설치된 Microsoft OpenJDK 21.0.11과 기존 사용자 Gradle 캐시를 이번 검증 프로세스의 `JAVA_HOME`·`GRADLE_USER_HOME`에 지정했다. `./gradlew.bat --version`에서 Gradle 9.7.1과 Launcher/Daemon JVM 21.0.11을 확인했다. 이는 터미널 검증 조건이며 IntelliJ 개발의 선행 조건으로 시스템 `JAVA_HOME` 설정을 요구하지 않는다.
- 사용자 개발 환경: IntelliJ를 사용한다. IDE의 프로젝트·Gradle 실행은 JDK 21을 기준으로 하고, 에이전트의 터미널 검증에서는 필요할 때 해당 프로세스에만 JDK를 지정한다. 로컬 IDE 설정의 Project SDK 참조는 `ms-21`이며, IDE에서의 실제 Gradle 실행은 이번 점검에서 직접 수행하지 않았다.
- 실제 재검증: `./gradlew.bat --offline --no-daemon check --rerun-tasks --warning-mode all` 성공, 5개 태스크 실제 실행. XML 결과 기준 일반 테스트 34개 + 시드 84개 = **118개 통과**, 실패·오류·건너뜀 0개. `clean`은 실행하지 않았다.
- Docker: CLI/Engine 28.0.1, Compose v2.33.1-desktop.1, Docker Desktop 4.39.0 확인. 현재 Desktop/backend가 실행 중이며 활성 `desktop-linux`와 `default` 컨텍스트에서 Linux 엔진 조회에 성공했다. WSL의 `docker-desktop`은 Running/WSL2다. 이전 점검의 엔진 연결 실패와 달리 현재 기동 상태는 정상이다.
- 접근 제약: 샌드박스 안의 Docker 설정·컨텍스트/WSL 접근 오류는 호스트의 엔진 장애와 구분했다. 샌드박스 밖 읽기 전용 조회로 실제 상태를 확인했으며 Docker 설정·컨텍스트·서비스·컨테이너를 변경하지 않았다.
- GitHub: `gh auth status --active` 정상, `gh repo view`로 `letter333/commerce-search-lab` 접근·기본 브랜치 `main`·현재 계정 ADMIN 권한을 확인했다. 인증정보 원문은 출력하거나 기록하지 않았다.
- 한계/후속: 이번 빌드는 기존 캐시를 사용한 오프라인 검증이다. 새 의존성 다운로드, Elasticsearch/Nori 이미지 실행, 실제 클라이언트 연결·검색 평가는 확인하지 않았으며 M1-01~M1-04에서 이어간다.
- 보존: 프로젝트 코드·영구 환경변수·Git 설정·기존 staged 내용을 변경하지 않았다. staged diff SHA-256 일치 확인. 작업 기록만 갱신했으며 이슈/PR 생성·커밋·푸시는 하지 않았다.

## H0-01 · 기존 개발·평가 기반 최초 전달

- 사용자 요청: 현재까지 준비한 내용을 커밋하고 푸시한다.
- 이슈: [#1](https://github.com/letter333/commerce-search-lab/issues/1). 기존 모든 상태의 이슈·PR을 조회해 없음을 확인한 뒤 등록했다. 기존 작업의 작성 시점과 이번 전달 이슈 등록 시점을 구분한다.
- 작업 브랜치: `codex/issue-1-development-foundation`. 대상: `letter333/commerce-search-lab`의 `main`. PR: [#2](https://github.com/letter333/commerce-search-lab/pull/2), MERGED. 이슈 #1은 CLOSED / COMPLETED 상태다.
- 포함 범위: 기존 staged 기획·시드와 현재 작업 트리의 Java/Gradle 골격, 시드 검사·평가 도구, 계약·상세 계획·스킬·Git 템플릿·민감정보 제외 규칙. 기존 staged 기획의 개인 경로는 이미 수정된 작업 트리본을 반영한다.
- 검증 근거: 위 실행 환경 점검에서 `check --rerun-tasks`로 118개 통과를 확인했으며 이후 제품/테스트/빌드 코드는 바뀌지 않았다. 이번 전달은 기존 검증 결과와 최종 index 일치, 문서·스킬·제외 규칙을 점검한다. 관찰하지 않은 Red 이력은 추가하지 않는다.
- 독립 검토: 후보 32개 파일의 민감정보·산출물 검토와 하네스/문서 범위 검토에서 차단 사항은 없었다. `gradlew`는 새 Unix checkout의 실행을 위해 index에 실행 모드 `100755`로 포함한다.
- 작성자: 기존 초기 커밋과 인증 계정에 대응하는 GitHub 비공개 이메일을 이번 커밋 명령에만 적용한다. 전역/로컬 Git 작성자 설정은 변경하지 않는다.
- 전달 점검: origin fetch/push 저장소 일치, 당시 원격 `main`과 로컬 HEAD 일치, 추가 push refspec·mirror·별도 pushRemote 없음 확인. 최초 커밋·푸시 요청에는 머지를 포함하지 않았으며, 이후 별도 머지 요청을 받아 아래와 같이 처리했다.
- 최종 index: 검토한 32개 경로 모두 작업 트리와 내용이 같고, `gradlew` 실행 모드 `100755`를 확인했다. 개인 절대 경로와 제한된 비밀 패턴 후보·추적된 제외 대상은 없었다. 문서 링크·이슈 템플릿 메타데이터·스킬 4개의 형식 검사와 `git diff --cached --check`를 통과했다.
- 전달 결과: 초기 기준선 커밋 `43341237a48e57a4ce9e0b759e30b157f2e2f0d7`을 작업 브랜치에 푸시하고 실제 원격 SHA 일치를 확인했다. 모든 상태의 PR을 재조회한 뒤 PR #2 하나를 생성했으며, 본문의 `Closes #1`로 머지 후 종료 대상을 명시했다. 전달 기록 커밋 `c3dfe2acc561871455678e09d2dd6576c39223c1`도 같은 PR에 반영했다.
- 후속 머지: 사용자 지시로 2026-09-25 00:52:13 KST에 PR #2를 `main`으로 머지했다. 기존 두 커밋을 유지하는 merge commit은 `5c328794b2a9b835f101dee11dbb2d0c3202dff0`이다. 실제 MERGED 상태·시각·대상 브랜치와 `origin/main` 포함을 확인했다.
- 이슈 종료: #1은 2026-09-25 00:52:14 KST에 자동 종료됐으며 CLOSED / COMPLETED 상태를 확인하고 완료 기록을 갱신했다. 중복 종료 명령은 실행하지 않았다.
- 머지 검증/보존: GitGuardian 검사 SUCCESS, 검토한 PR head와 기존 테스트 대상 코드 일치, 머지 결과 tree와 PR head tree 일치를 확인했다. 새 코드 변경이 없어 테스트를 반복하지 않았다. 로컬 미커밋 M1 계획 파일 3개가 원격 머지 동안 보존됐는지 해시로 확인한 뒤 이 기록과 계획의 현재 상태만 갱신했다.

## M1-01 · 버전 조합과 실행 계약 확정

- 세부계획: [M1-01 실행 순서와 완료 기준](m1-01-plan.md). 계획 작성 당시 H0 PR #2와 이슈 #1은 OPEN이었으며 계획 문서만 작성했다. 이후 H0 머지와 사용자 작업 시작 지시를 받아 M1-01에 착수했다.
- 현재 기반: 최신 `origin/main`의 `5c328794b2a9b835f101dee11dbb2d0c3202dff0`. 이슈 [#3](https://github.com/letter333/commerce-search-lab/issues/3)을 등록하고 `codex/issue-3-search-engine-contract`에서 작업한다. 기존 미커밋 계획 3개 파일은 브랜치 전환 전후 해시가 같아 보존을 확인했다.
- 계획 검증: 공식 Elastic·Spring Boot 자료의 호환성·전송/JSON 의존성·플러그인·Docker 조건을 확인해 근거 링크를 연결했다. 12개 체크리스트 순서, 문서 링크·앵커·공백, 기존 101개 구현 작업 ID 유지와 `git diff --check`를 확인했다. 독립 검토에서 전달/머지와 후속 작업 진입 조건을 분리했다. 문서 작업으로 Gradle 테스트는 재실행하지 않았다.
- 결과 문서: [검색 엔진 버전과 로컬 실행 계약](search-engine-setup.md). ES·Nori 9.4.7, Boot가 관리하는 Java Client·Rest5 9.4.5, 명시적 Jackson3JsonpMapper를 선정했다. 서버 후속 patch 반영과 기존 앱 버전 유지가 근거다.
- 의존성 관찰: 기존 JDK 21과 캐시로 `dependencies --configuration runtimeClasspath`, `dependencies --configuration testRuntimeClasspath`, `dependencyInsight --dependency jackson-databind --configuration testRuntimeClasspath`를 각각 `--offline --no-daemon`으로 실행해 모두 성공했다. runtime/testRuntime의 Jackson 3.1.5를 확인했고 현재 ES Client·Rest5는 없다. Client POM과 Boot BOM 비교의 하향/상향 예상은 실제 추가 후 resolve 결과와 구분해 기록했다.
- 환경 관찰: Docker Client/Engine 28.0.1, Compose v2.33.1-desktop.1, Linux x86_64, CPU 20개·메모리 약 11.6GiB, 조회 시 9200/9300 LISTEN 없음. WSL `vm.max_map_count=262144`; 프로젝트 권장 기준 1048576 적용은 M1-02 후속이며 현재 값을 이유로 기동 실패를 주장하지 않는다.
- 실행 계약: 단일 노드·호스트 loopback 9200, HTTP/transport TLS와 인증 유지, Git 제외 파일로 비밀번호 주입, 컨테이너 2GiB·자동 힙, 데이터 named volume·인증서 보존, 도달성/실제 readiness와 실패 조건 분리. 구체적인 값·명령과 공식 근거는 결과 문서에 있다.
- 검증 상태: 문서 링크·앵커·공백·101개 작업 ID 유지, 선정 버전 표기와 PowerShell 명령 구문 검사, 조사 산출물·개인키·비밀번호 경로의 Git 제외를 통과했다. 버전/의존성과 실행 계약의 독립 검토를 마쳤으며 인증서 ZIP 출력과 최종 파일 배치 경로를 명확히 했다. 기존 제품/테스트/빌드/시드와 index는 변경하지 않았고 새 동작 테스트나 반복 `check`를 실행하지 않았다. 이전 118개 통과를 새 엔진·Client 검증으로 표시하지 않는다.
- 미검증/전달: 이미지 pull·빌드·컨테이너 기동·인증서/비밀번호 생성·커널 변경·Client 의존성 추가·실제 검색은 실행하지 않았다. 사용자 전달 요청에 따라 문서 5개를 커밋·푸시하고 [PR #4](https://github.com/letter333/commerce-search-lab/pull/4)를 생성했다. base `main`, head `codex/issue-3-search-engine-contract`. 이후 사용자 머지 지시를 받아 PR MERGED, 이슈 #3 CLOSED / COMPLETED 상태를 확인했다.
- 전달 검증: 본문 커밋 `abea9fa1affafc600437d225b8908acd50c5a653`의 원격 SHA 일치, 문서 5개와 최종 index 일치, 링크·앵커·PowerShell 구문·공백·민감정보 제외를 확인했다. 초기 PR 생성 요청은 GitHub 오류를 반환해 모든 PR·이슈 연결을 재조회했고, 미생성을 확인한 뒤 REST API로 PR #4 하나를 생성했다. 후속 전달 기록 커밋 `e155a8124f24ef0ac0c7ac9f3282767b020d0c09`도 같은 브랜치/PR에 반영하고 원격 SHA 일치를 확인했다.
- 후속 머지: 사용자 지시로 2026-09-25 01:50:02 KST에 PR #4를 `main`으로 머지했다. 기존 두 커밋을 유지하는 merge commit은 `ae23b3abbc888824dba12a9514d976a63c0770d2`다. 실제 MERGED 상태·시각·대상 브랜치를 조회했고 이슈 #3은 01:50:04 KST에 자동 종료됐다. 중복 종료 명령은 실행하지 않았다.
- 머지 검증/정리: GitGuardian 검사 SUCCESS, 독립 문서 검토와 `git diff --check` 통과. 머지 결과 tree가 검토한 PR head tree와 같고 두 커밋을 포함함을 확인했다. 코드 변경이 없어 테스트를 반복하지 않았다. 깨끗한 작업 트리에서 로컬 `main`을 `origin/main`으로 fast-forward한 뒤 이 머지 기록만 갱신했다. 기록은 로컬 미커밋 상태로 보존하며 기존 작업 브랜치는 유지한다.
- 임시 조사 산출물: `build/m1-01/runtime-dependencies.txt`, `test-runtime-dependencies.txt`, `jackson-insight.txt`, `dependency-summary.json`. 모두 Git 제외 대상이며 실제 검색 결과가 아니다.

## M1-02 · Nori 포함 검색 엔진 기동

- 착수: 이슈 [#5](https://github.com/letter333/commerce-search-lab/issues/5), 브랜치 `codex/issue-5-nori-engine`, 기준선 `ae23b3abbc888824dba12a9514d976a63c0770d2`. 모든 상태의 이슈·PR을 확인해 M1-02가 없음을 확인한 뒤 등록했다. 기존 M1-01 머지 기록은 브랜치 전환 전후 파일 해시가 같아 보존했다.
- 검증 선행: 구성 작성 전 `config --quiet`와 `Test-Elasticsearch.ps1`에서 compose 부재, 구성 후 CA 부재를 확인했다. 실행 정책에 막힌 호출은 미실행으로 구분하고 해당 프로세스에만 `-ExecutionPolicy Bypass`를 적용했다. 환경 오류를 검색 로직의 Red로 기록하지 않는다.
- 구현: Nori Dockerfile·빌드 allowlist, Compose, 인증서 입력, 초기화·검증 PowerShell 스크립트, Windows secret 권한 보완 wrapper와 [실행 안내](local-elasticsearch.md)를 추가했다. 환경 예시는 값 없는 안내이며 실제 비밀 파일은 `.local/`에서 관리한다.
- Docker 복구: 착수 시 엔진 중지·WSL 값 65530이었다. 실제 시작 실패는 분석용 stale socket의 Windows 오류 1920이었다. 비필수 WSL 권한 경고를 원인으로 보던 초기 추론은 정정했다. 정상 재시작 시간 초과 후 이번 작업의 프로세스만 정리하고, 소켓 하나만 들어 있는 runtime 폴더를 같은 Docker 경로의 백업 이름으로 보존해 복구했다. 정상 Desktop/WSL 재시작에서도 재현돼 같은 범위로 다시 복구했다. 기존 이미지·볼륨은 유지했고 영구 해결로 표시하지 않는다.
- 호스트 조치: 복구 후 Engine 28.0.1·Compose 2.33.1, Linux x86_64·CPU 20개·메모리 12423901184 bytes를 확인했다. `/etc/sysctl.conf` 항목 유지와 `.wslconfig` 부팅 인자 적용에도 Docker 시작 후 실제 값이 262144였다. 이번에 추가한 두 영구 설정만 되돌리고 Docker 시작 후 `sysctl -w vm.max_map_count=1048576` 적용·재조회에 성공했다. Desktop/WSL 재시작 후 재적용이 필요하다.
- 이미지 확보: Docker 내부 R2 CDN 전송은 시간 초과했다. 호스트 curl로 같은 공식 manifest/config/layer를 받아 SHA-256·크기와 압축해제 diff_id를 검증한 archive를 불러왔다. 공식 linux/amd64 manifest는 `sha256:e083ef4f6b3d5d49115f2893e384d07b94c02c17093f6b7f05e9b2d2821a079c`, 기본 이미지 ID는 `sha256:e282267314c936a06e549825f40ab263c206bfca9f29662c6fb9631e180aaf44`다. 이후 Nori 빌드 성공, 최종 이미지 ID는 `sha256:d30a3030b85f5b35ebe449e66624093c03fb965e7f2b5e69fb1c4eeb69f2b8a3`. Docker 인증·프록시·버전은 바꾸지 않았다.
- 초기화 검증: 이미지 부재 시 파일 생성 없이 실패, 정상 생성, 재실행 시 네 파일 해시 동일, 일부 파일이 없는 상태의 거부·복구 후 해시 동일을 확인했다. 인증서 유효기간과 DNS elasticsearch/es01/localhost·IP 127.0.0.1 전체 SAN을 keytool로 검증했다. 최초 keytool 인자 인용 오류는 수정 후 재실행했다.
- 첫 기동 실패/보완: ES 9.4.7이 Windows secret의 777 권한을 거부해 exit 1로 종료했다. 읽기 전용 원본을 유지하고 0700 tmpfs의 모드 400·UID/GID 1000:0 복사본 경로를 원래 entrypoint에 전달하도록 수정했다. 실제 권한·소유자, 입력 네 마운트의 읽기 전용, 컨테이너 2GiB와 호스트 loopback 9200만 공개됨을 확인했다. 9300은 호스트에 바인딩하지 않았다.
- TLS 실패/보완: Schannel이 개발 CA의 폐기 정보 부재로 curl 60을 반환했다. Schannel에서만 best-effort 폐기 조회를 사용해 정상 CA 요청의 401을 확인했고, 잘못된 CA와 SAN에 없는 호스트는 각각 curl 60으로 거부됐다. CA 체인·hostname 검증을 해제하지 않았다.
- 실제 Green: `powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File scripts/Test-Elasticsearch.ps1 -AsJson` 성공. 인증된 ES·Nori 9.4.7, commerce-search-lab·정상 노드 1개·실패 0개, timed_out=false·green 확인. cluster UUID `UNO3XeASSbifnQAr2cP3fQ`, CA 파일 SHA-256 `4CEE9280C8C599C91A4186708D8DA5BFAD54F4D25AD6BA3AA99AF36FCACFFF3A`.
- 재기동 검증: down/up 직후 handshake 코드 35와 보안 인덱스 복구 중 401을 각각 관찰했다. 복구 후 같은 비밀번호로 성공하는 것을 확인하고 전체 180초 안에서 준비를 기다리도록 보완했다. 다시 down/up 직후부터 검증해 성공했으며 UUID·CA·버전·인증과 기존 named volume 생성 시각이 같았다. 볼륨 삭제·비밀번호 재설정은 없었다.
- 회귀 검증: `./gradlew.bat --offline --no-daemon check` 성공. test·verifySeeds 실제 실행의 XML 기준 118개·실패/오류/건너뜀 0개. 최종 코드 보완 후 같은 check도 성공(5개 태스크 up-to-date)했다. JDK·캐시는 프로세스에만 지정했고 제품 코드·시드·Gradle 의존성은 그대로다.
- 최종 점검: 후보 15개 파일에서 실제 로컬 비밀번호와 개인키·토큰 패턴이 없고 추적된 제외 파일·staged 변경이 없음을 확인했다. 상대 링크 51개, PowerShell 스크립트 2개·문서 명령 블록 10개, wrapper LF와 diff 공백 검사를 통과했다. 독립 리뷰의 차단 사항은 없었다. 재기동 후 tmpfs·모드 400·원본과 복사본 동일, 잘못된 비밀번호의 401, 컨테이너 healthy를 확인했다. 복사본 비교의 최초 cmp 명령은 이미지에 없어 실패했으며 SHA-256을 메모리에서 비교해 확인하고 값·해시는 출력하지 않았다.
- 증거: 안전한 전후 요약과 이미지 검증은 `build/m1-02/`, 원본 엔진 로그는 `.local/elasticsearch/logs/`에 보관하며 Git 제외다. 구현 완료 시 엔진이 로컬에서 실행 중임을 확인했다.
- 전달 결과: 사용자 요청으로 구현 커밋 `2d8398bd893c5d33477e10b29f47dfdc5a9600ca`를 푸시하고 실제 원격 SHA 일치를 확인했다. 모든 상태의 PR과 이슈 연결을 다시 조회한 뒤 [PR #6](https://github.com/letter333/commerce-search-lab/pull/6) 하나를 생성했다. base `main`, head `codex/issue-5-nori-engine`, 본문 `Closes #5`와 실제 종료 대상 #5의 연결을 확인했다. 이후 별도 머지 요청을 받아 PR MERGED, 이슈 #5 CLOSED / COMPLETED 상태를 확인했다.
- 전달 검증: 후보 15개 파일의 독립 범위·민감정보 검토에 차단 사항이 없었다. 최종 index와 검증한 작업 트리 내용이 같고 공백·LF·실제 로컬 비밀번호·개인키·토큰 패턴·추적된 제외 파일 검사를 통과했다. 원격에 없는 구현 커밋 전체도 검사했다. 전달 기록 커밋 `82a46968f07de2a0033ce98a9eda63a8738b242d`를 같은 PR에 반영하고 최종 원격 SHA 일치와 GitGuardian 검사 SUCCESS를 확인했다. 코드 변경이 없어 회귀·엔진 검증을 반복하지 않았다.
- 후속 머지: 사용자 지시로 2026-09-25 19:54:36 KST에 PR #6을 `main`으로 머지했다. 기존 두 커밋을 유지하는 merge commit은 `5dcfa231de4ba609ca26c50c8c238559dbdc793e`다. 실제 MERGED 상태·시각·대상 브랜치와 `origin/main` 포함을 확인했다. 이슈 #5는 19:54:38 KST에 자동 종료됐으며 CLOSED / COMPLETED를 확인했다. 중복 종료 명령은 실행하지 않았다.
- 머지 검증/정리: 머지 결과 tree와 검토한 PR head tree가 같고 기존 두 커밋이 보존됐다. 독립 검토에서 새 차단 사항이 없었으며 검증 대상 구현이 바뀌지 않아 테스트를 반복하지 않았다. 깨끗한 작업 트리에서 로컬 `main`을 `origin/main`으로 fast-forward했다. 이 머지 기록만 로컬 미커밋 상태로 남기며 기존 작업 브랜치는 유지한다.

## M1-03 · 공식 Java Client 연결 완료

- 착수 기록: 사용자 구현 요청에 따라 이슈 [#7](https://github.com/letter333/commerce-search-lab/issues/7)을 등록했다. 모든 상태의 이슈·PR을 조회해 중복이 없음을 확인했다. 브랜치 `codex/issue-7-java-client`, 기준선 `5dcfa231de4ba609ca26c50c8c238559dbdc793e`. 기존 미커밋 M1-02 머지 기록은 전환 전후 해시가 같아 보존했다.

- 변경: 공식 Java Client·Rest5 9.4.5와 명시적 `Jackson3JsonpMapper`를 Spring 설정에 연결했다. 기본 비활성화, HTTPS origin·사용자명·경로·제한 시간 검증, CA 신뢰·호스트명 검증, 파일 기반 비밀번호, 연결/풀/TLS·응답/socket 제한을 적용했다. 설정·파일 오류에 입력값과 원래 예외의 민감 경로를 포함하지 않는다. HTTP 자원은 Rest5Client 빈 한 곳에서 종료한다.
- 테스트 분리: `elasticsearch` 태그의 `integrationTest`는 `check`와 분리하고 매번 실제 실행한다. 완료 태스크가 NO-SOURCE·태스크 건너뜀·실행 테스트 0개·전체 skipped를 거절한다. 기본 앱 컨텍스트 테스트는 ES 설정을 명시적으로 비활성화해 외부 환경변수에 영향받지 않는다.
- 의존성: compile/runtime/testRuntime 세 보고서와 Jackson·OpenTelemetry API의 `dependencyInsight`가 성공했다. Client/Rest5 9.4.5, HC5 5.6.4, Core5 5.4.3, Jackson 3 3.1.5, Jackson 2 2.21.5, Parsson 1.1.9, OpenTelemetry API 1.62.0·semconv 1.41.1을 확인했다. Jackson 2·OpenTelemetry의 관리 버전 하향을 확인했지만 실제 JSON/요청 경로에서 linkage 오류는 없었다. 전역 override·exclude는 추가하지 않았다. 상세 선택값은 [실행 계약](search-engine-setup.md)에 기록했다.

### TDD와 보강 검증

아래 명령은 설치된 JDK 21과 기존 Gradle 캐시를 해당 프로세스에 지정해 실행했다. 시스템 `JAVA_HOME`은 바꾸지 않았다. 의존성 최초 추가 시 온라인 `check`로 다운로드했고 이후에는 `--offline --no-daemon`을 사용했다.

| 단위·명령 | 실제 Red | Green·후속 확인 |
|---|---|---|
| `test --tests '*ElasticsearchPropertiesTest'` | 정상 endpoint 스텁의 null 반환으로 1개 실패; URL/노출 방지 17개, 필수 입력 13개, 제한 시간 12개, record 출력 노출 1개를 순차 재현 | 각 최소 구현 후 1 → 21 → 34 → 48 → 49개 통과 |
| `test --tests '*ElasticsearchConfigurationTest'` | 빈 설정 클래스에서 활성화 시 Client 부재: 2개 중 1개 실패 | 최소 빈 구성 후 2개 통과 |
| 같은 설정 테스트의 파일 검증 | 비밀번호 입력 허용·누락/잘못된 파일 오류 문제: 12개 중 9개 실패 | 파일 읽기/검증·안전한 오류 구현 후 12개 통과 |
| JSON·종료·TLS 제한·개행/UTF-8 보강 | 기존 구현에 대한 보호 테스트로 첫 실행부터 통과; Red로 주장하지 않음 | 설정 테스트 최종 19개 통과. 정상 종료와 후속 빈 초기화 실패 시 종료, 정지된 TLS handshake 제한 확인 |
| 기본 앱 컨텍스트 테스트 | `ES_ENABLED=true`와 잘못된 URL을 상속해 기본 테스트 실패 | 테스트에서 ES 비활성화를 명시한 뒤 통과 |
| 통합 검증 실행 여부 | NO-SOURCE 강제 시 기존 Test 태스크의 `doLast` 검사가 실행되지 않아 BUILD SUCCESSFUL로 끝남 | 별도 finalizer로 옮긴 뒤 NO-SOURCE와 전체 skipped 강제 실행 모두 종료 코드 1로 차단 |

파일 검증 테스트의 첫 컴파일은 존재하지 않는 AssertJ 메서드 때문에 실패했다. 테스트 API를 수정한 뒤 동작 실패를 별도로 확인했으며 컴파일 오류는 Red에 포함하지 않았다. 실제 엔진의 인증/TLS 실패를 검사하는 통합 테스트는 구성된 클라이언트의 동작 검증이며, 연결 환경 실패 자체를 검색 로직의 Red로 계산하지 않는다.

### 최종 결과와 경계

| 검증 | 실제 결과 |
|---|---|
| `./gradlew.bat --offline --no-daemon check` | 일반 테스트 102개 + 시드 84개 = **186개 통과**, 실패·오류·건너뜀 0. 엔진을 중지하고 ES 활성 환경변수와 존재하지 않는 인증 파일 경로를 지정한 상태에서도 성공 |
| `./gradlew.bat --offline --no-daemon integrationTest` | **4개 통과**, 실패·오류·건너뜀 0. 복구 후 연속 두 실행에서 integrationTest가 UP-TO-DATE/캐시로 대체되지 않고 실제 실행됨 |
| 실제 TLS·인증 연결 | ES 9.4.7 / cluster `commerce-search-lab` 조회 성공; 잘못된 비밀번호 401, 다른 CA에서 SSLHandshakeException, CRLF를 붙인 비밀번호 임시 복사본으로 정상 조회 |
| 엔진 부재 | 엔진 중지 중 실제 정보 조회 테스트 1개 실행·1개 실패, ConnectException. 0건/건너뜀으로 성공 처리하지 않음 |
| NO-SOURCE·전체 skipped | `build/m1-03/no-source.init.gradle` 및 `all-skipped.init.gradle`로 유도한 실행이 모두 검증 오류로 차단됨 |
| 엔진 복구·보존 | green 복구. cluster UUID `UNO3XeASSbifnQAr2cP3fQ`, CA SHA-256 `4CEE9280C8C599C91A4186708D8DA5BFAD54F4D25AD6BA3AA99AF36FCACFFF3A` 동일. 기존 인증 파일 4개의 내용 동일성을 해시로 비교했고 비밀값·개인키 해시는 출력하지 않음 |

엔진 중지 검사 보조 스크립트는 의도한 ConnectException 발생 후 XML 문서 전체의 InnerText를 잘못 판독해 별도 오류를 냈다. finally에서 엔진 복구와 인증 파일 보존 검사가 완료됐고, 실패 노드의 InnerText·유형과 XML의 실행 1/실패 1/건너뜀 0을 직접 확인했다. 보조 판독을 수정했으며 이 보조 오류를 제품 테스트 실패나 성공으로 섞지 않았다. 이후 위의 전체 통합 테스트 4개를 다시 통과했다.

- 민감정보: 실제 비밀번호와 해당 Basic 인증 인코딩, 개인키·토큰 패턴을 변경 후보와 테스트 XML에서 검사했다. 실제 인증 파일·개인키·임시 보고서는 Git 제외 상태다. 테스트 fixture는 개발 엔진과 무관한 **공개 CA 인증서만** 포함하며 생성용 개인키 저장소는 `build/`에 남겼다.
- 검토: 설정/TLS/자원 수명, 테스트 실행 경계, 문서의 독립 검토를 반영했다. 자원 종료는 일반 컨텍스트 테스트, 실제 연결은 통합 테스트로 구분해 안내했다. 최종 후보 17개 파일의 민감정보 검사, 상대 링크 62개와 diff 공백 검사가 통과했고 추적된 제외 파일·staged 변경은 없었다. 구현·시드 범위를 벗어난 변경과 검색 품질 주장은 없다.
- 증거: `build/reports/tests/{test,verifySeeds,integrationTest}/`, `build/test-results/`, `build/m1-03/`는 로컬 임시 산출물이며 Git 제외다. 최종 엔진은 실행 중이다.
- 전달 상태: [이슈 #7](https://github.com/letter333/commerce-search-lab/issues/7)에 완료 체크리스트와 실제 검증 결과를 반영하고 OPEN 상태를 확인했다. 구현·검증 완료, 커밋·푸시·PR 생성은 아직 하지 않았다. 실제 PR 머지 후 이슈 종료를 확인한다.

## 다음 작업 · M1-04 테스트 인덱스 격리

- 목적: 실제 엔진 테스트가 서로와 개발용 인덱스에 영향을 주지 않게 한다. 착수 전에 해당 작업 ID의 이슈·모든 상태 PR을 조회하고 작업 이슈를 연결한다.
- 먼저 작성(I): 서로 다른 고유 테스트 인덱스에 문서 하나씩 저장·조회하고, 한쪽을 정리해도 다른 쪽 데이터가 남는지 확인한다.
- 범위: 테스트별 고유 이름과 실패 시 정리. 삭제는 해당 테스트가 생성한 구체적인 이름으로 제한한다. M1-03의 통합 테스트 태스크와 제품 Client를 재사용한다.
- 후속 경계: 상품 로딩·색인·검색 API와 Nori 토큰 품질은 각 후속 작업에서 검증한다. 현재 연결 검증은 검색 품질 측정이 아니다.
- API는 [평가 계약](evaluation-contract.md)에 맞춘다. 변경이 필요하면 실행기·계약·테스트를 함께 갱신한다.
- 데이터 관련성은 사람이 재검토한 뒤 검토자·일자·변경 근거를 기록한다. 자동 시드 검사만으로 M0를 완료 처리하지 않는다.
- 기존에 한 항목으로 적었던 M1-01 실행 환경 고정을 세분화했다. 이후 구현은 [상세 계획의 작업 ID](implementation-plan.md)를 사용하며 실제 결과는 이 작업 기록에 남긴다.

## 결정 기록

| 일자 | 결정 | 이유 |
|---|---|---|
| 2026-09-24 | 개발 하네스와 검색 평가 하네스를 함께 준비 | 사용자 선택 |
| 2026-09-24 | 기존 Java/Gradle 테스트 환경에서 하네스 실행 | 별도 Python·Node 런타임 없이 현재 저장소 활용 |
| 2026-09-24 | 실제 API 평가를 `check`에서 분리 | API 구현 전에도 검증 가능하고 개발 테스트에 외부 서비스 불필요 |
| 2026-09-24 | 제안 선택 평가는 명시적 옵션으로 분리 | 원문 자동 대체와 평가 점수 혼합 방지 |
| 2026-09-24 | 실제 착수 전 이슈 등록, 이슈당 PR 최대 하나, 실제 머지 후 이슈 종료 | 사용자 지정 작업 흐름 |
| 2026-09-24 | Conventional Commits, 한국어 요약과 실제 이슈의 Refs 참조 | 사용자 선택 |
| 2026-09-25 | ES·Nori 9.4.7 + Java Client·Rest5 9.4.5, 명시적 Jackson3JsonpMapper | 서버 patch 수정 반영, Boot 관리 버전과 기존 Jackson 3 사용 유지. 실제 연결은 M1-03 검증 |
| 2026-09-25 | 단일 노드 loopback·TLS·비밀번호 파일 주입, 컨테이너 2GiB·자동 힙 | 로컬 개발 재현 조건 확정. 실제 기동/자원 적합성은 M1-02 검증 |
