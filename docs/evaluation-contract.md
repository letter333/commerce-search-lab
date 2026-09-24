# 검색 평가 계약 v0.1

이 문서는 M1에서 구현할 검색 API와 평가 실행기가 공유하는 계약이다. 기획에 명시되지 않았던 필터의 HTTP 표현과 제안 형식을 여기서 구체화한다. 실제 검색 API는 아직 없다.

## 실행

저장소 루트에서 JDK 21로 실행한다. `evaluateSearch`는 테스트 클래스를 빌드하고 HTTP로 검색 API를 호출한다. API 서버는 별도로 실행해야 한다.

```powershell
# M1: baseline 측정
./gradlew.bat evaluateSearch --args="--base-url http://localhost:8080 --modes baseline"

# M2 이후: 같은 인덱스에서 세 모드를 비교
./gradlew.bat evaluateSearch --args="--base-url http://localhost:8080 --modes baseline,query_v1,ranking_v1 --accept-suggestions"

# 조건과 결과 위치 명시
./gradlew.bat evaluateSearch --args="--base-url http://localhost:8080 --modes baseline --warmup 1 --timeout-ms 5000 --output build/evaluations/baseline-run-01"
```

macOS/Linux에서는 `./gradlew`를 사용한다. 구현하지 않은 모드는 요청하지 않는다. `suggestion_accepted`는 모드 이름이 아니라 별도 평가 단계이며 `--accept-suggestions`로 실행한다.

| 옵션 | 기본값 / 의미 |
|---|---|
| `--base-url` | 필수. 경로·인증정보 없는 HTTP(S) origin. `/api/search`는 실행기가 붙인다. |
| `--catalog` | `docs/catalog-50.json` |
| `--scenarios` | `docs/search-scenarios-30.json` |
| `--modes` | `baseline`. 쉼표로 나열한 모드별로 실행. |
| `--warmup` | `1`. 각 모드의 전체 시나리오를 측정 전에 실행할 횟수. `0`이면 생략. |
| `--timeout-ms` | `5000`. 개별 HTTP 요청 제한 시간. |
| `--accept-suggestions` | 기본 비활성. 오타·한영 오류 시나리오에서 실제 첫 번째 제안으로 재검색. |
| `--output` | `build/evaluations/<UTC 실행 시각>/`. 결과는 별도 폴더로 보존한다. |

## HTTP 요청

`GET /api/search`에 UTF-8 URL 인코딩한 query parameter를 사용한다. 원문의 앞뒤·연속 공백도 보존한다.

| 파라미터 | 형태 |
|---|---|
| `q` | 원문 검색어 또는 명시적으로 선택한 제안 |
| `mode` | `baseline`, `query_v1`, `ranking_v1` 중 구현된 모드 |
| `page`, `size` | 시나리오의 값. 현재 `1`, `10`. |
| `price_min`, `price_max` | 원화 정수, 경계값 포함. `request.filters`에서 평탄화. |
| `color`, `category` | 정확히 일치하는 필터. `request.filters`에서 평탄화. |

예: `GET /api/search?q=후드티&mode=baseline&page=1&size=10&price_max=40000` (실제 전송 시 한글 인코딩).

API는 요청 검증 오류를 4xx로, 검색 엔진 오류·시간 초과를 503/504로 구분한다. 평가 실행기에 전달한 유효한 시나리오에서 4xx/5xx가 나면 0건 응답으로 간주하지 않는다.

## HTTP 응답

HTTP 200의 JSON 객체는 아래 필드를 포함한다. 다음 값은 **형식 설명용 예시이며 실행 결과가 아니다**.

```json
{
  "request_id": "example-only-request",
  "original_query": "맨투먼",
  "executed_query": "맨투먼",
  "suggestions": ["맨투맨"],
  "items": [],
  "total": 0,
  "timing_ms": 1.0,
  "config_version": "baseline-config-v1",
  "index_version": "catalog-v0.1-index-v1"
}
```

- `original_query`: 해당 HTTP 요청의 `q`를 그대로 보존한다. 제안 선택 요청의 원문은 선택한 제안이며 최초 입력은 실행 기록으로 연결한다.
- `executed_query`: 정규화·별칭 처리를 반영해 실제로 실행한 질의. 오타 제안을 조용히 자동 적용하지 않는다. `suggest_only` 원문 요청은 공백·대소문자 정규화 외 어휘 변경을 하네스가 거절한다. 그 밖의 정규화·확장 의도는 향후 API 테스트에서 별도로 확인한다.
- `suggestions`: 우선순위대로 정렬한 오타 후보 문자열 배열. 별칭 확장을 넣지 않는다. 후보가 없으면 `[]`.
- `items`: 순위대로 정렬한 상품 객체 배열. 각 객체에 카탈로그의 `id`가 있어야 한다. 반환 ID와 snapshot을 대조해 필터 조건을 검사한다. 추가 상품 필드는 허용한다.
- `total`: 일치 상품 수인 0 이상의 정수. Elasticsearch의 `{value, relation}` 객체를 그대로 반환하지 않는다.
- `timing_ms`: 서버가 측정한 0 이상의 처리 시간. 클라이언트가 측정한 HTTP 경과 시간과 구별한다.
- `config_version`: 같은 모드 실행 중 고정된 질의·랭킹 설정 버전.
- `index_version`: 모든 비교 모드에서 고정된 실제 인덱스 버전. alias 이름만으로 버전을 대신하지 않는다.

