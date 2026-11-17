# Etapa 1: compila o backend e gera o JAR executável.
FROM maven:3.9.12-eclipse-temurin-25 AS build
WORKDIR /build

COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline

COPY src ./src
RUN mvn -B -ntp clean package -DskipTests

# Etapa 2: executa apenas o artefato compilado.
FROM eclipse-temurin:25-jre-noble
WORKDIR /app

RUN mkdir -p /app/data/models
COPY --from=build /build/target/via-jar-1.0.0.jar /app/app.jar

ENV CACHE_FILE=/app/data/cache-semantico.json \
    HISTORICO_AVALIACOES_FILE=/app/data/historico-avaliacoes.json \
    LOCAL_EMBEDDING_CACHE_DIR=/app/data/models

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
