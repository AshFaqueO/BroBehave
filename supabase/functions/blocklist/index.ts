// blocklist — distributes the adult-domain list used by automatic detection.
//
//   GET /functions/v1/blocklist             -> manifest.json (version, size, sha256, signature)
//   GET /functions/v1/blocklist?download=1  -> blocklist.bin
//
// Both files are produced and signed offline by tools/build_blocklist.py and uploaded to
// the private `blocklist` Storage bucket. This function only hands them out; the app
// accepts a list only if its signature verifies against the public key built into the app,
// so nothing served here is trusted on its own.
//
// The list contains hashes, not site names, and no user data is involved.
import { createClient } from "npm:@supabase/supabase-js@2";

Deno.serve(async (req) => {
  if (req.method !== "GET") return new Response("method not allowed", { status: 405 });

  const admin = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!, {
    auth: { persistSession: false },
  });
  const wantsFile = new URL(req.url).searchParams.has("download");
  const { data, error } = await admin.storage.from("blocklist").download(wantsFile ? "blocklist.bin" : "manifest.json");
  if (error || !data) return new Response("not published", { status: 404 });

  return new Response(data, {
    headers: {
      "Content-Type": wantsFile ? "application/octet-stream" : "application/json",
      "Cache-Control": "public, max-age=3600",
    },
  });
});
