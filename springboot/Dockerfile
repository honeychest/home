# Purpose: Spring Boot 애플리케이션의 Docker 이미지 생성을 위한 레시피 (Java 17, 메모리 최적화 반영)

# 1. Base 이미지 설정 (프리티어 환경에 가벼운 alpine 기반 JDK 17 사용)
FROM azul/zulu-openjdk-alpine:17

# 2. 작업 디렉토리 설정
WORKDIR /app

# CI 빌드 커밋을 실행 이미지에 기록해 배포 대상과 실행 파일을 대조한다.
ARG BUILD_REVISION=unknown
LABEL org.opencontainers.image.revision="${BUILD_REVISION}"

# 3. 빌드된 JAR 파일을 컨테이너 내부로 복사
# 빌드 시 생성되는 파일명인 chsproject.jar를 사용합니다.
COPY build/libs/chsproject.jar app.jar

# 4. 메모리 제한 및 최적화 옵션 설정
# 눈생님이 사용하시던 옵션을 그대로 반영합니다.
ENV JAVA_OPTS="-Xms128m -Xmx384m -XX:+UseSerialGC"

# 5. 실행 명령어
# Jasypt 비밀번호는 실행 시 docker-compose에서 환경변수로 넘겨받아 주입합니다.
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
