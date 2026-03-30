FROM mcr.microsoft.com/dotnet/sdk:8.0 AS build

WORKDIR /build
COPY clients/csharp/src/Neo4j.AgentMemory/ clients/csharp/src/Neo4j.AgentMemory/
COPY demo/agents/sage/ demo/agents/sage/

WORKDIR /build/demo/agents/sage
RUN dotnet publish -c Release -o /app

FROM mcr.microsoft.com/dotnet/aspnet:8.0
COPY --from=build /app /app
WORKDIR /app

EXPOSE 8005
ENTRYPOINT ["dotnet", "Sage.dll"]
