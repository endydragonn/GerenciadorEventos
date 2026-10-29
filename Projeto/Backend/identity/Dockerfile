## Imagem do serviço IDENTITY (issue #36). Contexto de build = Projeto/Backend/ (raiz do multi-módulo Maven):
## `docker build -f identity/Dockerfile Projeto/Backend` ou o `build:` do compose. Porta 8084 (só na rede gerenciador-net).
## Estágio de build: compila só o identity e as libs que ele usa (shared, identity-client; -pl identity -am)
FROM maven:3.9.7-eclipse-temurin-21-jammy AS build
WORKDIR /build
COPY pom.xml checkstyle.xml checkstyle-suppressions.xml ./
COPY shared ./shared
COPY identity-client ./identity-client
COPY identity ./identity
# O agregador lista todos os módulos: o Maven exige o pom de cada um, mas não o código (aqui só o pom dos outros).
COPY reservation-client/pom.xml ./reservation-client/pom.xml
COPY catalog-client/pom.xml ./catalog-client/pom.xml
COPY monolito/pom.xml ./monolito/pom.xml
COPY notification/pom.xml ./notification/pom.xml
COPY reservation/pom.xml ./reservation/pom.xml
COPY catalog/pom.xml ./catalog/pom.xml
COPY admission/pom.xml ./admission/pom.xml
COPY admission-client/pom.xml ./admission-client/pom.xml
COPY stock/pom.xml ./stock/pom.xml
COPY stock-client/pom.xml ./stock-client/pom.xml
RUN mvn -B -DskipTests -pl identity -am clean package

# Estágio runtime: imagem pequena apenas para executar o jar (Java 25)
FROM eclipse-temurin:25-jre-jammy
WORKDIR /app
# Diretório da chave de assinatura do JWT (JWT_SIGNING_KEY_PATH): o compose monta o volume nomeado identity-keys aqui
RUN mkdir -p /var/lib/identity
COPY --from=build /build/identity/target/*.jar ./app.jar
EXPOSE 8084
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
