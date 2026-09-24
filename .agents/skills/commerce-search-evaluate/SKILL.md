---
name: commerce-search-evaluate
description: commerce-search-lab의 검색 평가를 실행하거나 manifest.json·runs.jsonl·summary.json을 해석하고 baseline/query_v1/ranking_v1을 비교할 때 사용한다. 평가 도구 검증과 실제 검색 품질을 구분하고 재현 조건·분모·실패·제안 선택 결과를 확인한다.
---

# 검색 평가 실행과 해석

저장소의 [평가 계약](../../../docs/evaluation-contract.md)을 기준으로 실행한다. [작업 기록](../../../docs/worklog.md)에서 검색 API 구현 상태를 확인하고, 환경·검증 명령은 [하네스 안내](../../../docs/harness.md)를 따른다.

## 실행 경로 선택

- 기존 결과 해석이면 같은 실행 폴더의 `manifest.json`, `runs.jsonl`, `summary.json`을 함께 읽는다. 요약 파일 하나만으로 실제 서비스 측정인지, 비교 조건이 같은지 확정하지 않는다.
- 실제 API가 준비되어 있으면 서비스가 구현한 모드만 `evaluateSearch`로 실행한다. 실행기는 서버를 시작하지 않는다.
- API가 없다면 평가 도구 테스트와 CLI 진입점을 검증한다. 이는 검색 품질 측정이 아니며, 테스트 서버가 정답을 반환한 수치를 baseline으로 기록하지 않는다. 연결 실패 보고서는 실패 처리 검증 자료다.

저장소 루트, JDK 21, Windows PowerShell 기준:

```powershell
# 도구 검증: API가 없어도 실행 가능
./gradlew.bat test --tests 'org.letter33.commercesearchlab.evaluation.SearchEvaluatorTest'
./gradlew.bat evaluateSearch --args="--help"

# 실제 API의 baseline
./gradlew.bat evaluateSearch --args="--base-url http://localhost:8080 --modes baseline"

# 세 모드가 구현된 이후: 같은 인덱스에서 비교, 실제 top1 제안 선택 포함
./gradlew.bat evaluateSearch --args="--base-url http://localhost:8080 --modes baseline,query_v1,ranking_v1 --accept-suggestions"
```

macOS/Linux에서는 `./gradlew`를 사용한다. `--base-url`에는 인증정보·경로 없는 HTTP(S) origin을 준다. 기본 데이터가 아닌 지정된 개발 데이터를 평가할 때는 `--catalog`, `--scenarios`를 지정한다. 워밍업·시간 제한·별도 출력 폴더는 계약의 CLI 옵션을 사용한다. 기본 출력은 UTC 시각별 새 폴더이며 기존 출력 폴더를 재사용하지 않는다.

## 비교 조건 확보

실행 시 명령과 서비스 코드 revision·미커밋 변경 유무, 실제 서비스인지 테스트 서버인지, 설정 파일·색인 생성 기록의 위치를 결과와 함께 남긴다. 현재 manifest에는 코드 revision이나 응답 생성 주체가 자동 기록되지 않는다.

실행 간 또는 모드 간 비교 전에 다음 근거를 확인한다.

- `catalog.dataset_id`, `catalog.sha256`, `scenarios.dataset_id`, `scenarios.sha256`: 같은 데이터와 판정 snapshot인지 확인한다. ID가 같아도 해시가 다르면 같은 snapshot으로 취급하지 않는다.
- `index_version`: 한 실행의 모든 모드에서 고정되어야 한다. 실제 색인 생성 기록이 카탈로그 해시와 연결되는지 확인한다.
- `config_versions_by_mode`: 각 모드 실행 중 고정되어야 하며 모드별 값은 달라도 된다. 불변 설정 파일을 대조해 모드별 의도한 질의·랭킹·별칭 변경과 공통 분석기·매핑 조건을 구분한다. 분석기 변경은 별도 실험으로 다룬다.
- 요청 필터, `warmup_passes_per_mode`, `measured_passes_per_mode`, `concurrency`, `timeout_ms`, `accept_suggestions`, 실행 환경: 비교 대상의 차이를 확인한다. 특히 지연시간 비교는 같은 기기·서비스 조건의 근거가 필요하다.

버전 문자열만 같아도 동일 설정·실제 색인 내용이 입증되는 것은 아니다. 두 실행의 코드·설정·데이터 출처가 부족하면 관찰된 수치는 보고하되 개선 원인을 단정하거나 우승 모드를 판정하지 않는다. HTTP 하네스로 검증할 수 없는 분석기 동작·실제 색인 내용은 서비스 통합 검증 근거를 찾는다.

