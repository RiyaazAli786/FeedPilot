namespace FeedPilot.Api.Services;

/// <summary>
/// Resolves the database connection string across environments.
///
/// Render (and Heroku) expose the database as a single <c>DATABASE_URL</c> in the
/// <c>postgres://user:pass@host:port/db</c> form, which Npgsql does not accept directly — so it
/// is converted to the key/value form here. Otherwise the configured value is used
/// (<c>ConnectionStrings__Default</c> env var, or appsettings for local dev).
/// </summary>
public static class ConnectionStringHelper
{
    public static string? Resolve(IConfiguration config)
    {
        var url = Environment.GetEnvironmentVariable("DATABASE_URL");
        if (!string.IsNullOrWhiteSpace(url) &&
            (url.StartsWith("postgres://", StringComparison.OrdinalIgnoreCase) ||
             url.StartsWith("postgresql://", StringComparison.OrdinalIgnoreCase)))
        {
            return FromPostgresUrl(url);
        }

        return config.GetConnectionString("Default");
    }

    private static string FromPostgresUrl(string url)
    {
        var uri = new Uri(url);
        var parts = uri.UserInfo.Split(':', 2);
        var user = Uri.UnescapeDataString(parts[0]);
        var password = parts.Length > 1 ? Uri.UnescapeDataString(parts[1]) : string.Empty;
        var database = uri.AbsolutePath.TrimStart('/');
        var port = uri.Port > 0 ? uri.Port : 5432;

        // SSL is required by Render's managed Postgres; trust the server cert (Render terminates
        // it internally and does not expose a CA to pin against).
        return $"Host={uri.Host};Port={port};Database={database};Username={user};Password={password};" +
               "SSL Mode=Require;Trust Server Certificate=true";
    }
}
