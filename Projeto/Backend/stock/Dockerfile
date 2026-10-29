## Imagem do serviço STOCK (issue #47, saga stock + wallet). Contexto de build = Projeto/Backend/ (raiz do multi-módulo Maven):
## `docker build -f stock/Dockerfile Projeto/Backend` ou o `build:` do compose. Porta 8086 (só na rede gerenciador-net).
## Estágio de build: compila só o stock e as libs que ele usa (shared, stock-client; -pl stock -am) com Java 21 e Maven 3.9
FROM maven:3.9.7-eclipse-temurin-21-jammy AS build
WORKDIR /build
COPY pom.xml checkstyle.xml checkstyle-suppressions.xml ./
COPY shared ./shared
COPY reservation-client/pom.xml ./reservation-client/pom.xml
COPY stock-client ./stock-client
COPY stock ./stock
# O agregador lista todos os módulos: o Maven exige o pom de cada um, mas não o código (aqui só o pom dos outros serviços).
COPY monolito/pom.xml ./monolito/pom.xml
COPY notification/pom.xml ./notification/pom.xml
COPY catalog-client/pom.xml ./catalog-client/pom.xml
COPY identity-client/pom.xml ./identity-client/pom.xml
COPY catalog/pom.xml ./catalog/pom.xml
COPY identity/pom.xml ./identity/pom.xml
COPY admission/pom.xml ./admission/pom.xml
COPY admission-client/pom.xml ./admission-client/pom.xml
COPY reservation/pom.xml ./reservation/pom.xml
RUN mvn -B -DskipTests -pl stock -am clean package

# Estágio runtime: imagem pequena apenas para executar o jar (Java 25)
FROM eclipse-temurin:25-jre-jammy
WORKDIR /app
COPY --from=build /build/stock/target/stock-*-exec.jar ./app.jar
EXPOSE 8086
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
