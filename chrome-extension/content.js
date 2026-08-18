window.addEventListener("message", event => {
  const message = event.data || {};
  if (event.source !== window || message.source !== "feedpilot-dashboard") return;
  if (message.type !== "OPEN_INSTAGRAM_SESSION") return;

  chrome.runtime.sendMessage(
    {
      type: "OPEN_INSTAGRAM_SESSION",
      requestId: message.requestId,
      accountId: message.accountId,
      adminSession: window.localStorage.getItem("tf_admin_session") || "",
      apiBase: window.location.origin,
      profileUrl: message.profileUrl
    },
    response => {
      const error = chrome.runtime.lastError?.message || response?.error || "";
      window.postMessage(
        {
          source: "feedpilot-instagram-extension",
          requestId: message.requestId,
          ok: !!response?.ok && !error,
          error
        },
        window.location.origin
      );
    }
  );
});
