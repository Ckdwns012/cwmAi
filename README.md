# CwmAI 프로젝트

오픈소스AI를 활용하여 내부규정을 내부망환경에서 검색하고 질의응답할 수 있는 직원용 사내 챗봇 프로젝트입니다.

## 📋 프로젝트 개요

- **프레임워크**: Spring Boot 3.5.7
- **Java 버전**: 17
- **빌드 도구**: Gradle
- **주요 기능**:
  - PDF 문서 업로드 및 텍스트 추출
  - 법령 문서 청킹 및 벡터 저장
  - AI 기반 법령 질의응답
  - JWT 기반 인증 시스템

## 🚀 시작하기

### 사전 요구사항

- **Java 17** 이상
- **Gradle** (또는 Gradle Wrapper 사용)
- **Ollama** 서버 (로컬 AI 모델 실행용)
  - 설치: https://ollama.ai/
  - 모델: `qwen3:4b-instruct-2507-q4_K_M` (또는 호환 가능한 모델)

### 1. 저장소 클론

```bash
git clone <repository-url>
cd cwmAi
```

### 2. Ollama 서버 실행

```bash
# Ollama 서버 시작
ollama serve

# 필요한 모델 다운로드 (다른 터미널에서)
ollama pull qwen3:4b-instruct-2507-q4_K_M
```

### 3. 프로젝트 빌드 및 실행

```bash
# Gradle Wrapper를 사용한 빌드
./gradlew build

# 애플리케이션 실행
./gradlew bootRun

# 또는 JAR 파일로 실행
./gradlew bootJar
java -jar build/libs/cwmAi-0.0.1-SNAPSHOT.jar
```

### 4. 애플리케이션 접속

- **URL**: http://localhost:9090
- **기본 관리자 계정**: 
  - ID: `admin`
  - Password: `admin`

## 📁 프로젝트 구조

```
cwmAi/
├── src/
│   ├── main/
│   │   ├── java/com/example/cwmAi/
│   │   │   ├── Config/          # 설정 클래스
│   │   │   ├── Controller/      # REST/Web 컨트롤러
│   │   │   ├── Service/         # 비즈니스 로직
│   │   │   ├── Repository/     # 데이터 접근 계층
│   │   │   ├── dto/            # 데이터 전송 객체
│   │   │   └── Util/           # 유틸리티 클래스
│   │   └── resources/
│   │       ├── application.properties
│   │       ├── mapper/         # MyBatis 매퍼 (현재 미사용)
│   │       ├── static/        # 정적 리소스
│   │       └── templates/     # Thymeleaf 템플릿
│   └── test/                   # 테스트 코드
├── uploads/                     # 업로드된 문서 저장소
├── build.gradle
└── settings.gradle
```

## ⚙️ 설정

### application.properties

주요 설정은 `src/main/resources/application.properties`에서 확인할 수 있습니다:

```properties
spring.application.name=cwmAi
server.port=9090
```

### Ollama 설정

애플리케이션은 기본적으로 `http://localhost:11434/api`에서 Ollama 서버를 찾습니다.

모델명은 `aiService.java`에서 설정할 수 있습니다:
```java
private static final String MODEL_NAME = "qwen3:4b-instruct-2507-q4_K_M";
```

## 🔧 개발 환경 설정

### IntelliJ IDEA

1. `File` → `Open` → 프로젝트 폴더 선택
2. Gradle 프로젝트로 자동 인식
3. `File` → `Project Structure` → `Project SDK`를 Java 17로 설정
4. `File` → `Settings` → `Build, Execution, Deployment` → `Build Tools` → `Gradle`
   - `Build and run using`: Gradle
   - `Run tests using`: Gradle

### Eclipse

1. `File` → `Import` → `Gradle` → `Existing Gradle Project`
2. 프로젝트 폴더 선택
3. Java 17로 설정 확인

### VS Code

1. Java Extension Pack 설치
2. 프로젝트 폴더 열기
3. `.vscode/settings.json`에 Java 17 경로 설정

## 📝 주요 기능

### 1. 문서 업로드
- PDF 파일을 카테고리별로 업로드
- 자동으로 텍스트 추출 및 청킹
- 법령 조항 단위로 분할 저장

### 2. AI 질의응답
- 사용자 질문에 대한 관련 조항 추천
- 추천된 조항을 바탕으로 최종 답변 생성
- SSE(Server-Sent Events)를 통한 실시간 응답

### 3. 인증 시스템
- JWT 기반 인증
- 쿠키를 통한 세션 관리
- 관리자 권한 기능

## 🛠️ 빌드 및 배포

### 개발 환경 빌드

```bash
./gradlew clean build
```

### 프로덕션 빌드

```bash
./gradlew clean bootJar
```

생성된 JAR 파일: `build/libs/cwmAi-0.0.1-SNAPSHOT.jar`

### JAR 실행

```bash
java -jar build/libs/cwmAi-0.0.1-SNAPSHOT.jar
```

## 🐛 문제 해결

### Ollama 연결 오류

- Ollama 서버가 실행 중인지 확인: `ollama serve`
- 포트 11434가 사용 가능한지 확인
- 모델이 설치되어 있는지 확인: `ollama list`

### 빌드 오류

```bash
# Gradle 캐시 정리
./gradlew clean

# Gradle Wrapper 재다운로드
rm -rf .gradle
./gradlew wrapper --gradle-version=8.14.3
```

### 포트 충돌

`application.properties`에서 포트 변경:
```properties
server.port=9091
```

## 📦 의존성

주요 의존성:
- Spring Boot Web
- Spring Boot Thymeleaf
- Spring WebFlux (비동기 AI 요청)
- Apache PDFBox (PDF 텍스트 추출)
- LangChain4j (AI 통합)
- JWT (인증)

전체 의존성 목록은 `build.gradle` 참조

## 👥 협업 가이드

### 브랜치 전략

- `main`: 프로덕션 배포용
- `develop`: 개발 통합 브랜치
- `feature/*`: 기능 개발 브랜치

### 커밋 메시지 규칙

```
feat: 새로운 기능 추가
fix: 버그 수정
docs: 문서 수정
style: 코드 포맷팅
refactor: 코드 리팩토링
test: 테스트 추가
chore: 빌드 설정 변경
```

### Pull Request

1. 기능 개발 완료 후 `develop` 브랜치로 PR 생성
2. 코드 리뷰 후 머지
3. 충돌 발생 시 로컬에서 해결 후 푸시

## 📄 라이선스

이 프로젝트는 내부 사용을 위한 프로젝트입니다.

## 📞 문의

프로젝트 관련 문의사항이 있으면 이슈를 생성해주세요.

