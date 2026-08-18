const INSTAGRAM_COOKIE_URL = "https://www.instagram.com/";
const COOKIE_NAMES = new Set([
  "csrftoken",
  "datr",
  "ds_user_id",
  "ig_did",
  "mid",
  "rur",
  "sessionid",
  "shbid",
  "shbts",
  "wd"
]);

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  if (message?.type !== "OPEN_INSTAGRAM_SESSION") return false;

  openInstagramSession(message)
    .then(result => sendResponse(result))
    .catch(error => sendResponse({ ok: false, error: error.message || String(error) }));

  return true;
});

async function openInstagramSession(message) {
  if (!message.accountId) throw new Error("Missing account id.");
  if (!message.adminSession) throw new Error("Dashboard admin session is missing. Reconnect the dashboard.");

  const apiBase = new URL(message.apiBase);
  const response = await fetch(`${apiBase.origin}/api/admin/instagram-sessions/${message.accountId}/browser-cookies`, {
    headers: { "X-Admin-Session": message.adminSession }
  });

  if (!response.ok) {
    let detail = "";
    try {
      const body = await response.json();
      detail = body.message || "";
    } catch {
      detail = await response.text();
    }
    throw new Error(detail || `Cookie request failed with HTTP ${response.status}.`);
  }

  const data = await response.json();
  if (!Array.isArray(data.cookies) || data.cookies.length === 0) {
    throw new Error("No cookies returned for this account.");
  }

  await clearInstagramSessionCookies();
  for (const cookie of data.cookies) {
    if (!COOKIE_NAMES.has(cookie.name)) continue;
    await setCookie({
      url: INSTAGRAM_COOKIE_URL,
      name: cookie.name,
      value: cookie.value,
      domain: cookie.domain || ".instagram.com",
      path: cookie.path || "/",
      secure: cookie.secure !== false,
      httpOnly: !!cookie.httpOnly,
      sameSite: cookie.sameSite || "no_restriction",
      expirationDate: Number(cookie.expirationDate)
    });
  }

  await createTab(data.profileUrl || message.profileUrl || INSTAGRAM_COOKIE_URL);
  return { ok: true };
}

async function clearInstagramSessionCookies() {
  const cookies = await getAllCookies({ domain: "instagram.com" });
  await Promise.all(cookies
    .filter(cookie => COOKIE_NAMES.has(cookie.name))
    .map(cookie => removeCookie(cookie)));
}

function getAllCookies(details) {
  return new Promise((resolve, reject) => {
    chrome.cookies.getAll(details, cookies => {
      const error = chrome.runtime.lastError;
      if (error) reject(new Error(error.message));
      else resolve(cookies || []);
    });
  });
}

function setCookie(details) {
  return new Promise((resolve, reject) => {
    chrome.cookies.set(details, cookie => {
      const error = chrome.runtime.lastError;
      if (error) reject(new Error(error.message));
      else resolve(cookie);
    });
  });
}

function removeCookie(cookie) {
  const domain = cookie.domain.startsWith(".") ? cookie.domain.slice(1) : cookie.domain;
  const url = `${cookie.secure ? "https" : "http"}://${domain}${cookie.path || "/"}`;
  return new Promise(resolve => {
    chrome.cookies.remove({ url, name: cookie.name }, () => resolve());
  });
}

function createTab(url) {
  return new Promise((resolve, reject) => {
    chrome.tabs.create({ url }, tab => {
      const error = chrome.runtime.lastError;
      if (error) reject(new Error(error.message));
      else resolve(tab);
    });
  });
}