## 결과 읽기

먼저 `manifest.status`, `summary.valid`, `summary.modes.<mode>.valid`, `failures_including_warmup`을 확인한다. 정상 완료는 `status: complete`다. 미완료·실패 실행은 품질 개선이나 성능 비교의 근거로 채택하지 않는다. 오류가 있는 모드의 품질 지표는 `null`이며, 다른 정상 모드의 값이 남아 있어도 전체 비교가 완료된 것은 아니다.

`runs.jsonl`의 `status: failed`, `error_type`, `error_message`, `http_status`로 원인을 확인한다. 워밍업 오류도 모드를 무효화한다. 실패 요청을 0건 성공으로 바꾸거나 성공 요청만 골라 품질 점수를 다시 계산하지 않는다. CLI 종료 코드는 완료 0, 요청·응답 실패 1, 입력·파일 오류 2이며 Gradle에서는 비정상 종료가 빌드 실패로 보인다. 입력 오류는 보고서 생성 전에 종료될 수 있다. 원인 해결 후 새 폴더로 재실행한다.

품질과 분모는 `summary.modes.<mode>` 아래 단계별 필드에서 읽는다.

| 단계 / 지표 | 함께 확인할 값과 해석 |
|---|---|
| `original.hit_at_5`, `original.mrr_at_10` | `answerable_queries`가 분모. 정답 없는 질의는 제외한다. |
| `original.no_answer_unexpected_results` | `no_answer_queries` 중 상품을 반환한 건수. 원문 검색 점수와 별도 보고한다. |
| `original.suggestion_accuracy` | `queries_with_suggestions`가 분모. top1 정확도이며 제안이 없으면 `null` = N/A다. |
| `original.suggestion_coverage` | `correction_queries`가 분모. 정확도와 함께 보고한다. |
| `original.normal_false_suggestion_rate` | `normal_queries`가 분모. 별칭·브랜드·SKU 정상 입력도 포함한다. |
| `suggestion_accepted` | `enabled`, `selected_queries`, `eligible_correction_queries`, `selection_coverage`, `queries_without_suggestion`을 함께 보고한다. Hit/MRR 분모는 이 단계의 `answerable_queries`다. |
| 각 단계의 `client_p95_ms` | `latency_sample_count`와 함께 읽는다. 워밍업 제외, 성공 요청의 직렬 1회 측정으로 부하 성능이 아니다. 실패 실행의 p95는 성능 비교에 쓰지 않는다. |

현재 기본 개발셋의 원문 분모는 정답 있음 27, 정답 없음 3, 교정 대상 6, 정상 입력 24다. 실제 보고서와 입력 snapshot을 확인하고 이 수치를 다른 데이터에 고정 적용하지 않는다. `null`을 0으로 표시하지 말고 실패·미실행·분모 없음 중 이유를 구분한다.

`suggestion_accepted`는 모드가 아닌 별도 단계다. 실제 서버의 top1을 그대로 선택하며, 틀린 제안도 기대 정답으로 대체하지 않는다. `scenario_query`, `request_query`, `parent_request_id`로 원문과 선택 요청을 연결한다. 선택 후 점수를 `original`에 섞지 않는다. 선택 대상이 모드마다 다를 수 있으므로 선택 후 점수 차이에는 대상 수·커버리지·시나리오 차이를 함께 설명한다. 선택 후 점수는 사용자 선택률이나 전체 교정 대상의 회복률이 아니다.

## 데이터와 결과 보존

현재 실행기는 `intent_group`과 `split: development`를 요구한다. 최종셋을 개발셋으로 이름만 바꿔 실행하지 않는다. 최종 평가 요청은 split 선택·그룹 격리·판정 동결 계약을 구현하는 별도 작업으로 다룬다. 점수를 높이려는 목적으로 판정·분모를 수정하지 않으며, 요청된 데이터 변경은 snapshot과 판정 근거를 함께 갱신한다.

결과 설명에는 측정 대상·모드·유효성, 원문 지표와 분모, 제안 지표와 커버리지, 대표 실패 시나리오, 비교 가능 범위, 세 산출물 경로를 포함한다. 가상 개발셋 결과는 실제 커머스 성능이나 최종 평가 성적으로 일반화하지 않는다. 보존할 실행은 `clean` 전에 세 파일을 함께 별도 결과 폴더로 옮기거나 복사하고 실행 근거를 함께 남긴다. 수행한 검증과 한계는 작업 기록에 반영한다.
