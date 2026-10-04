FROM eclipse-temurin:25-jdk AS build
RUN apt-get update && apt-get install -y --no-install-recommends git curl ca-certificates && rm -rf /var/lib/apt/lists/*
WORKDIR /workspace/tend
COPY . .
RUN sh scripts/prepare && javac @cmd/compile && java @cmd/test

FROM eclipse-temurin:25-jre
RUN apt-get update && apt-get install -y --no-install-recommends git ca-certificates && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /workspace/tend/out/work.archaic.tend out/work.archaic.tend
COPY --from=build /workspace/tend/out/work.archaic.service.catalog out/work.archaic.service.catalog
COPY --from=build /workspace/tend/out/work.archaic.culpa out/work.archaic.culpa
COPY --from=build /workspace/tend/lib/bin/gson-2.14.0.jar lib/bin/gson-2.14.0.jar
COPY cmd/run cmd/run
COPY schema schema
RUN mkdir -p /var/lib/tend && chown 1000:1000 /var/lib/tend
USER 1000:1000
VOLUME ["/var/lib/tend"]
ENTRYPOINT ["java", "@cmd/run"]
CMD ["--help"]
