## Imagem do serviço NOTIFICATION (issue #32). Contexto de build = Projeto/Backend/ (raiz do multi-módulo Maven):
## `docker build -f notification/Dockerfile Projeto/Backend` ou o `build:` do compose. Sem EXPOSE: o serviço não tem HTTP.
## Estágio de build: compila só o notification e a lib shared (-pl notification -am) com Java 21 e Maven 3.9
FROM maven:3.9.7-eclipse-temurin-21-jammy AS build
WORKDIR /build
COPY pom.xml checkstyle.xml checkstyle-suppressions.xml ./
COPY shared ./shared
COPY notification ./notification
# O agregador lista todos os módulos: o Maven exige o pom de cada um, mas não o código (aqui só o pom dos outros).
COPY monolito/pom.xml ./monolito/pom.xml
COPY reservation-client/pom.xml ./reservation-client/pom.xml
COPY reservation/pom.xml ./reservation/pom.xml
COPY catalog-client/pom.xml ./catalog-client/pom.xml
COPY catalog/pom.xml ./catalog/pom.xml
RUN mvn -B -DskipTests -pl notification -am clean package

# Estágio runtime: imagem pequena apenas para executar o jar (Java 25)
FROM eclipse-temurin:25-jre-jammy
WORKDIR /app
COPY --from=build /build/notification/target/*.jar ./app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
