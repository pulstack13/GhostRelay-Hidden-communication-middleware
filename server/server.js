import { createServer } from "node:http";
import { randomUUID } from "node:crypto";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { basename, extname, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";

const messages = [];
const maxMessages = 500;
const port = Number(process.env.PORT || 8787);
const host = "0.0.0.0";
const publicDir = resolve(fileURLToPath(new URL("./public/", import.meta.url)));
const uploadDir = resolve(fileURLToPath(new URL("./uploads/", import.meta.url)));
const maxAttachmentBytes = 12 * 1024 * 1024;
const mime = { ".html": "text/html; charset=utf-8", ".webmanifest": "application/manifest+json", ".svg": "image/svg+xml" };

function send(res, status, body) {
  res.writeHead(status, { "content-type": "application/json; charset=utf-8", "cache-control": "no-store", "access-control-allow-origin": "*", "access-control-allow-methods": "GET, POST, OPTIONS", "access-control-allow-headers": "content-type" });
  res.end(JSON.stringify(body));
}

const server = createServer(async (req, res) => {
  let url;
  try { url = new URL(req.url || "/", "http://localhost"); } catch { return send(res, 400, { error: "invalid_url" }); }
  if (req.method === "OPTIONS") return send(res, 204, {});
  if (req.method === "GET" && url.pathname === "/health") {
    return send(res, 200, { status: "prototype", storage: "memory", e2ee: false });
  }
  if (req.method === "GET" && url.pathname === "/messages") {
    return send(res, 200, { messages });
  }
  if (req.method === "POST" && url.pathname === "/attachments") {
    const chunks = [];
    let rawBytes = 0;
    for await (const chunk of req) {
      rawBytes += chunk.length;
      if (rawBytes > Math.ceil(maxAttachmentBytes * 1.4)) return send(res, 413, { error: "attachment_too_large" });
      chunks.push(chunk);
    }
    const raw = Buffer.concat(chunks).toString("utf8");
    let input;
    try { input = JSON.parse(raw); } catch { return send(res, 400, { error: "invalid_json" }); }
    if (typeof input?.senderDeviceId !== "string" || typeof input?.name !== "string" || typeof input?.mimeType !== "string" || typeof input?.data !== "string") {
      return send(res, 400, { error: "expected_senderDeviceId_name_mimeType_and_base64_data" });
    }
    const bytes = Buffer.from(input.data, "base64");
    if (!bytes.length || bytes.length > maxAttachmentBytes || bytes.toString("base64") !== input.data) return send(res, 413, { error: "attachment_too_large_or_invalid" });
    const id = randomUUID();
    const extension = extname(basename(input.name)).toLowerCase().replace(/[^.a-z0-9]/g, "").slice(0, 12) || ".bin";
    await mkdir(uploadDir, { recursive: true });
    const safeMimeType = /^[a-z0-9!#$&^_.+-]+\/[a-z0-9!#$&^_.+-]+$/i.test(input.mimeType) ? input.mimeType.slice(0, 120) : "application/octet-stream";
    const attachment = { id, name: basename(input.name).replace(/[\r\n\0]/g, "_").slice(0, 180), mimeType: safeMimeType, size: bytes.length, url: `/attachments/${id}` };
    await writeFile(resolve(uploadDir, `${id}${extension}`), bytes, { flag: "wx" });
    await writeFile(resolve(uploadDir, `${id}.meta.json`), JSON.stringify(attachment), { flag: "wx" });
    return send(res, 201, { attachment });
  }
  if (req.method === "POST" && url.pathname === "/messages") {
    let raw = "";
    for await (const chunk of req) {
      raw += chunk;
      if (raw.length > 16_384) return send(res, 413, { error: "payload_too_large" });
    }
    let input;
    try { input = JSON.parse(raw); } catch { return send(res, 400, { error: "invalid_json" }); }
    const hasText = typeof input?.text === "string" && input.text.trim().length > 0;
    let verifiedAttachment = null;
    const attachmentId = input?.attachment?.id;
    if (typeof attachmentId === "string" && /^[0-9a-f-]{36}$/i.test(attachmentId) && input.attachment.url === `/attachments/${attachmentId}`) {
      try {
        const candidate = JSON.parse(await readFile(resolve(uploadDir, `${attachmentId}.meta.json`), "utf8"));
        if (candidate.id === attachmentId && candidate.url === input.attachment.url) verifiedAttachment = candidate;
      } catch {}
    }
    const hasAttachment = verifiedAttachment !== null;
    if (typeof input?.senderDeviceId !== "string" || (!hasText && !hasAttachment) || (typeof input.text === "string" && input.text.length > 4000)) {
      return send(res, 400, { error: "expected_senderDeviceId_and_text_or_attachment" });
    }
    const entry = { id: randomUUID(), senderDeviceId: input.senderDeviceId, text: hasText ? input.text.trim() : "", ...(hasAttachment ? { attachment: verifiedAttachment } : {}), createdAt: new Date().toISOString() };
    messages.push(entry);
    if (messages.length > maxMessages) messages.shift();
    return send(res, 201, entry);
  }
  const attachmentMatch = req.method === "GET" && url.pathname.match(/^\/attachments\/([0-9a-f-]{36})$/i);
  if (attachmentMatch) {
    try {
      const files = await import("node:fs/promises");
      const names = await files.readdir(uploadDir);
      const fileName = names.find((name) => name.startsWith(attachmentMatch[1] + ".") && !name.endsWith(".meta.json"));
      if (!fileName) return send(res, 404, { error: "not_found" });
      const body = await readFile(resolve(uploadDir, fileName));
      let attachmentMeta;
      try { attachmentMeta = JSON.parse(await readFile(resolve(uploadDir, `${attachmentMatch[1]}.meta.json`), "utf8")); } catch { attachmentMeta = null; }
      const type = attachmentMeta?.mimeType || "application/octet-stream";
      const inline = new Set(["image/jpeg", "image/png", "image/gif", "image/webp", "audio/mpeg", "audio/mp4", "audio/ogg", "audio/wav", "audio/webm"]).has(type.toLowerCase());
      res.writeHead(200, { "content-type": type, "content-length": body.length, "cache-control": "no-store", "x-content-type-options": "nosniff", "content-disposition": inline ? "inline" : "attachment" });
      return res.end(body);
    } catch { return send(res, 404, { error: "not_found" }); }
  }
  if (req.method === "GET") {
    let requested;
    try { requested = decodeURIComponent(url.pathname === "/" ? "/index.html" : url.pathname); } catch { return send(res, 400, { error: "invalid_path" }); }
    const target = resolve(publicDir, `.${requested}`);
    if (target !== publicDir && !target.startsWith(publicDir + sep)) return send(res, 400, { error: "invalid_path" });
    try {
      const body = await readFile(target);
      res.writeHead(200, { "content-type": mime[extname(target)] || "application/octet-stream", "cache-control": "no-store", "x-content-type-options": "nosniff" });
      return res.end(body);
    } catch { return send(res, 404, { error: "not_found" }); }
  }
  return send(res, 404, { error: "not_found" });
});

server.listen(port, host, () => console.log(`Prototype server listening on http://${host}:${port}`));
