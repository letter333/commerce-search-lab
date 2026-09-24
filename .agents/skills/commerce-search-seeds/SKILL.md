---
name: commerce-search-seeds
description: commerce-search-lab의 상품 카탈로그·검색 시나리오·기대 상품 ID를 추가하거나 수정하고 개발 시드 계약 실패를 진단한다. 데이터 버전과 관련성 판정을 함께 관리할 때 사용하며, 검색 API 실행·지표 비교는 commerce-search-evaluate의 범위다.
---

# 검색 개발 시드 변경

현재 데이터의 계약을 확인하고, 요청한 변경이 상품·질의·판정 중 어디에 영향을 주는지 좁힌다. 모든 경로와 명령은 저장소 루트 기준이다. 이 파일은 `.agents/skills/commerce-search-seeds/`에 위치한다.

## 필요한 근거

- [프로젝트 계획](../../../docs/search-project-plan.md)의 데이터·평가 설계.
- 현재 `docs/catalog-50.json`, `docs/search-scenarios-30.json`에서 변경 대상과 같은 `intent_group`의 사례.
- `src/test/java/org/letter33/commercesearchlab/harness/DevelopmentSeedContractTests.java`의 실패한 검증 또는 변경할 계약. 필드 추가 시 `NEED_FIELDS`, `FILTER_FIELDS`, `matches`를 확인한다.
- 실행기 입력도 바뀔 때만 [평가 계약](../../../docs/evaluation-contract.md)과 `src/test/java/org/letter33/commercesearchlab/evaluation/SearchEvaluator.java`의 `load`, `parseResponse`를 확인한다.

## 변경과 판정

1. 데이터 변경인지 검색 구현의 오류인지 먼저 구분한다. 테스트 실패나 낮은 검색 점수만으로 기대 상품을 삭제하거나 정답 없는 질의로 바꾸지 않는다.
2. 기존 상품 ID·SKU와 시나리오 ID는 안정적으로 유지한다. 상품 속성·가격·활성 상태를 바꾸면 그 상품을 기대 결과에 포함하거나 제외해야 하는 시나리오를 함께 검토한다.
3. 기대 ID는 현재 활성 상품 중 `information_need`와 `request.filters`를 모두 만족하는 **전체 집합**이다. 실제 검색 상위 결과를 정답으로 복사하지 않는다. `material_contains`는 부분 문자열 조건, `price_min`/`price_max`는 경계 포함 조건이다.
4. 오타·한영 오류는 `suggest_only`, 정답 후보 목록, 명시적 제안 선택 후 평가와 원문 추가 보고를 함께 설정한다. 정상·별칭·브랜드·SKU 질의는 `no_spelling_suggestion`이며 별칭을 오타 후보에 넣지 않는다. 모든 교정 정책의 `auto_apply`는 `false`다. 정답이 없으면 기대 ID는 빈 배열이다.
5. 현재 실행기는 `split: development`, 첫 페이지, `size` 10~100만 지원한다. 같은 의도의 원문·별칭·오타 변형은 같은 `intent_group`에 둔다. 최종셋을 추가하는 요청은 그룹 분리·동결 기록·실행기 지원을 함께 구현하는 별도 변경으로 다룬다.

## 버전과 기존 증거

의미가 바뀐 snapshot은 새 `dataset_id`로 구분한다. 상품 snapshot이 바뀌면 시나리오의 `catalog_id`와 `judgment_scope`를 연결하고, 판정이 바뀌면 질의 snapshot도 구분한다. 필드 구조 변경은 `schema_version`도 검토한다. 기존 평가 보고서가 참조한 snapshot은 Git 이력이나 별도 버전 파일로 다시 찾을 수 있어야 한다. 아직 커밋되지 않은 원본만 있다면 덮어쓰기 전에 별도 snapshot을 보존한다.

현재 테스트는 50개 상품, 30개 질의, 정답 있는 27개·오류 입력 6개·정상 입력 24개·정답 없는 3개와 dataset ID를 고정한다. **의도한 데이터 확대**에는 새 집계와 판정 근거로 이 고정값을 갱신할 수 있다. 검증 자체를 제거하거나 분모를 성공 응답 수로 바꾸지 않는다. 데이터 수에 의존하는 평가 테스트와 문서도 찾고, 영향이 있는 항목만 갱신한다.

`judgment_provenance`와 작업 기록에는 기계적 속성 일치 검사, AI가 작성한 판정, 실제 사람의 검토를 구분한다. 사람이 검토하지 않았으면 검토 대기 상태를 유지한다.

## 검증과 전달

Windows에서 `./gradlew.bat verifySeeds`를 실행한다. 테스트·실행기 코드도 바꾸면 `./gradlew.bat check`를 실행한다. macOS/Linux에서는 `./gradlew`를 사용한다.

결과에는 변경한 ID와 snapshot, 판정이 달라진 이유, 새 분모, 실행 명령·결과, 남은 사람 검토를 기록한다. 읽기 전용 감사에서는 데이터·버전을 바꾸지 않고 발견 사항을 보고한다. 실제 변경 작업은 [작업 기록](../../../docs/worklog.md)에 남긴다.
