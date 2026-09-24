# 커밋·이슈·PR 템플릿 사용법

실제 작업은 **작업 ID 하나 → 이슈 하나 → PR 최대 하나 → 실제 머지 후 이슈 종료** 순서로 진행한다. 여러 작은 커밋과 리뷰 수정은 같은 PR에 반영한다. 상세 절차와 권한 범위는 [Git 전달 스킬](../.agents/skills/commerce-search-git-delivery/SKILL.md)을 따른다.

| 용도 | 템플릿 | 작성할 내용 |
|---|---|---|
| 커밋 메시지 | [.gitmessage.txt](../.gitmessage.txt) | Conventional Commits 제목, 필요한 설명, 실제 이슈의 `Refs #번호` |
| 작업 이슈 | [task.md](../.github/ISSUE_TEMPLATE/task.md) | 작업 ID, 목적, 범위, 완료 기준, 착수 전 검증 계획 |
| PR 본문 | [pull_request_template.md](../.github/pull_request_template.md) | 해결한 문제, 실제 변경·검증·제약, 실제 이슈의 `Closes #번호` |

자리표시자를 실제 값으로 바꾸고 안내 주석과 불필요한 항목을 제거한다. 미실행 상태를 통과로 바꾸거나 체크박스를 미리 선택하지 않는다. 환경변수 값·토큰·개인키·민감한 원본 로그를 메시지, 본문, 첨부에 복사하지 않는다.

## 커밋 작성

커밋을 요청받고 변경 범위·staged 내용·검증을 확인한 뒤, 저장소 루트에서 다음 명령으로 편집기를 연다. 이 명령은 작성 완료 후 실제 커밋을 만든다.

```powershell
git commit --cleanup=strip --template .gitmessage.txt
```

템플릿의 안내 주석 위에 실제 메시지를 작성한다. 제목은 `type(scope): 한국어 요약`, 마지막 이슈 참조는 `Refs #실제이슈번호`로 적는다. scope는 적절한 단일 영역이 없으면 생략한다. 호환성을 깨면 제목의 `!`와 영향·전환 방법을 담은 `BREAKING CHANGE:` 후터를 함께 쓴다. 커밋에는 이슈 종료 키워드를 쓰지 않는다.

`--cleanup=strip`은 `#`으로 시작하는 안내 줄을 제거한다. 저장소의 `core.commentChar`/`core.commentString`을 별도로 바꾼 환경에서는 주석 문자를 먼저 확인한다. 템플릿만 그대로 저장하면 실제 메시지가 비어 있으므로 커밋하지 않는다. 템플릿은 [Git의 `--template`과 cleanup 옵션](https://git-scm.com/docs/git-commit)을 사용하며, Git 설정 변경 없이 위 명령으로 선택할 수 있다.

자동화된 작성에서는 실제 메시지만 담은 UTF-8 임시 파일을 준비하고 `git commit --file <메시지 파일>`로 전달한다. 이때 원본 템플릿을 그대로 넘기지 않는다. `--file`의 기본 정리는 안내 주석을 남길 수 있으므로 주석을 제거한 완성본을 사용한다. 임시 파일은 stage하지 않는다.

## 이슈 작성

착수 전에 같은 작업의 이슈와 모든 상태의 PR을 조회한다. 기존 이슈가 있으면 재사용한다. 새 이슈가 필요하면 GitHub의 새 이슈 화면에서 **작업 단위 구현**을 선택한다. 제목의 작업 ID와 결과를 실제 값으로 바꾸고, 구현 전에 완료 기준과 검증 계획을 작성한다. 실제 결과는 수행 후 갱신한다.

GitHub CLI나 API로 작성할 때는 `task.md`의 첫 두 `---` 사이 YAML 메타데이터(`name`, `about`, `title`)를 제외한 본문을 UTF-8 임시 파일에 복사한다. 제목은 별도 인수로 전달하고 완성한 본문을 `--body-file` 또는 구조화된 도구 인수로 전달한다. 원본 파일을 그대로 이슈 본문으로 보내지 않는다.

## PR 작성

생성 전에 open/draft/closed/merged 상태 전체에서 같은 작업 이슈와 브랜치의 PR을 확인한다. 기존 PR이 있으면 같은 PR을 갱신하거나 재개한다. 재개할 수 없다고 대체 PR을 만들지 않는다.

PR 템플릿에 실제 변경 결과·실행 명령·관찰한 결과·남은 제약을 작성한다. 종료 연결은 실제 작업 이슈 하나의 `Closes #번호`만 사용한다. 다른 이슈는 일반 링크로 참조한다. CLI/API에서도 완성한 본문을 UTF-8 임시 파일과 `--body-file` 또는 구조화된 인수로 전달할 수 있다.

실제 머지 후 이슈 종료 상태를 다시 확인한다. 기본 브랜치 대상 여부와 저장소 설정에 따라 자동 종료되지 않을 수 있으므로, 실제 머지와 완료 기준을 확인한 뒤 열려 있는 이슈를 종료한다. 자세한 조건은 [GitHub 이슈 연결 문서](https://docs.github.com/en/issues/tracking-your-work-with-issues/using-issues/linking-a-pull-request-to-an-issue)와 [자동 종료 설정](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/managing-repository-settings/managing-auto-closing-issues)을 따른다.

## 적용 시점과 범위

GitHub의 이슈 선택 화면과 새 PR 본문에 자동으로 나타나려면 템플릿 파일이 저장소 **기본 브랜치에 반영**되어야 한다. 로컬 파일 작성만으로 GitHub에 적용되지는 않는다. 반영 전에는 파일에서 완성한 내용을 복사해 사용할 수 있다. [GitHub 템플릿 안내](https://docs.github.com/en/communities/using-templates-to-encourage-useful-issues-and-pull-requests/about-issue-and-pull-request-templates)

템플릿은 작성 양식이다. 이슈당 PR 개수나 커밋 형식을 서버에서 강제하는 자동화는 포함하지 않는다. 템플릿 작성 요청만으로 Git 설정·hook을 변경하거나 실제 이슈/PR 생성·커밋·푸시·머지를 실행하지 않는다.
