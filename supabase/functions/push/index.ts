// push — turns one notification_outbox row into FCM messages.
//
// Called by the database (trigger `notification_outbox_dispatch`, via pg_net) with a
// shared secret, never by the app. Per-type toggles and the "one reaction push per post
// per 15 minutes" rule are applied when the row is queued (see the Phase 2 migration);
// this function words the message and delivers it.
//
// Secrets (Edge Function environment only — never in the repo):
//   PUSH_WEBHOOK_SECRET   same value as the `push_function_secret` Vault secret
//   FCM_SERVICE_ACCOUNT   the Firebase service-account JSON (FCM HTTP v1)
//
// Messages are data-only (`title`, `body`, `tag`); the app builds the notification.
// Text is English for now; localisation is a later step.
import { createClient } from "npm:@supabase/supabase-js@2";

const RANK_NAMES: Record<string, string> = {
  clown: "Clown",
  noob: "Noob",
  novice: "Novice",
  average: "Average",
  advanced: "Advanced",
  sigma: "Sigma",
  chad: "Chad",
  absolute_chad: "Absolute Chad",
  giga_chad: "Giga Chad",
};
const REACTION_EMOJI: Record<string, string> = {
  haha: "😂",
  impressive: "🤯",
  fire: "🔥",
  respect: "👊",
  strong: "💪",
};

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

const base64url = (data: ArrayBuffer | string) => {
  const bytes = typeof data === "string" ? new TextEncoder().encode(data) : new Uint8Array(data);
  let binary = "";
  for (const b of bytes) binary += String.fromCharCode(b);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
};

/** OAuth2 access token for FCM from the service account (JWT bearer grant). */
async function fcmAccessToken(account: { client_email: string; private_key: string }): Promise<string> {
  const now = Math.floor(Date.now() / 1000);
  const header = base64url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claims = base64url(JSON.stringify({
    iss: account.client_email,
    scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token",
    iat: now,
    exp: now + 3600,
  }));
  const pem = account.private_key.replace(/-----[A-Z ]+-----/g, "").replace(/\s+/g, "");
  const key = await crypto.subtle.importKey(
    "pkcs8",
    Uint8Array.from(atob(pem), (c) => c.charCodeAt(0)),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const signature = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(`${header}.${claims}`));
  const response = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion: `${header}.${claims}.${base64url(signature)}`,
    }),
  });
  if (!response.ok) throw new Error("fcm_auth_failed");
  return (await response.json()).access_token;
}

type Outbox = {
  id: string;
  recipient_id: string;
  kind: "friend_request" | "milestone" | "tier_change" | "reaction";
  actor_id: string | null;
  event_id: string | null;
  payload: Record<string, unknown>;
  sent_at: string | null;
};

/** Friendly-banter wording, matching the in-app feed copy. */
export function wording(row: Outbox, actor: string, otherReactors: number): { title: string; body: string } {
  const rank = (key: unknown) => RANK_NAMES[String(key)] ?? "a new rank";
  switch (row.kind) {
    case "friend_request":
      return { title: "New bro request", body: `${actor} wants to add you.` };
    case "milestone":
      return {
        title: `${actor} ranked up`,
        body: `${actor} hit ${row.payload.streak_days} days and ranked up to ${rank(row.payload.rank)}.`,
      };
    case "tier_change":
      return row.payload.type === "tier_up"
        ? { title: `${actor} climbed back`, body: `${actor} climbed back up to ${rank(row.payload.to)}.` }
        : { title: `${actor} slipped`, body: `${actor} slipped down to ${rank(row.payload.to)}.` };
    case "reaction": {
      const emoji = REACTION_EMOJI[String(row.payload.reaction)] ?? "";
      return otherReactors > 0
        ? {
          title: "Your post is getting reactions",
          body: `${actor} and ${otherReactors} ${otherReactors === 1 ? "other" : "others"} reacted to your post.`,
        }
        : { title: "New reaction", body: `${actor} reacted ${emoji} to your post.`.replace("  ", " ") };
    }
  }
}

Deno.serve(async (req) => {
  const secret = Deno.env.get("PUSH_WEBHOOK_SECRET");
  if (!secret || req.headers.get("Authorization") !== `Bearer ${secret}`) {
    return json(401, { error: "unauthorized" });
  }
  const { outbox_id } = await req.json().catch(() => ({}));
  if (typeof outbox_id !== "string") return json(400, { error: "bad_request" });

  const admin = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!, {
    auth: { persistSession: false },
  });

  const { data: row } = await admin.from("notification_outbox").select("*").eq("id", outbox_id).maybeSingle<Outbox>();
  if (!row || row.sent_at) return json(200, { skipped: true });

  let actor = "Someone";
  if (row.actor_id) {
    const { data } = await admin.from("profiles").select("display_name, username").eq("id", row.actor_id).maybeSingle();
    actor = data?.display_name ?? data?.username ?? actor;
  }
  let otherReactors = 0;
  if (row.kind === "reaction" && row.event_id) {
    const { count } = await admin.from("reactions").select("*", { count: "exact", head: true }).eq("event_id", row.event_id);
    otherReactors = Math.max(0, (count ?? 1) - 1);
  }
  const { title, body } = wording(row, actor, otherReactors);

  const { data: tokens } = await admin.from("device_tokens").select("fcm_token").eq("user_id", row.recipient_id);
  const serviceAccount = Deno.env.get("FCM_SERVICE_ACCOUNT");
  let delivered = 0;

  if (tokens && tokens.length > 0 && serviceAccount) {
    const account = JSON.parse(serviceAccount);
    const accessToken = await fcmAccessToken(account);
    for (const { fcm_token } of tokens) {
      const response = await fetch(`https://fcm.googleapis.com/v1/projects/${account.project_id}/messages:send`, {
        method: "POST",
        headers: { Authorization: `Bearer ${accessToken}`, "Content-Type": "application/json" },
        body: JSON.stringify({
          message: {
            token: fcm_token,
            android: { priority: "HIGH" },
            // One notification per post (or per sender), so repeats replace each other.
            data: { title, body, tag: row.event_id ?? `${row.kind}:${row.actor_id ?? ""}` },
          },
        }),
      });
      if (response.ok) {
        delivered++;
      } else if (response.status === 404 || response.status === 400) {
        // The token is no longer valid (app uninstalled or data cleared): forget it.
        await admin.from("device_tokens").delete().eq("user_id", row.recipient_id).eq("fcm_token", fcm_token);
      }
    }
  }

  await admin.from("notification_outbox").update({ sent_at: new Date().toISOString() }).eq("id", row.id);
  return json(200, { delivered });
});
