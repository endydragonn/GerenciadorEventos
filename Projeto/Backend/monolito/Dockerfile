## Imagem do MONÓLITO (identity + gateway; o catalog virou serviço na #35). Contexto de build = Projeto/Backend/ (raiz do
## multi-módulo Maven, issue #32): `docker build -f monolito/Dockerfile Projeto/Backend` ou o `build:` do compose.
## Estágio de build: compila só o monólito e as libs shared, reservation-client, catalog-client, identity-client e admission-client (-pl monolito -am) com Java 21 e Maven 3.9
FROM maven:3.9.7-eclipse-temurin-21-jammy AS build
WORKDIR /build
COPY pom.xml checkstyle.xml checkstyle-suppressions.xml ./
COPY shared ./shared
COPY reservation-client ./reservation-client
COPY catalog-client ./catalog-client
COPY identity-client ./identity-client
COPY admission-client ./admission-client
COPY monolito ./monolito
# O agregador lista todos os módulos: o Maven exige o pom de cada um, mas não o código (aqui só o pom dos outros serviços).
COPY notification/pom.xml ./notification/pom.xml
COPY reservation/pom.xml ./reservation/pom.xml
COPY catalog/pom.xml ./catalog/pom.xml
COPY identity/pom.xml ./identity/pom.xml
COPY admission/pom.xml ./admission/pom.xml
COPY stock/pom.xml ./stock/pom.xml
COPY stock-client/pom.xml ./stock-client/pom.xml
RUN mvn -B -DskipTests -pl monolito -am clean package

# Estágio runtime: imagem pequena apenas para executar o jar (Java 25)
FROM eclipse-temurin:25-jre-jammy
WORKDIR /app
COPY --from=build /build/monolito/target/*.jar ./app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-Dserver.port=8081", "-jar", "/app/app.jar"]
