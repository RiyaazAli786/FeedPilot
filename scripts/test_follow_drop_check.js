'use strict';

/**
 * Tests whether an Instagram follow sticks after the app's 4-8 second verification window.
 *
 * Dry-run by default. Add --execute to send the follow mutation.
 *
 * Usage:
 *   node scripts/test_follow_drop_check.js --username target_user --execute
 *   node scripts/test_follow_drop_check.js --target-id 123456789 --username target_user --execute
 *   node scripts/test_follow_drop_check.js --username target_user --verify-only
 *
 * Options:
 *   --username <handle>     Target username. Required when --target-id is omitted.
 *   --target-id <id>        Numeric Instagram user id.
 *   --session <file>        Cookie file. Defaults to scripts/testsession.json.
 *   --cookies <string>      Cookie header string. IG_COOKIES env var also works.
 *   --graphql-only          Use only GraphQL follow; do not try REST fallback.
 *   --no-graphql            Use only REST friendships/create.
 *   --execute               Actually send the follow request.
 *   --verify-only           Do not follow; only call friendships/show and print state.
 *   --wait-min <seconds>    Minimum wait before verification. Default: 4.
 *   --wait-max <seconds>    Maximum wait before verification. Default: 8.
 *   --out <file>            Save last raw response body to a file.
 */

const fs = require('fs');
const https = require('https');
const path = require('path');
const { URLSearchParams } = require('url');

const INSTAGRAM_APP_ID = '936619743392459';
const ASBD_ID = '359341';
const FOLLOW_GRAPHQL_DOC_ID = '26508036048874888';
const DEFAULT_USER_AGENT =
  'Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.6778.135 Mobile Safari/537.36';
const DEFAULT_SESSION_FILE = path.join(__dirname, 'testsession.json');

function parseArgs(argv) {
  const args = {
    execute: false,
    verifyOnly: false,
    graphqlOnly: false,
    noGraphql: false,
    username: '',
    targetId: '',
    session: '',
    cookies: '',
    waitMin: 4,
    waitMax: 8,
    out: ''
  };

  for (let i = 2; i < argv.length; i++) {
    const arg = argv[i];
    const next = () => argv[++i] || '';
    if (arg === '--execute') args.execute = true;
    else if (arg === '--verify-only') args.verifyOnly = true;
    else if (arg === '--graphql-only') args.graphqlOnly = true;
    else if (arg === '--no-graphql') args.noGraphql = true;
    else if (arg === '--target-id') args.targetId = next();
    else if (arg === '--username') args.username = cleanUsername(next());
    else if (arg === '--session') args.session = next();
    else if (arg === '--cookies') args.cookies = next();
    else if (arg === '--wait-min') args.waitMin = Number(next());
    else if (arg === '--wait-max') args.waitMax = Number(next());
    else if (arg === '--out') args.out = next();
    else if (!args.targetId && /^\d+$/.test(arg)) args.targetId = arg;
    else if (!args.username) args.username = cleanUsername(arg);
    else throw new Error(`Unknown argument: ${arg}`);
  }

  if (!Number.isFinite(args.waitMin) || args.waitMin < 0) throw new Error('--wait-min must be a positive number.');
  if (!Number.isFinite(args.waitMax) || args.waitMax < args.waitMin) throw new Error('--wait-max must be >= --wait-min.');
  return args;
}