M1에서는 설정 버전이 가리키는 분석기·매핑·가중치·별칭 사전을 불변 파일로 보존하고, 인덱스 생성 기록에 카탈로그 SHA-256을 연결한다. 응답 버전 문자열이 같다는 사실만으로 동일한 분석기나 데이터가 입증되지는 않는다.

하네스는 응답 구조, 원문 보존, 상품 ID 유효성·중복, 필터 준수, 버전의 일관성을 확인한다. 한글 분석기, 실제 색인 내용 전체, `executed_query`의 의미적 타당성을 HTTP 응답만으로 입증하지는 못한다. 이 항목은 M1/M2 서비스 통합 테스트로 검증한다.

## 계산 기준

| 지표 | 대상과 계산 |
|---|---|
| Hit@5 | 정답 있는 원문 질의별 상위 5개에 적합 상품이 있으면 1. 현재 27개 평균. |
| MRR@10 | 정답 있는 원문 질의별 첫 적합 상품의 순위 역수. 10위 내 없으면 0. 현재 27개 평균. |
| 제안 정확도 | 오타·한영 오류 중 제안을 반환한 질의에서 top1이 `acceptable_suggestions`에 속한 비율. 제안 0개이면 JSON `null`(N/A). |
| 제안 커버리지 | 오타·한영 오류 질의 6개 중 제안이 있는 비율. |
| 정상 입력 오제안율 | 교정이 불필요한 정상 질의 24개 중 오타 제안을 반환한 비율. 별칭·SKU 대소문자 사례도 정상 입력. |
| 정답 없음 실패 | 정답 없는 3개에서 상품을 반환한 건수. 원문 Hit/MRR 분모에서는 제외. 필터 위반은 별도 계약 실패. |
| p95 HTTP 시간 | 워밍업을 제외한 클라이언트 요청 시간의 nearest-rank 95백분위. 원문·제안 선택 단계별로 구분. |

원문 검색은 오타 시나리오도 포함해 의도에 맞는 상품을 찾았는지 측정한다. 제안 선택 후 점수는 별도 결과다. `--accept-suggestions`는 실제 서버가 내놓은 top1만 선택하며 정답 목록으로 후보를 만들어 보내지 않는다. 틀린 제안도 그 후보 그대로 실행한다. 제안이 없는 시나리오는 선택 후 평가 대상이 아니므로, 선택 후 점수에는 평가된 질의 수와 전체 오류 질의 대비 커버리지를 함께 표시한다. 이 수치는 실제 사용자의 선택률이나 전체 오류 질의의 회복률이 아니다.

동시성은 `1`, 각 시나리오의 본 측정은 1회다. p95는 소규모 기능 평가의 진단값이며 부하 시험이 아니다. 같은 기기·데이터·워밍업·모드 조건에서만 비교하고, M3/M4의 별도 부하 시험으로 일반화한다.

## 실행 산출물과 실패

- `manifest.json`: 입력 파일 식별자·SHA-256, 모드, 실행 시각, 환경·Java, 워밍업·동시성, 설정·인덱스 버전 등 재현 조건.
- `runs.jsonl`: 시나리오·모드·단계별 요청과 응답·시간·오류. 최초 원문과 제안 선택 요청을 구분한다.
- `summary.json`: 모드별 원문 검색·교정·제안 선택 지표와 분모, 실행 유효성·실패 정보.

통신 실패, 비정상 HTTP 상태, 잘못된 JSON, 필터 위반, 버전 변경은 실패로 기록한다. 워밍업을 포함해 오류가 발생한 모드의 품질 지표는 `null`, 전체 실행은 `valid: false`가 된다. 시간 지표는 성공한 측정 표본 수와 함께 표시하므로 오류가 있는 실행의 지연시간을 성능 비교에 사용하지 않는다.

프로세스 종료 코드는 완료 `0`, 요청·응답 실패 `1`, 입력·파일 오류 `2`다. Gradle은 Java 실행의 비정상 종료를 빌드 실패로 전달한다. 입력 검증 실패는 HTTP 실행 전 종료하므로 평가 보고서가 없을 수 있다. 기존 출력 폴더는 덮어쓰지 않는다. 실패 보고서를 확인하고 원인을 해결한 뒤 새 결과 폴더로 다시 실행한다.

카탈로그의 정답을 반환하는 테스트 서버는 지표 계산 검증에만 사용한다. 해당 결과를 실제 검색 baseline이나 품질 보고서로 사용하지 않는다. 현재 실행기는 `intent_group`이 있고 `split: development`인 시나리오만 허용한다. 최종셋 지원은 M3에서 split 선택·의도 그룹 격리·판정 동결 계약과 함께 확장한다.
