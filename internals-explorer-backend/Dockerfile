FROM eclipse-temurin:17-jdk
EXPOSE 8080

ARG JAR_FILE=./build/libs/internals-explorer-backend.jar
ADD ${JAR_FILE} app.jar

ENTRYPOINT ["java", "-jar", "/app.jar"]
