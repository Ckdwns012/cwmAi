# Git 저장소 설정 가이드

## 1. Git 저장소 초기화 (처음 한 번만)

```bash
# 현재 디렉토리에서 Git 초기화
git init

# 원격 저장소 추가 (GitHub, GitLab 등)
git remote add origin <your-repository-url>

# 예시:
# git remote add origin https://github.com/username/cwmAi.git
# 또는
# git remote add origin git@github.com:username/cwmAi.git
```

## 2. 첫 커밋

```bash
# 모든 파일 추가
git add .

# 첫 커밋
git commit -m "Initial commit: cwmAi project setup"

# 메인 브랜치를 main으로 설정 (GitHub 기본값)
git branch -M main

# 원격 저장소에 푸시
git push -u origin main
```

## 3. 협업자를 위한 설정

### 새로 프로젝트를 받는 사람

```bash
# 저장소 클론
git clone <repository-url>
cd cwmAi

# Java 17 설치 확인
java -version

# Gradle Wrapper 권한 부여 (Mac/Linux)
chmod +x gradlew

# 프로젝트 빌드
./gradlew build

# Ollama 서버 실행 (별도 터미널)
ollama serve

# 애플리케이션 실행
./gradlew bootRun
```

### 기존 프로젝트 업데이트 받기

```bash
# 최신 변경사항 가져오기
git pull origin main

# 의존성 업데이트가 필요한 경우
./gradlew clean build
```

## 4. 일상적인 작업 흐름

```bash
# 1. 최신 변경사항 가져오기
git pull origin main

# 2. 새 브랜치 생성 (기능 개발 시)
git checkout -b feature/새기능명

# 3. 작업 후 커밋
git add .
git commit -m "feat: 새 기능 추가"

# 4. 원격 저장소에 푸시
git push origin feature/새기능명

# 5. Pull Request 생성 (GitHub/GitLab 웹 인터페이스에서)
```

## 5. .gitignore 확인

다음 파일/폴더는 Git에 포함되지 않습니다:
- `build/` - 빌드 결과물
- `.gradle/` - Gradle 캐시
- `.idea/`, `*.iml` - IntelliJ 설정
- `uploads/` - 업로드된 파일 (`.gitkeep` 제외)
- `*.log` - 로그 파일

## 6. 주의사항

### 절대 커밋하지 말아야 할 것

- 개인 API 키나 비밀번호
- 환경별 설정 파일 (로컬 DB 비밀번호 등)
- 대용량 파일 (100MB 이상)
- 빌드 결과물

### 환경 변수 사용 권장

민감한 정보는 환경 변수나 별도 설정 파일로 관리:
```properties
# application-local.properties (gitignore에 추가)
spring.datasource.password=${DB_PASSWORD}
```

## 7. 충돌 해결

```bash
# 충돌 발생 시
git pull origin main

# 충돌 파일 수정 후
git add .
git commit -m "fix: merge conflict resolved"
git push origin <your-branch>
```

## 8. 유용한 Git 명령어

```bash
# 현재 상태 확인
git status

# 변경사항 확인
git diff

# 커밋 히스토리
git log --oneline

# 브랜치 목록
git branch -a

# 원격 저장소 정보
git remote -v
```

