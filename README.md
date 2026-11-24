# Gmail → 디스코드 알림 봇

Gmail 받은편지함이 바뀌면 Cloud Pub/Sub가 Spring Boot 서비스에 알립니다. 서비스는 Gmail 변경 내역을 읽어 새 메일의 제목, 본문 미리보기, 원문 링크를 지정된 디스코드 채널에 보냅니다. 마지막 처리 위치는 Cloud Storage 객체 한 개에 저장합니다. GitHub Actions는 테스트와 빌드를 실행합니다.

## 연결 정보 준비

1. Google Cloud 프로젝트에서 Gmail API를 켜고 OAuth 동의 화면을 구성합니다. 웹 애플리케이션 OAuth 클라이언트를 만든 뒤 승인된 리디렉션 URI에 `https://developers.google.com/oauthplayground`를 추가합니다.
2. [OAuth 2.0 Playground](https://developers.google.com/oauthplayground)의 **Use your own OAuth credentials**에 클라이언트 ID와 시크릿을 넣습니다. Gmail 계정으로 `https://www.googleapis.com/auth/gmail.readonly` 범위를 오프라인 액세스로 승인하고 인증 코드를 갱신 토큰으로 교환합니다. External 앱이 Testing 상태이면 이 범위의 갱신 토큰은 7일 후 만료됩니다. 장기 실행하려면 게시 상태를 In production으로 전환한 뒤 토큰을 다시 발급합니다. [Google OAuth 토큰 만료](https://developers.google.com/identity/protocols/oauth2#expiration)
3. [Discord Developer Portal](https://discord.com/developers/applications)에서 봇을 만들고 서버에 설치합니다. 대상 채널에서 봇에 **View Channel**, **Send Messages** 권한을 부여하고 채널 ID를 복사합니다.

## Cloud Run 배포

Gmail OAuth 클라이언트와 Pub/Sub 주제를 같은 Google Cloud 프로젝트에 만듭니다. 결제를 연결하고 Cloud Run, Artifact Registry, Pub/Sub, Cloud Scheduler, Cloud Storage, Secret Manager API를 활성화합니다.

1. Pub/Sub 주제 `gmail-inbox`를 만들고 `gmail-api-push@system.gserviceaccount.com`에 이 주제의 **Pub/Sub Publisher** 역할을 부여합니다. 조직에서 외부 서비스 계정을 제한하면 정책 예외가 필요할 수 있습니다. [Gmail 푸시 알림](https://developers.google.com/workspace/gmail/api/guides/push)
2. 비공개 Cloud Storage 버킷을 만들고 Cloud Run 실행용 서비스 계정에 이 버킷의 **Storage Object User** 역할을 부여합니다. 체크포인트 객체를 삭제하는 수명 주기 규칙을 적용하지 않습니다.
3. Artifact Registry의 Docker 저장소에 아래와 같이 이미지를 올립니다. `IMAGE`에는 저장소의 전체 이미지 경로와 태그를 넣습니다.

```sh
gcloud auth configure-docker REGION-docker.pkg.dev
IMAGE=REGION-docker.pkg.dev/PROJECT_ID/REPOSITORY/gmail-discord-bot:TAG
docker buildx build --platform linux/amd64 --push -t "$IMAGE" .
```

4. Cloud Run 서비스에 이 이미지와 실행용 서비스 계정을 지정합니다. **인증 필요**, **서비스당 최대 인스턴스 1**, **인스턴스당 요청 동시 실행 수 1**로 설정합니다. Cloud Run이 제공하는 `PORT`를 사용합니다. [Cloud Run 배포](https://docs.cloud.google.com/run/docs/deploying)
5. 아래 환경변수를 등록한 뒤 서비스를 배포합니다. 시크릿은 Secret Manager에 보관하고 실행용 서비스 계정에 해당 시크릿의 **Secret Manager Secret Accessor** 역할을 부여한 뒤 Cloud Run 환경변수에 연결합니다. [Cloud Run 시크릿 설정](https://docs.cloud.google.com/run/docs/configuring/services/secrets)

| 이름 | 값 |
| --- | --- |
| `GMAIL_ADDRESS` | OAuth를 승인한 Gmail 주소 |
| `GMAIL_CLIENT_ID` | OAuth 클라이언트 ID |
| `GMAIL_CLIENT_SECRET` | OAuth 클라이언트 시크릿 |
| `GMAIL_REFRESH_TOKEN` | Gmail OAuth 갱신 토큰 |
| `GMAIL_TOPIC` | `projects/PROJECT_ID/topics/gmail-inbox` |
| `DISCORD_BOT_TOKEN` | 디스코드 봇 토큰 |
| `DISCORD_CHANNEL_ID` | 대상 채널 ID |
| `CHECKPOINT_BUCKET` | 체크포인트를 저장할 버킷 이름 |
| `CHECKPOINT_OBJECT` | 선택 항목. 기본값은 `gmail-checkpoint.json` |

## 푸시와 초기 실행

1. Pub/Sub 푸시 호출용 서비스 계정에 이 Cloud Run 서비스의 **Cloud Run Invoker** 역할을 부여합니다. 주제의 푸시 구독을 만들고 엔드포인트를 `https://서비스주소/pubsub/gmail`로 설정합니다. **인증 사용**을 켜고 호출용 서비스 계정을 지정합니다. OIDC Audience에는 경로를 뺀 Cloud Run 기본 서비스 URL을 지정합니다. 구독의 확인 기한을 300초로 설정해 콜드 스타트와 여러 메일 처리 시간을 확보합니다. Pub/Sub 서비스 에이전트가 이 계정의 토큰을 발급할 수 있어야 합니다. [Cloud Run의 인증된 Pub/Sub 푸시](https://docs.cloud.google.com/run/docs/triggering/pubsub-triggers)
2. Cloud Scheduler 호출용 서비스 계정에 같은 서비스의 **Cloud Run Invoker** 역할을 부여합니다. 매일 실행되는 HTTP 작업을 만들고 **POST** `https://서비스주소/jobs/renew`를 호출하도록 설정합니다. 예시 일정은 UTC `15 0 * * *`입니다. OIDC 인증을 선택해 호출 계정을 지정하고 Audience에는 Cloud Run 기본 서비스 URL을 사용합니다. 실패 시 갱신을 다시 시도하도록 재시도 횟수를 설정합니다. [Cloud Scheduler로 Cloud Run 호출](https://docs.cloud.google.com/run/docs/triggering/using-scheduler)
3. Scheduler의 **지금 실행**으로 최초 Gmail 감시를 등록하고 현재 위치를 저장합니다. 성공 후 테스트 메일을 보내 디스코드에서 확인합니다. 기존 폴링 배포에서 옮길 때는 이전 실행이 끝난 뒤 최초 등록하고, 전환 중 도착한 메일을 별도로 확인합니다.

## 운영과 검증

- Pub/Sub가 지연되거나 누락하면 다음 푸시 또는 매일 감시를 갱신할 때 마지막 처리 위치부터 확인합니다. Gmail이 오래된 변경 위치를 더 이상 제공하지 않으면 최근 처리 시점 이후 받은편지함에 남은 메일을 조회합니다.
- 디스코드 전송에 실패하면 해당 작업을 다시 시도합니다. 디스코드가 메시지를 받은 직후 체크포인트 저장이나 요청이 실패하면 재전송 시 알림이 중복될 수 있습니다. 재배포할 때도 동일한 버킷과 객체 이름을 유지하세요.
- JDK 21로 `./gradlew test bootJar`를 실행합니다. `docker build .`로 컨테이너 빌드를 확인할 수 있습니다.