function cleanUsername(input) {
  if (!input) return '';
  const trimmed = String(input).trim().replace(/^@/, '');
  const match = trimmed.match(/instagram\.com\/([^/?#]+)/i);
  return (match ? match[1] : trimmed).replace(/\/+$/, '');
}

function loadSessionCookies(filePath) {
  if (!filePath || !fs.existsSync(filePath)) return '';
  const raw = fs.readFileSync(filePath, 'utf8').trim();
  if (!raw) return '';

  try {
    const parsed = JSON.parse(raw);
    if (typeof parsed === 'string') return parsed;
    if (parsed.sessionCookies || parsed.cookieHeader || parsed.cookies) {
      const value = parsed.sessionCookies || parsed.cookieHeader || parsed.cookies;
      return Array.isArray(value) ? cookiesArrayToHeader(value) : String(value);
    }
    if (Array.isArray(parsed)) return cookiesArrayToHeader(parsed);
    if (typeof parsed === 'object') {
      return Object.entries(parsed)
        .filter(([, value]) => value !== undefined && value !== null)
        .map(([name, value]) => `${name}=${value}`)
        .join('; ');
    }
  } catch (_) {
    return raw;
  }

  return '';
}

function cookiesArrayToHeader(items) {
  return items
    .map(item => {
      const name = item.name || item.key;
      const value = item.value;
      return name && value !== undefined ? `${name}=${value}` : '';
    })
    .filter(Boolean)
    .join('; ');
}

function extractCookie(cookieHeader, name) {
  const pattern = new RegExp(`(?:^|;\\s*)${escapeRegExp(name)}=([^;]+)`);
  return cookieHeader.match(pattern)?.[1] || '';
}

function escapeRegExp(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function createJazoest(seed) {
  const id = seed && seed.trim() ? seed : `${Date.now()}-${Math.random()}`;
  let sum = 0;
  for (const ch of Buffer.from(id, 'ascii')) sum += ch;
  return `2${sum}`;
}

function generateInstagramAjax() {
  const high = Math.floor(Math.random() * 900000000) + 100000000;
  const low = Math.floor(Math.random() * 10);
  return String(high * 10 + low);
}

function extractWwwClaim(cookieHeader) {
  return extractCookie(cookieHeader, '__igwwwclaim') || extractCookie(cookieHeader, 'x_ig_www_claim') || '0';
}

function baseHeaders(cookieHeader, referer, contentType = 'application/x-www-form-urlencoded') {
  const headers = {
    'User-Agent': DEFAULT_USER_AGENT,
    'sec-ch-ua': '"Google Chrome";v="131", "Chromium";v="131", "Not_A Brand";v="24"',
    'sec-ch-ua-mobile': '?1',
    'sec-ch-ua-platform': '"Android"',
    'sec-ch-ua-platform-version': '"14.0.0"',
    'sec-ch-ua-model': '"SM-S918B"',
    'Accept-Language': 'en-US,en;q=0.9',
    Accept: '*/*',
    'X-IG-App-ID': INSTAGRAM_APP_ID,
    'X-IG-WWW-Claim': extractWwwClaim(cookieHeader),
    'X-Requested-With': 'XMLHttpRequest',
    'X-CSRFToken': extractCookie(cookieHeader, 'csrftoken'),
    Cookie: cookieHeader,
    Referer: referer,
    'Sec-Fetch-Dest': 'empty',
    'Sec-Fetch-Mode': 'cors',
    'Sec-Fetch-Site': 'same-origin'
  };

  if (contentType) {
    headers['Content-Type'] = contentType;
    headers.Origin = 'https://www.instagram.com';
  }
  return headers;
}

function buildRestFollowRequest(targetId, username, cookieHeader) {
  const referer = username ? `https://www.instagram.com/${username}/` : 'https://www.instagram.com/';
  const body = new URLSearchParams({
    container_module: 'profile',
    nav_chain: 'PolarisProfilePostsTabRoot:profilePage:1:via_cold_start',
    user_id: targetId,
    include_follow_friction_check: 'true',
    jazoest: createJazoest(targetId)
  }).toString();

  return {
    name: 'REST friendships/create',
    method: 'POST',
    url: `https://www.instagram.com/api/v1/friendships/create/${targetId}/`,
    headers: {
      ...baseHeaders(cookieHeader, referer),
      'X-Instagram-AJAX': generateInstagramAjax(),
      'X-ASBD-ID': ASBD_ID
    },
    body
  };
}

async function buildGraphqlFollowRequest(targetId, username, cookieHeader, fetchTokens = true) {
  const referer = username ? `https://www.instagram.com/${username}/` : 'https://www.instagram.com/';
  const tokens = fetchTokens ? await fetchPageTokens(cookieHeader, username) : { lsd: '', fbDtsg: '' };
  const lsd = tokens.lsd || extractCookie(cookieHeader, 'lsd') || '9TjJvcwkR5rOoXDuAO_1-5';

  const params = new URLSearchParams({
    __comet_req: '7',
    jazoest: createJazoest(targetId),
    lsd,
    __crn: 'comet.igweb.PolarisProfilePostsTabRoute',
    fb_api_caller_class: 'RelayModern',
    fb_api_req_friendly_name: 'usePolarisFollowMutation',
    variables: JSON.stringify({
      target_user_id: targetId,
      container_module: 'profile',
      nav_chain: 'PolarisProfilePostsTabRoot:profilePage:1:via_cold_start'
    }),
    doc_id: FOLLOW_GRAPHQL_DOC_ID
  });
  if (tokens.fbDtsg) params.set('fb_dtsg', tokens.fbDtsg);

  return {
    name: 'GraphQL usePolarisFollowMutation',
    method: 'POST',
    url: 'https://www.instagram.com/api/graphql',
    headers: {
      ...baseHeaders(cookieHeader, referer),
      'X-FB-LSD': lsd,
      'X-FB-Friendly-Name': 'usePolarisFollowMutation'
    },
    body: params.toString(),
    tokenInfo: {
      lsd: Boolean(tokens.lsd),
      fbDtsg: Boolean(tokens.fbDtsg)
    }
  };
}

function buildFollowStateRequest(targetId, username, cookieHeader) {
  const referer = username ? `https://www.instagram.com/${username}/` : 'https://www.instagram.com/';
  return {
    name: 'GET friendships/show',
    method: 'GET',
    url: `https://www.instagram.com/api/v1/friendships/show/${targetId}/`,
    headers: baseHeaders(cookieHeader, referer, ''),
    body: ''
  };
}

function buildResolveRequest(username, cookieHeader) {
  const clean = cleanUsername(username);
  const referer = `https://www.instagram.com/${clean}/`;
  return {
    name: 'Resolve user id from profile page',
    method: 'GET',
    url: referer,
    headers: {
      ...baseHeaders(cookieHeader, referer, ''),
      Accept: 'text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8',
      'Upgrade-Insecure-Requests': '1'
    },
    body: ''
  };
}

async function resolveTargetId(username, cookieHeader) {
  const req = buildResolveRequest(username, cookieHeader);
  printRequestSummary(req);
  const res = await request(req);
  const body = res.body || '';
  const id =
    body.match(/"profile_id"\s*:\s*"(\d+)"/)?.[1] ||
    body.match(/"user_id"\s*:\s*"(\d+)"/)?.[1] ||
    body.match(/"id"\s*:\s*"(\d+)"[^{}]{0,500}"username"\s*:\s*"([^"]+)"/)?.[1] ||
    body.match(/"username"\s*:\s*"[^"]+"[^{}]{0,500}"id"\s*:\s*"(\d+)"/)?.[1] ||
    body.match(/"pk"\s*:\s*"?(\d+)"?/)?.[1] ||
    '';

  console.log('\nResolve result');
  console.log({
    http: res.statusCode,
    elapsedMs: res.elapsedMs,
    targetId: id || null,
    bodyPreview: body.slice(0, 500)
  });

  if (!(res.statusCode >= 200 && res.statusCode < 300) || !id) {
    throw new Error(`Could not resolve numeric user id for @${cleanUsername(username)}`);
  }
  return String(id);
}

async function fetchPageTokens(cookieHeader, username) {
  const target = username ? `https://www.instagram.com/${username}/` : 'https://www.instagram.com/accounts/edit/';
  const res = await request({
    name: 'Token page fetch',
    method: 'GET',
    url: target,
    headers: {
      ...baseHeaders(cookieHeader, target, ''),
      Accept: 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8'
    },
    body: ''
  });

  return {
    lsd:
      res.body.match(/"LSD",\[\],\{"token":"([^"]+)"/)?.[1] ||
      res.body.match(/"lsd"\s*,\s*"([^"]+)"/i)?.[1] ||
      '',
    fbDtsg:
      res.body.match(/"DTSGInitialData",\[\],\{"token":"([^"]+)"/)?.[1] ||
      res.body.match(/"token":"([^"]+)","async_get_token"/)?.[1] ||
      ''
  };
}

function request(req) {
  return new Promise((resolve, reject) => {
    const url = new URL(req.url);
    const body = req.body || '';
    const headers = { ...req.headers };
    if (req.method !== 'GET') headers['Content-Length'] = Buffer.byteLength(body);

    const started = Date.now();
    const httpReq = https.request({
      method: req.method,
      hostname: url.hostname,
      path: `${url.pathname}${url.search}`,
      headers
    }, res => {
      const chunks = [];
      res.on('data', chunk => chunks.push(chunk));
      res.on('end', () => {
        resolve({
          statusCode: res.statusCode,
          headers: res.headers,
          body: Buffer.concat(chunks).toString('utf8'),
          elapsedMs: Date.now() - started
        });
      });
    });

    httpReq.on('error', reject);
    httpReq.setTimeout(30000, () => httpReq.destroy(new Error('Request timeout')));
    if (body) httpReq.write(body);
    httpReq.end();
  });
}

function mergeCookies(cookieHeader, res) {
  const pairs = new Map(
    cookieHeader
      .split(';')
      .map(x => x.trim())
      .filter(Boolean)
      .map(x => {
        const index = x.indexOf('=');
        return index === -1 ? [x, ''] : [x.slice(0, index), x.slice(index + 1)];
      })
  );

  const setCookies = Array.isArray(res.headers['set-cookie']) ? res.headers['set-cookie'] : [];
  for (const item of setCookies) {
    const first = String(item).split(';')[0].trim();
    const index = first.indexOf('=');
    if (index > 0) pairs.set(first.slice(0, index), first.slice(index + 1));
  }

  const claim = String(res.headers['x-ig-set-www-claim'] || '').trim();
  if (claim) pairs.set('__igwwwclaim', claim);

  return Array.from(pairs.entries()).map(([key, value]) => `${key}=${value}`).join('; ');
}

function classifyFollowResponse(res) {
  const body = res.body || '';
  const hasError =
    body.includes('"status":"fail"') ||
    body.includes('"errors":[') ||
    body.includes('"error":') ||
    body.includes('feedback_required') ||
    body.includes('spam') ||
    body.includes('checkpoint_required') ||
    body.includes('login_required');
  const followingConfirmed =
    body.includes('"following":true') ||
    body.includes('"following": true') ||
    body.includes('"outgoing_request":true') ||
    body.includes('"outgoing_request": true');

  return {
    okHttp: res.statusCode >= 200 && res.statusCode < 300,
    hasError,
    followingConfirmed,
    appInitialSuccess: res.statusCode >= 200 && res.statusCode < 300 && !hasError && followingConfirmed
  };
}

function classifyFollowState(res) {
  const body = res.body || '';
  let json = null;
  try {
    json = JSON.parse(body);
  } catch (_) {}

  const following = json ? Boolean(json.following) : textHasTrue(body, 'following');
  const outgoingRequest = json ? Boolean(json.outgoing_request) : textHasTrue(body, 'outgoing_request');
  const hasFollowing = json ? Object.prototype.hasOwnProperty.call(json, 'following') : textHasAny(body, 'following');
  const hasOutgoing = json ? Object.prototype.hasOwnProperty.call(json, 'outgoing_request') : textHasAny(body, 'outgoing_request');
  const blocking = json ? Boolean(json.blocking) : textHasTrue(body, 'blocking');
  const restricted = json ? Boolean(json.restricted_by_viewer) : textHasTrue(body, 'restricted_by_viewer');

  let state = 'INCONCLUSIVE';
  let appFinalSuccess = true;
  if (following || outgoingRequest) {
    state = 'CONFIRMED';
    appFinalSuccess = true;
  } else if (blocking || restricted || hasFollowing || hasOutgoing) {
    state = 'DROPPED';
    appFinalSuccess = false;
  }

  return {
    http: res.statusCode,
    state,
    following,
    outgoingRequest,
    blocking,
    restricted,
    appFinalSuccess
  };
}

function textHasTrue(body, field) {
  return body.includes(`"${field}":true`) || body.includes(`"${field}": true`);
}

function textHasAny(body, field) {
  return body.includes(`"${field}":true`) ||
    body.includes(`"${field}": true`) ||
    body.includes(`"${field}":false`) ||
    body.includes(`"${field}": false`);
}

function printRequestSummary(req) {
  console.log(`\n${req.name}`);
  console.log(`${req.method} ${req.url}`);
  console.log('headers:', maskHeaders(req.headers));
  if (req.body) console.log('body:', req.body);
  if (req.tokenInfo) console.log('tokenInfo:', req.tokenInfo);
}

function printResponse(label, res, extra = {}) {
  console.log(`\n${label} response`);
  console.log({
    http: res.statusCode,
    elapsedMs: res.elapsedMs,
    setCookieCount: Array.isArray(res.headers['set-cookie']) ? res.headers['set-cookie'].length : 0,
    xIgSetWwwClaim: res.headers['x-ig-set-www-claim'] || '',
    ...extra,
    bodyPreview: (res.body || '').slice(0, 700)
  });
}

function maskHeaders(headers) {
  const copy = { ...headers };
  if (copy.Cookie) {
    const names = copy.Cookie.split(';').map(x => x.trim().split('=')[0]).filter(Boolean);
    copy.Cookie = `[${names.length} cookies: ${names.join(', ')}]`;
  }
  if (copy['X-CSRFToken']) copy['X-CSRFToken'] = `${copy['X-CSRFToken'].slice(0, 6)}...`;
  return copy;
}

function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

function randomInt(min, max) {
  return Math.floor(Math.random() * (max - min + 1)) + min;
}

async function verifyFollowState(targetId, username, cookieHeader, label = 'Verification') {
  const req = buildFollowStateRequest(targetId, username, cookieHeader);
  printRequestSummary(req);
  const res = await request(req);
  const verdict = classifyFollowState(res);
  printResponse(label, res, verdict);
  return { res, verdict };
}

async function main() {
  const args = parseArgs(process.argv);
  const sessionFile = args.session || DEFAULT_SESSION_FILE;
  let cookieHeader = args.cookies || process.env.IG_COOKIES || loadSessionCookies(sessionFile);

  if (!args.targetId && !args.username) {
    throw new Error('Pass --username <handle>, or pass --target-id <numeric_instagram_user_id>.');
  }
  if (args.targetId && !/^\d+$/.test(args.targetId)) {
    throw new Error('--target-id must be numeric.');
  }
  if (!cookieHeader || !cookieHeader.includes('sessionid=')) {
    throw new Error(`Missing Instagram cookies. Put them in ${sessionFile}, or use --cookies, --session, or IG_COOKIES.`);
  }
  if (!extractCookie(cookieHeader, 'csrftoken')) {
    console.warn('Warning: csrftoken cookie missing; Instagram usually rejects write requests without it.');
  }

  if (!args.targetId) {
    args.targetId = await resolveTargetId(args.username, cookieHeader);
  }

  console.log('\nTarget');
  console.log({ username: args.username || null, targetId: args.targetId });

  if (args.verifyOnly) {
    await verifyFollowState(args.targetId, args.username, cookieHeader, 'Verify-only');
    return;
  }

  if (!args.execute) {
    const preview = args.noGraphql
      ? buildRestFollowRequest(args.targetId, args.username, cookieHeader)
      : await buildGraphqlFollowRequest(args.targetId, args.username, cookieHeader, false);
    printRequestSummary(preview);
    console.log('\nDry run only. Add --execute to send the follow request.');
    return;
  }

  let lastResponse = null;
  if (!args.noGraphql) {
    const graphql = await buildGraphqlFollowRequest(args.targetId, args.username, cookieHeader);
    printRequestSummary(graphql);
    lastResponse = await request(graphql);
    cookieHeader = mergeCookies(cookieHeader, lastResponse);
    const initial = classifyFollowResponse(lastResponse);
    printResponse(graphql.name, lastResponse, initial);

    if (initial.appInitialSuccess) {
      await verifyAfterWait(args, cookieHeader);
      if (args.out) fs.writeFileSync(args.out, lastResponse.body || '');
      return;
    }

    if (args.graphqlOnly) {
      console.log('\nGraphQL did not confirm following. REST fallback disabled by --graphql-only.');
      if (args.out) fs.writeFileSync(args.out, lastResponse.body || '');
      return;
    }

    console.log('\nGraphQL did not confirm following; trying REST friendships/create fallback...');
  }

  if (!args.graphqlOnly) {
    const rest = buildRestFollowRequest(args.targetId, args.username, cookieHeader);
    printRequestSummary(rest);
    lastResponse = await request(rest);
    cookieHeader = mergeCookies(cookieHeader, lastResponse);
    const initial = classifyFollowResponse(lastResponse);
    printResponse(rest.name, lastResponse, initial);

    if (initial.appInitialSuccess) {
      await verifyAfterWait(args, cookieHeader);
      if (args.out) fs.writeFileSync(args.out, lastResponse.body || '');
      return;
    }
  }

  if (args.out && lastResponse) fs.writeFileSync(args.out, lastResponse.body || '');

  if (args.noGraphql) {
    console.log('\nREST did not confirm following. GraphQL disabled by --no-graphql.');
  } else {
    console.log('\nNeither GraphQL primary nor REST fallback confirmed following.');
  }
  console.log('No verification because initial follow was not confirmed.');
  process.exitCode = 2;
}

async function verifyAfterWait(args, cookieHeader) {
  const waitSeconds = randomInt(Math.round(args.waitMin), Math.round(args.waitMax));
  console.log(`\nInitial follow accepted. Waiting ${waitSeconds}s before friendships/show verification...`);
  await sleep(waitSeconds * 1000);

  const { verdict } = await verifyFollowState(args.targetId, args.username, cookieHeader, 'Post-follow verification');
  console.log('\nFinal app-style verdict');
  if (verdict.state === 'CONFIRMED') {
    console.log('SUCCESS: follow is still present / outgoing request still exists.');
  } else if (verdict.state === 'DROPPED') {
    console.log('FAILURE: Instagram accepted then dropped the follow.');
    process.exitCode = 2;
  } else {
    console.log('INCONCLUSIVE: verification did not expose relationship fields; app would keep initial success.');
  }
}

main().catch(err => {
  console.error(`\nERROR: ${err.message}`);
  process.exitCode = 1;
});
