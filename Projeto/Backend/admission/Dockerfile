## Imagem do serviço ADMISSION (issue #41). Contexto de build = Projeto/Backend/ (raiz do multi-módulo Maven):
## `docker build -f admission/Dockerfile Projeto/Backend` ou o `build:` do compose. Porta 8085 (só na rede gerenciador-net).
## Estágio de build: compila só o admission e a lib que ele usa (admission-client; -pl admission -am) com Java 21 e Maven 3.9.
## Sem `shared`: o admission não tem datasource. O módulo `shared` entra só como pom (o agregador o lista).
FROM maven:3.9.7-eclipse-temurin-21-jammy AS build
WORKDIR /build
COPY pom.xml checkstyle.xml checkstyle-suppressions.xml ./
COPY admission-client ./admission-client
COPY admission ./admission
# O agregador lista todos os módulos: o Maven exige o pom de cada um, mas não o código (aqui só o pom dos outros).
COPY shared/pom.xml ./shared/pom.xml
COPY reservation-client/pom.xml ./reservation-client/pom.xml
COPY catalog-client/pom.xml ./catalog-client/pom.xml
COPY identity-client/pom.xml ./identity-client/pom.xml
COPY monolito/pom.xml ./monolito/pom.xml
COPY notification/pom.xml ./notification/pom.xml
COPY reservation/pom.xml ./reservation/pom.xml
COPY catalog/pom.xml ./catalog/pom.xml
COPY identity/pom.xml ./identity/pom.xml
RUN mvn -B -DskipTests -pl admission -am clean package

# Estágio runtime: imagem pequena apenas para executar o jar (Java 25)
FROM eclipse-temurin:25-jre-jammy
WORKDIR /app
COPY --from=build /build/admission/target/*.jar ./app.jar
EXPOSE 8085
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
