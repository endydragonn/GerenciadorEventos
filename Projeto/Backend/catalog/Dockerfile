## Imagem do serviço CATALOG (issue #35). Contexto de build = Projeto/Backend/ (raiz do multi-módulo Maven):
## `docker build -f catalog/Dockerfile Projeto/Backend` ou o `build:` do compose. Porta 8083 (só na rede gerenciador-net).
## Estágio de build: compila só o catalog e as libs que ele usa (shared, catalog-client, reservation-client; -pl catalog -am)
FROM maven:3.9.7-eclipse-temurin-21-jammy AS build
WORKDIR /build
COPY pom.xml checkstyle.xml checkstyle-suppressions.xml ./
COPY shared ./shared
COPY catalog-client ./catalog-client
COPY reservation-client ./reservation-client
COPY catalog ./catalog
# O agregador lista todos os módulos: o Maven exige o pom de cada um, mas não o código (aqui só o pom dos outros serviços).
COPY monolito/pom.xml ./monolito/pom.xml
COPY notification/pom.xml ./notification/pom.xml
COPY reservation/pom.xml ./reservation/pom.xml
COPY identity-client/pom.xml ./identity-client/pom.xml
RUN mvn -B -DskipTests -pl catalog -am clean package

# Estágio runtime: imagem pequena apenas para executar o jar (Java 25)
FROM eclipse-temurin:25-jre-jammy
WORKDIR /app
COPY --from=build /build/catalog/target/*.jar ./app.jar
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
