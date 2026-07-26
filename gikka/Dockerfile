# Purpose: gikka(기까) 앱 이미지 — springboot/Dockerfile 과 같은 구성(Java 17 alpine).
# 다른 점은 jar 이름뿐이다. 힙은 docker-compose 의 JAVA_OPTS 가 덮어쓴다.

FROM azul/zulu-openjdk-alpine:17

WORKDIR /app

COPY build/libs/gikka.jar app.jar

# compose 에서 JAVA_OPTS 를 주지 않고 이미지를 단독 실행할 때의 기본값.
ENV JAVA_OPTS="-Xms128m -Xmx512m -XX:+UseSerialGC"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
