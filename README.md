# commerce-search-lab

한국어 상품 검색 품질 실험실. 같은 데이터·질의에서 BM25, 별칭 확장, 랭킹 변경을 비교하고 실패 사례와 측정 조건을 기록한다.

현재는 Spring Boot 기본 골격과 개발·검색 평가 하네스, Elasticsearch·Nori 9.4.7 로컬 기동 구성이 준비되어 있다. 다음 단계는 공식 Java Client 연결이다. 검색 API와 상품 색인은 아직 구현하지 않았다. 상품 50개와 시나리오 30개는 가상 개발 시드다.

JDK 21과 저장소의 Gradle Wrapper를 사용한다. 최초 실행은 의존성 다운로드가 필요하다.

```powershell
./gradlew.bat verifySeeds  # 개발 시드 일관성 검사
./gradlew.bat check        # 시드 검사 + 코드·평가 도구 테스트
```

macOS/Linux에서는 `./gradlew`를 사용한다. 검색 API 구현 후 실제 평가는 다음처럼 실행한다.

```powershell
./gradlew.bat evaluateSearch --args="--base-url http://localhost:8080 --modes baseline"
```

- [프로젝트 계획](docs/search-project-plan.md)
- [작은 작업 단위별 상세 구현 계획](docs/implementation-plan.md)
- [하네스 사용법과 단계별 완료 기준](docs/harness.md)
- [로컬 Elasticsearch·Nori 기동과 재기동 검증](docs/local-elasticsearch.md)
- [커밋·이슈·PR 템플릿 사용법](docs/git-templates.md)
- [검색 평가 실행·API 계약](docs/evaluation-contract.md)
- [현재 상태와 다음 작업](docs/worklog.md)
- [AI 작업 지침](AGENTS.md)
