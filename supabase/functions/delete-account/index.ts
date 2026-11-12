// delete-account — permanently deletes the calling user's account.
//
// Called by the app (Settings → Delete my account) with the user's own JWT. Removes the
// user's avatar files, then deletes the auth user; every table references the user with
// ON DELETE CASCADE, so all their data goes with it.
//
// The service role key is only ever read here, from the Edge Function environment.
import { createClient } from "npm:@supabase/supabase-js@2";

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });

Deno.serve(async (req) => {
  if (req.method !== "POST") return json(405, { error: "method_not_allowed" });

  const authorization = req.headers.get("Authorization");
  if (!authorization) return json(401, { error: "not_signed_in" });

  const url = Deno.env.get("SUPABASE_URL")!;

  // Identify the caller from their own token — never from the request body.
  const asUser = createClient(url, Deno.env.get("SUPABASE_ANON_KEY")!, {
    global: { headers: { Authorization: authorization } },
  });
  const { data: userData, error: userError } = await asUser.auth.getUser();
  if (userError || !userData.user) return json(401, { error: "not_signed_in" });
  const userId = userData.user.id;

  const admin = createClient(url, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!, {
    auth: { persistSession: false },
  });

  // Avatars live in a folder named after the user id.
  const { data: files } = await admin.storage.from("avatars").list(userId);
  if (files && files.length > 0) {
    await admin.storage.from("avatars").remove(files.map((f) => `${userId}/${f.name}`));
  }

  const { error: deleteError } = await admin.auth.admin.deleteUser(userId);
  if (deleteError) {
    // No user data in logs: only the fact that it failed.
    console.error("delete-account: deleting the auth user failed");
    return json(500, { error: "delete_failed" });
  }
  return json(200, { deleted: true });
});
