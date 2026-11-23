FROM eclipse-temurin:17-jre-jammy
ENV JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai"
WORKDIR /app
RUN useradd --uid 10001 --no-create-home --shell /usr/sbin/nologin opsatlas
COPY --chmod=444 backend/target/opsatlas-0.1.0.jar /app/opsatlas.jar
COPY frontend/dist /app/ui
USER opsatlas
EXPOSE 8092
ENTRYPOINT ["java", "-jar", "/app/opsatlas.jar"]
