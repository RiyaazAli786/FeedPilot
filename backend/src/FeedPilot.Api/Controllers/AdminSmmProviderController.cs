using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using FeedPilot.Api.Dtos;
using FeedPilot.Api.Services;

namespace FeedPilot.Api.Controllers;

[ApiController]
[AdminSession]
[Route("api/admin/smm-provider")]
public class AdminSmmProviderController : ControllerBase
{
    [HttpGet]
    public ActionResult<ApiError> Get() =>
        StatusCode(StatusCodes.Status410Gone,
            new ApiError("External SMM provider settings are disabled. FeedPilot processes app-placed orders only.", "EXTERNAL_ORDERS_DISABLED"));

    [HttpPatch]
    public ActionResult<ApiError> Update() =>
        StatusCode(StatusCodes.Status410Gone,
            new ApiError("External SMM provider settings are disabled. FeedPilot processes app-placed orders only.", "EXTERNAL_ORDERS_DISABLED"));
}
