## Imagem do serviço RESERVATION (issue #33). Contexto de build = Projeto/Backend/ (raiz do multi-módulo Maven):
## `docker build -f reservation/Dockerfile Projeto/Backend` ou o `build:` do compose. Porta 8082 (só na rede gerenciador-net).
## Estágio de build: compila só o reservation e as libs que ele usa (shared, reservation-client; -pl reservation -am) com Java 21 e Maven 3.9
FROM maven:3.9.7-eclipse-temurin-21-jammy AS build
WORKDIR /build
COPY pom.xml checkstyle.xml checkstyle-suppressions.xml ./
COPY shared ./shared
COPY reservation-client ./reservation-client
COPY reservation ./reservation
# O agregador lista todos os módulos: o Maven exige o pom de cada um, mas não o código (aqui só o pom dos outros serviços).
COPY monolito/pom.xml ./monolito/pom.xml
COPY notification/pom.xml ./notification/pom.xml
COPY catalog-client/pom.xml ./catalog-client/pom.xml
COPY identity-client/pom.xml ./identity-client/pom.xml
COPY catalog/pom.xml ./catalog/pom.xml
COPY identity/pom.xml ./identity/pom.xml
RUN mvn -B -DskipTests -pl reservation -am clean package

# Estágio runtime: imagem pequena apenas para executar o jar (Java 25)
FROM eclipse-temurin:25-jre-jammy
WORKDIR /app
COPY --from=build /build/reservation/target/*.jar ./app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
