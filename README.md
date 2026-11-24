# Gmail → 디스코드 알림 봇

GitHub Actions가 매시 7분과 37분에 Kotlin·Spring Boot 배치를 실행합니다. 배치는 Gmail 받은편지함의 새 메일마다 제목, Gmail 본문 미리보기, 원문 링크를 지정한 디스코드 채널에 보낸 뒤 종료합니다.

React 관리 화면에서는 Gmail 주소, Google OAuth 클라이언트 정보와 갱신 토큰, 디스코드 봇 토큰과 채널 ID, 알림 사용 여부를 설정할 수 있습니다. 배치는 다음 실행부터 PostgreSQL에 저장된 설정을 읽습니다.

## 초기 설정

1. Google Cloud 프로젝트에서 Gmail API를 활성화합니다. OAuth 동의 화면을 구성하고 **웹 애플리케이션** OAuth 클라이언트를 만든 뒤 승인된 리디렉션 URI에 `https://developers.google.com/oauthplayground`를 등록합니다.
2. [OAuth 2.0 Playground](https://developers.google.com/oauthplayground)의 설정에서 **Use your own OAuth credentials**를 켜고 만든 클라이언트 ID와 시크릿을 입력합니다. `https://www.googleapis.com/auth/gmail.readonly` 범위를 오프라인 액세스로 승인하고 **Exchange authorization code for tokens**를 눌러 갱신 토큰을 발급받습니다. Google OAuth 앱의 유형이 External이고 게시 상태가 Testing이면 이 범위의 갱신 토큰은 7일 후 만료됩니다. 장기 실행에는 게시 상태를 In production으로 전환하고 토큰을 다시 발급받으세요. [Google OAuth 문서](https://developers.google.com/identity/protocols/oauth2#expiration)
3. [Discord Developer Portal](https://discord.com/developers/applications)에서 애플리케이션과 봇을 만들고 서버에 설치합니다. 대상 채널에서 봇에 **View Channel**, **Send Messages** 권한을 부여합니다. 개발자 모드에서 채널 ID를 복사합니다.
4. GitHub 호스팅 러너에서 접속할 수 있는 영구 PostgreSQL 데이터베이스를 준비합니다. 첫 실행에 앱이 테이블을 생성할 수 있도록 해당 스키마의 생성 권한을 부여합니다.

## GitHub Actions 배포

GitHub 비공개 저장소의 **Settings → Secrets and variables → Actions**에서 다음 저장소 시크릿을 등록합니다.

| 이름 | 값 |
|---|---|
| `GMAIL_CLIENT_ID` | Google OAuth 클라이언트 ID |
| `GMAIL_CLIENT_SECRET` | Google OAuth 클라이언트 시크릿 |
| `GMAIL_REFRESH_TOKEN` | 연결할 Gmail 계정의 OAuth 갱신 토큰 |
| `DISCORD_BOT_TOKEN` | Discord 봇 토큰 |
| `DISCORD_CHANNEL_ID` | 알림을 받을 채널 ID |
| `BOT_CONFIG_KEY` | 관리 화면을 사용할 때 필요한 암호화 키. 관리 웹과 동일한 값을 설정 |
| `DATABASE_URL` | JDBC 주소. 예시 `jdbc:postgresql://host:5432/dbname?sslmode=verify-full` |
| `DATABASE_USERNAME` | DB 사용자 |
| `DATABASE_PASSWORD` | DB 비밀번호 |

Gmail·디스코드 시크릿 다섯 개는 DB에 관리 설정이 처음 저장되기 전 기본값입니다. 관리 화면에서 처음부터 직접 설정하는 경우 이 다섯 개는 생략할 수 있습니다.

기본 브랜치를 `main`으로 설정합니다. `main`에 푸시하면 Actions가 테스트와 JAR 빌드를 실행합니다. 예약 실행은 해당 커밋의 JAR을 사용하며 아티팩트가 없거나 만료되면 다시 빌드합니다.

GitHub 시크릿이나 관리 화면에 연결 정보를 저장한 뒤 **Actions → Gmail Discord bot → Run workflow**에서 `main`을 선택해 첫 실행을 시작합니다. 이때 Gmail의 현재 위치를 기준으로 저장합니다. 이후 테스트 메일을 하나 보내고 워크플로를 다시 실행해 알림 내용과 링크를 확인합니다.

## 관리 웹 배포

1. `openssl rand -base64 32`로 32바이트 설정 암호화 키를 생성합니다. 키를 비밀로 유지하고 GitHub Actions와 관리 웹 양쪽에 동일한 `BOT_CONFIG_KEY`를 등록합니다.
2. 관리 웹의 런타임 환경에 배치와 동일한 `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD`를 등록합니다. 관리자 로그인에 사용할 긴 무작위 비밀번호를 `BOT_ADMIN_PASSWORD`로 설정합니다. 첫 설정을 기존 값으로 채우려면 관리 웹에도 Gmail·디스코드 환경 변수 다섯 개를 넣을 수 있습니다.
3. Render를 사용한다면 저장소 루트의 `Dockerfile`을 사용하는 **Docker Web Service**를 만듭니다. 이미지의 기본 실행 모드는 `BOT_MODE=admin`입니다. 앱은 Render가 제공하는 `PORT`를 사용하며, 배포 후 제공되는 HTTPS 주소로 접속합니다. 관리 웹의 절전 여부와 무관하게 GitHub Actions가 폴링을 실행합니다. [Render Docker 배포](https://render.com/docs/docker) · [Render 포트와 HTTPS](https://render.com/docs/web-services#port-binding)
4. 관리 화면에 관리자 비밀번호로 로그인한 후 Gmail 주소, Gmail OAuth 정보, 알림 대상 채널과 봇 토큰을 저장합니다. 이미 등록된 비밀 입력란을 비워 두면 저장된 값이 유지됩니다. Gmail 주소에는 OAuth 갱신 토큰을 발급한 실제 계정 주소를 입력합니다.

저장된 Gmail 계정을 바꾸면 배치는 그 계정의 처리 위치를 사용합니다. 기존 대기 메시지는 해당 Gmail 계정에 남으며, 같은 계정에서 채널을 변경하면 아직 보내지 못한 메시지도 새 채널로 전송됩니다. 관리 설정은 단일 Gmail 계정과 디스코드 채널을 연결합니다.

## 운영과 복구

- PostgreSQL에 Gmail 조회 위치와 전송 대기열을 저장합니다. 실행이 빠지면 다음 성공한 실행에서 마지막 위치부터 이어서 조회합니다. 디스코드에 보내지 못한 메시지는 대기열을 통해 재시도합니다.
- Gmail 이력이 만료되면 최근 조회 시점 이후 받은편지함에 남아 있는 메일을 다시 확인합니다. 그사이 이동하거나 영구 삭제한 메일은 복구 대상에서 빠질 수 있습니다. 디스코드가 메시지를 받은 직후 상태 기록이 실패하면 재시도 중 알림이 중복될 수 있습니다.
- GitHub Actions 예약은 부하에 따라 지연되거나 생략될 수 있습니다. 비공개 저장소의 기본 Linux 러너 사용량은 작업 단위로 올림됩니다. 하루 48회 기준으로 30일간 각 실행이 1분 이내라면 약 1,440분이 필요합니다. 저장소의 별도 빌드와 같은 계정의 다른 워크플로도 포함되므로 실제 Usage를 확인하세요. [예약 실행](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#schedule) · [Actions 사용량](https://docs.github.com/en/billing/concepts/product-billing/github-actions)

## 로컬 테스트용 Docker Compose

1. `cp .env.example .env`로 로컬 설정 파일을 만듭니다.
2. `.env`에 테스트용 `BOT_ADMIN_PASSWORD`를 지정하고, `openssl rand -base64 32` 결과를 `BOT_CONFIG_KEY`에 넣습니다. 기존 로컬 설정을 다시 읽으려면 같은 키를 유지합니다.
3. `docker compose up --build -d`를 실행하고 브라우저에서 `http://127.0.0.1:8080`에 접속합니다. 포트가 이미 사용 중이면 `.env`의 `BOT_LOCAL_PORT`를 바꿉니다.

관리 화면은 로컬에서만 접속할 수 있고 PostgreSQL은 Compose 내부에서 연결됩니다. Gmail·디스코드 계정 없이 로그인과 설정 저장을 확인할 수 있습니다. `docker compose down`으로 종료해도 로컬 DB 볼륨은 유지됩니다. 테스트 데이터까지 초기화하려면 `docker compose down -v`를 사용합니다.

## 로컬 실행

JDK 21 환경에서 `./gradlew test bootJar`를 실행합니다. 위 시크릿과 동일한 이름의 환경 변수를 설정하고 `java -jar build/libs/<boot-jar>.jar`로 배치를 한 번 실행할 수 있습니다. 로컬 테스트에는 별도 DB와 채널을 연결하세요.

관리 웹을 로컬에서 실행하려면 JDK 21과 Node.js 22를 준비한 뒤 다음을 실행합니다. 위 관리 웹 환경 변수와 PostgreSQL 연결 설정을 먼저 내보냅니다.

```sh
npm --prefix admin-ui ci
npm --prefix admin-ui run build
./gradlew bootJar -PadminUi
BOT_MODE=admin java -jar build/libs/gmail-noti-discord-bot.jar
```

브라우저에서 `http://localhost:8080`에 접속합니다. `PORT`를 설정한 경우 해당 포트를 사용합니다.

PostgreSQL 통합 테스트는 별도의 테스트 DB를 만든 뒤 `TEST_DATABASE_URL`, `TEST_DATABASE_USERNAME`, `TEST_DATABASE_PASSWORD`를 설정하고 `./gradlew test`로 실행합니다. `main` 빌드에서는 Actions가 테스트 DB를 실행합니다.
