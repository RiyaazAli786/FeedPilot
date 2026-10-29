# syntax=docker/dockerfile:1
# Build context is this `backend/` directory 

# ---- build ----
FROM mcr.microsoft.com/dotnet/sdk:10.0 AS build
WORKDIR /src

# Restore first (cached unless the csproj changes).
COPY src/FeedPilot.Api/FeedPilot.Api.csproj src/FeedPilot.Api/
RUN dotnet restore src/FeedPilot.Api/FeedPilot.Api.csproj

# Then build + publish.
COPY . .
RUN dotnet publish src/FeedPilot.Api/FeedPilot.Api.csproj -c Release -o /app /p:UseAppHost=false

# ---- runtime ----
FROM mcr.microsoft.com/dotnet/aspnet:10.0 AS runtime
WORKDIR /app
COPY --from=build /app .

ENV ASPNETCORE_ENVIRONMENT=Production
ENV DOTNET_HOSTBUILDER__RELOADCONFIGONCHANGE=false
# Runtime provides $PORT (or binds default); Program.cs binds to it. 8080 is only the documented default.
EXPOSE 8080

ENTRYPOINT ["dotnet", "FeedPilot.Api.dll"]
