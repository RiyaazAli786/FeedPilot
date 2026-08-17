namespace FeedPilot.Api.Domain;

public class PickedUsername
{
    public Guid Id { get; set; } = Guid.NewGuid();
    public string Username { get; set; } = string.Empty;
    public Guid? UserId { get; set; }
    public string DeviceId { get; set; } = string.Empty;
    /// <summary>Which clone made the pick. DeviceId is the hardware fingerprint shared by every
    /// clone on one phone, so this is what actually separates one clone's picked list from
    /// another's on the same device. Defaults to "unknown" for rows from older clients.</summary>
    public string AppId { get; set; } = "unknown";
    public DateTime PickedAt { get; set; } = DateTime.UtcNow;
}
