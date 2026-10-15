# Her servis için aynı Dockerfile: docker build --build-arg SERVICE=account-service .
# (docker-compose "apps" profili bunu kullanır.)
FROM maven:3.9-eclipse-temurin-21 AS build
ARG SERVICE
WORKDIR /src
COPY pom.xml .
COPY common/pom.xml common/
COPY account-service/pom.xml account-service/
COPY transfer-service/pom.xml transfer-service/
COPY notification-service/pom.xml notification-service/
COPY integration-tests/pom.xml integration-tests/
# Bağımlılıkları ayrı katmanda indir: kaynak değişince tekrar indirilmez.
RUN mvn -B -q -pl ${SERVICE} -am dependency:go-offline || true
COPY common/src common/src
COPY ${SERVICE}/src ${SERVICE}/src
RUN mvn -B -q -pl ${SERVICE} -am -DskipTests package

FROM eclipse-temurin:21-jre
ARG SERVICE
WORKDIR /app
COPY --from=build /src/${SERVICE}/target/${SERVICE}-*-exec.jar /app/app.jar
EXPOSE 8081 8082 8083
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
