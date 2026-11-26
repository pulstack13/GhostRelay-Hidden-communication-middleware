import { createClient } from "npm:@supabase/supabase-js@2";

type MessageRecord = {
  sender_id?: string;
  recipient_id?: string;
};

type DatabaseWebhook = {
  type?: string;
  table?: string;
  schema?: string;
  record?: MessageRecord;
};

const escapeHtml = (value: string) => value.replace(/[&<>"']/g, (char) => ({
  "&": "&amp;",
  "<": "&lt;",
  ">": "&gt;",
  '"': "&quot;",
  "'": "&#39;",
}[char] ?? char));

Deno.serve(async (request) => {
  if (request.method !== "POST") return new Response("Method not allowed", { status: 405 });

  const webhookSecret = Deno.env.get("STORE_MESSAGE_WEBHOOK_SECRET");
  if (!webhookSecret || request.headers.get("x-store-webhook-secret") !== webhookSecret) {
    return new Response("Unauthorized", { status: 401 });
  }

  let event: DatabaseWebhook;
  try {
    event = await request.json();
  } catch {
    return Response.json({ error: "Invalid webhook payload" }, { status: 400 });
  }

  if (event.type !== "INSERT" || event.schema !== "public" || event.table !== "messages") {
    return Response.json({ ignored: true });
  }

  const senderId = event.record?.sender_id;
  const recipientId = event.record?.recipient_id;
  const supabaseUrl = Deno.env.get("SUPABASE_URL");
  const supabaseSecretKey = Deno.env.get("STORE_SUPABASE_SECRET_KEY");
  const resendApiKey = Deno.env.get("RESEND_API_KEY");
  const fromEmail = Deno.env.get("STORE_FROM_EMAIL");

  if (!senderId || !recipientId || !supabaseUrl || !supabaseSecretKey || !resendApiKey || !fromEmail) {
    console.error("Email notification is missing an event field or server secret");
    return Response.json({ error: "Email notification is not configured" }, { status: 500 });
  }

  const admin = createClient(supabaseUrl, supabaseSecretKey, {
    auth: { persistSession: false, autoRefreshToken: false },
  });

  const { data: leaseToken, error: claimError } = await admin.rpc(
    "claim_message_email_cooldown",
    { p_user_id: recipientId },
  );
  if (claimError) {
    console.error("Could not claim email cooldown", claimError.message);
    return Response.json({ error: "Could not claim email cooldown" }, { status: 500 });
  }
  if (!leaseToken) return Response.json({ suppressed: true, reason: "30-minute cooldown" });

  let sent = false;
  try {
    const [{ data: recipientResult, error: recipientError }, { data: senderProfile }] = await Promise.all([
      admin.auth.admin.getUserById(recipientId),
      admin.from("profiles").select("display_name").eq("id", senderId).maybeSingle(),
    ]);

    if (recipientError) throw recipientError;
    const recipientEmail = recipientResult.user?.email;
    if (!recipientEmail) return Response.json({ skipped: true, reason: "Recipient has no email address" });

    const senderName = senderProfile?.display_name || "Someone";
    const safeSenderName = escapeHtml(senderName);
    const html = `
      <div style="font-family:Arial,sans-serif;line-height:1.6;color:#17212b">
        <h2>You have a new message on Store</h2>
        <p><strong>${safeSenderName}</strong> sent you a message. Open Store to reply.</p>
        <hr style="border:0;border-top:1px solid #e5e7eb;margin:24px 0" />
        <p style="color:#64748b;font-size:13px">Connect your Supabase with email today. Cheap pricing. Fast storage.</p>
      </div>`;

    const response = await fetch("https://api.resend.com/emails", {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${resendApiKey}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        from: fromEmail,
        to: [recipientEmail],
        subject: `New message from ${senderName} on Store`,
        html,
      }),
    });

    if (!response.ok) {
      const details = await response.text();
      console.error("Email provider rejected a notification", response.status, details.slice(0, 300));
      return Response.json({ error: "Email provider rejected the notification" }, { status: 502 });
    }
    sent = true;
    return Response.json({ sent: true });
  } catch (error) {
    console.error("Message email failed", error instanceof Error ? error.message : "unknown error");
    return Response.json({ error: "Could not send the message email" }, { status: 502 });
  } finally {
    const { error: finishError } = await admin.rpc("finish_message_email_cooldown", {
      p_user_id: recipientId,
      p_lease_token: leaseToken,
      p_sent: sent,
    });
    if (finishError) console.error("Could not finish email cooldown", finishError.message);
  }
});
